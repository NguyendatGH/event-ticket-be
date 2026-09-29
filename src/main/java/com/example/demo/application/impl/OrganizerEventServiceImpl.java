package com.example.demo.application.impl;

import com.example.demo.application.OrganizerAccess;
import com.example.demo.application.OrganizerEventQueries;
import com.example.demo.application.OrganizerEventService;
import com.example.demo.application.dto.EventUpsertRequest;
import com.example.demo.application.dto.EventUpsertRequest.TierInput;
import com.example.demo.application.dto.OrganizerEventDetail;
import com.example.demo.domain.common.DomainException;
import com.example.demo.domain.common.DomainException.FieldError;
import com.example.demo.domain.common.Slugs;
import com.example.demo.domain.event.Event;
import com.example.demo.domain.event.EventStatus;
import com.example.demo.domain.event.ScheduleItem;
import com.example.demo.domain.event.TicketTier;
import com.example.demo.domain.event.Venue;
import com.example.demo.domain.inventory.Inventory;
import com.example.demo.infrastructure.persistence.EventRepository;
import com.example.demo.infrastructure.persistence.InventoryRepository;
import com.example.demo.infrastructure.persistence.TicketTierRepository;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import static com.example.demo.application.support.Texts.blankToNull;

/**
 * Phần GHI của sự kiện phía BTC (ui-api-contract §4.4): tạo nháp, sửa, publish, xóa nháp, đồng bộ hạng vé.
 * Controller: OrganizerEventController (POST/PUT/DELETE /organizer/events...). Phần đọc nằm ở OrganizerEventQueries.
 * Không có hồ sơ BTC → 404 ORGANIZER_NOT_FOUND (ADMIN cũng vậy); sự kiện của BTC khác → 404 EVENT_NOT_FOUND.
 * <p>
 * Thứ tự khóa để tránh deadlock: sửa sự kiện khóa dòng event trước, rồi inventory theo tier id tăng dần.
 * Checkout chỉ khóa inventory (cũng theo id tăng dần) nên hai bên không bao giờ chờ nhau thành vòng.
 */
@Service
@Transactional
public class OrganizerEventServiceImpl implements OrganizerEventService {

    /** Số vé tối đa mỗi đơn khi BTC không nhập. */
    private static final int DEFAULT_MAX_PER_ORDER = 6;

    private final EventRepository events;
    private final TicketTierRepository tiers;
    private final InventoryRepository inventory;
    private final OrganizerAccess access;
    private final OrganizerEventQueries queries;
    private final JdbcClient jdbc;

    public OrganizerEventServiceImpl(EventRepository events, TicketTierRepository tiers, InventoryRepository inventory,
                                 OrganizerAccess access, OrganizerEventQueries queries, JdbcClient jdbc) {
        this.events = events;
        this.tiers = tiers;
        this.inventory = inventory;
        this.access = access;
        this.queries = queries;
        this.jdbc = jdbc;
    }

    /** POST /organizer/events: tạo nháp. Chỉ bắt buộc name; field có mặt thì phải hợp lệ. */
    @Override
    public OrganizerEventDetail create(UUID userId, EventUpsertRequest req) {
        UUID organizerId = access.currentOrganizer(userId).getId();
        checkDates(req);
        String slug = Slugs.unique(Event.slugify(req.name()), events::existsBySlug);
        Event e = events.save(apply(Event.draft(organizerId, slug), req));
        if (req.tiers() != null) {
            for (TierInput t : req.tiers()) addTier(e.getId(), t);
        }
        events.flush();
        return queries.detail(e);
    }

    /**
     * PUT /organizer/events/{id}. DRAFT sửa tự do. Đã publish (chưa kết thúc): thêm tier, đổi tổng số vé không dưới
     * sold + reserved, đổi giá khi chưa có vé bán/giữ, không xóa tier đã có đơn; sau khi sửa vẫn phải đủ điều kiện publish.
     * ENDED/CANCELLED → 409.
     */
    @Override
    public OrganizerEventDetail update(UUID userId, UUID eventId, EventUpsertRequest req) {
        // Khóa event trước khi đọc tier: không khóa thì hai PUT song song cùng tính delta kho từ totalQuantity cũ → available > total
        Event e = access.ownEventLocked(userId, eventId);
        Instant now = Instant.now();
        e.ensureEditable(now);
        checkDates(req);
        apply(e, req);
        if (req.tiers() != null) syncTiers(e, req.tiers());
        if (e.getStatus() != EventStatus.DRAFT) {
            List<FieldError> problems = e.publishProblems(queries.tiersOf(e.getId()), now, false);
            if (!problems.isEmpty()) {
                throw DomainException.badRequest("EVENT_INCOMPLETE", "Sự kiện đang bán phải giữ đủ thông tin").withErrors(problems);
            }
        }
        events.flush();
        return queries.detail(e);
    }

    /** POST /organizer/events/{id}/publish: 400 EVENT_INCOMPLETE kèm errors[], 409 EVENT_NOT_DRAFT. */
    @Override
    public OrganizerEventDetail publish(UUID userId, UUID eventId) {
        Event e = access.ownEventLocked(userId, eventId);
        e.publish(queries.tiersOf(e.getId()), Instant.now());
        events.flush();
        return queries.detail(e);
    }

    /** DELETE /organizer/events/{id}: chỉ nháp chưa có đơn. Xóa theo thứ tự khóa ngoại: inventory → tiers → event. */
    @Override
    public void delete(UUID userId, UUID eventId) {
        Event e = access.ownEvent(userId, eventId);
        boolean hasOrders = jdbc.sql("select exists(select 1 from orders where event_id = :id)")
                .param("id", e.getId()).query(Boolean.class).single();
        if (e.getStatus() != EventStatus.DRAFT || hasOrders) {
            throw DomainException.conflict("EVENT_NOT_DELETABLE", "Chỉ xóa được sự kiện nháp chưa có đơn hàng");
        }
        List<TicketTier> eventTiers = queries.tiersOf(e.getId());
        inventory.deleteAllById(eventTiers.stream().map(TicketTier::getId).toList());
        inventory.flush();
        tiers.deleteAll(eventTiers);
        tiers.flush();
        events.delete(e);
    }

    /* ---------- hạng vé ---------- */

    private void addTier(UUID eventId, TierInput t) {
        int total = t.totalQuantity() == null ? 0 : t.totalQuantity();
        int maxPerOrder = t.maxPerOrder() == null ? DEFAULT_MAX_PER_ORDER : t.maxPerOrder();
        TicketTier tier = tiers.save(new TicketTier(eventId, t.name().trim(), t.description(),
                t.price() == null ? 0 : t.price(), total, maxPerOrder));
        inventory.save(new Inventory(tier.getId(), total));   // kho mới = toàn bộ số vé
    }

    /**
     * Đồng bộ tập tier đầy đủ FE gửi lên: tier có id = sửa, không có id = thêm, tier cũ vắng mặt = xóa.
     * Khóa inventory mọi tier cũ theo id tăng dần (cùng thứ tự với checkout, tránh deadlock);
     * dưới khóa, số vé đã bán + đang giữ = totalQuantity − available.
     */
    private void syncTiers(Event e, List<TierInput> inputs) {
        Map<UUID, TicketTier> existing = queries.tiersOf(e.getId()).stream()
                .collect(Collectors.toMap(TicketTier::getId, Function.identity()));
        List<FieldError> unknown = new ArrayList<>();
        for (int i = 0; i < inputs.size(); i++) {
            UUID tierId = inputs.get(i).id();
            if (tierId != null && !existing.containsKey(tierId)) {
                unknown.add(new FieldError("tiers[" + i + "].id", "Hạng vé không thuộc sự kiện này"));
            }
        }
        if (!unknown.isEmpty()) throw DomainException.badRequest("VALIDATION", "Hạng vé không hợp lệ").withErrors(unknown);

        Map<UUID, Inventory> locked = new HashMap<>();
        existing.keySet().stream().sorted().forEach(tierId -> locked.put(tierId, inventory.findWithLockByTicketTierId(tierId)
                .orElseThrow(() -> new IllegalStateException("Thiếu inventory cho tier " + tierId))));

        // 1. Xóa tier không còn trong danh sách (chỉ khi chưa bán/giữ vé nào và chưa có đơn)
        Set<UUID> kept = inputs.stream().map(TierInput::id).filter(Objects::nonNull).collect(Collectors.toSet());
        for (TicketTier old : existing.values()) {
            if (kept.contains(old.getId())) continue;
            Inventory inv = locked.get(old.getId());
            boolean hasItems = jdbc.sql("select exists(select 1 from order_items where ticket_tier_id = :id)")
                    .param("id", old.getId()).query(Boolean.class).single();
            if (old.getTotalQuantity() - inv.getAvailable() > 0 || hasItems) {
                throw DomainException.conflict("TIER_HAS_SALES", "Hạng vé \"" + old.getName() + "\" đã có đơn hàng, không xóa được");
            }
            inventory.delete(inv);
            inventory.flush();
            tiers.delete(old);
        }
        // 2. Thêm tier mới, sửa tier cũ (TicketTier.update tự chặn giảm dưới số đã bán/giữ và đổi giá khi đã có vé)
        for (TierInput t : inputs) {
            if (t.id() == null) {
                addTier(e.getId(), t);
                continue;
            }
            TicketTier tier = existing.get(t.id());
            Inventory inv = locked.get(t.id());
            int held = tier.getTotalQuantity() - inv.getAvailable();
            int total = t.totalQuantity() == null ? 0 : t.totalQuantity();
            inv.adjust(total - tier.getTotalQuantity());
            tier.update(t.name().trim(), t.description(), t.price() == null ? 0 : t.price(), total,
                    t.maxPerOrder() == null ? tier.getMaxPerOrder() : t.maxPerOrder(), held);
        }
    }

    /* ---------- tiện ích ---------- */

    /** Ghi đè nội dung sự kiện từ request (PUT = thay toàn bộ); chuỗi rỗng thành null, mô tả bỏ đoạn trống. */
    private static Event apply(Event e, EventUpsertRequest r) {
        Venue venue = r.venue() == null ? null
                : new Venue(blankToNull(r.venue().name()), blankToNull(r.venue().city()), blankToNull(r.venue().address()));
        List<ScheduleItem> schedule = r.schedule() == null ? null
                : r.schedule().stream().map(s -> new ScheduleItem(s.time(), s.title().trim())).toList();
        List<String> description = r.description() == null ? null
                : r.description().stream().filter(p -> p != null && !p.isBlank()).map(String::trim).toList();
        e.updateDetails(r.name().trim(), blankToNull(r.category()), blankToNull(r.tagline()), description,
                blankToNull(r.coverImageUrl()), blankToNull(r.coverImageAlt()), r.startsAt(), r.endsAt(), venue, schedule);
        return e;
    }

    /** Field có mặt thì phải hợp lệ, kể cả khi lưu nháp. */
    private static void checkDates(EventUpsertRequest r) {
        if (r.startsAt() != null && r.endsAt() != null && !r.endsAt().isAfter(r.startsAt())) {
            throw DomainException.invalid("endsAt", "Thời gian kết thúc phải sau thời gian bắt đầu");
        }
    }
}
