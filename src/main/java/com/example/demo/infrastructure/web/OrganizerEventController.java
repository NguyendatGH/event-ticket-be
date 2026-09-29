package com.example.demo.infrastructure.web;

import com.example.demo.application.OrganizerEventQueries;
import com.example.demo.application.OrganizerEventService;
import com.example.demo.application.dto.EventUpsertRequest;
import com.example.demo.application.dto.OrganizerEventDetail;
import com.example.demo.application.dto.OrganizerEventSummary;
import com.example.demo.application.dto.OrganizerOrderRow;
import com.example.demo.application.dto.PageResponse;
import com.example.demo.infrastructure.security.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * /api/v1/organizer/events: BTC quản lý sự kiện của mình. Đọc → OrganizerEventQueries, ghi → OrganizerEventService.
 * Quyền ORGANIZER/ADMIN do SecurityConfig chặn ở /api/v1/organizer/**; "sự kiện có phải của mình không" kiểm tra trong service.
 */
@RestController
@RequestMapping("/api/v1/organizer/events")
@Tag(name = "Organizer events", description = "BTC tạo, sửa, publish, xóa nháp sự kiện và xem đơn")
public class OrganizerEventController {

    private final OrganizerEventQueries queries;
    private final OrganizerEventService service;

    public OrganizerEventController(OrganizerEventQueries queries, OrganizerEventService service) {
        this.queries = queries;
        this.service = service;
    }

    @GetMapping
    @Operation(summary = "Sự kiện của tôi", description = "status = DRAFT|PUBLISHED|UPCOMING|CANCELLED|ENDED, q khớp tên")
    public PageResponse<OrganizerEventSummary> list(@RequestParam(required = false) String status,
                                                    @RequestParam(required = false) String q,
                                                    @RequestParam(defaultValue = "0") int page,
                                                    @RequestParam(defaultValue = "12") int size) {
        return queries.list(CurrentUser.require(), status, q, page, size);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Tạo sự kiện nháp", description = "Chỉ bắt buộc name; field có mặt thì phải hợp lệ")
    public OrganizerEventDetail create(@Valid @RequestBody EventUpsertRequest req) {
        return service.create(CurrentUser.require(), req);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Chi tiết sự kiện kèm số liệu vé/doanh thu theo hạng vé")
    public OrganizerEventDetail get(@PathVariable UUID id) {
        return queries.get(CurrentUser.require(), id);
    }

    @PutMapping("/{id}")
    @Operation(summary = "Sửa sự kiện", description = "tiers là tập đầy đủ. Sau publish: 409 TIER_HAS_SALES, TIER_QUANTITY_BELOW_SOLD, TIER_PRICE_LOCKED")
    public OrganizerEventDetail update(@PathVariable UUID id, @Valid @RequestBody EventUpsertRequest req) {
        return service.update(CurrentUser.require(), id, req);
    }

    @PostMapping("/{id}/publish")
    @Operation(summary = "Publish sự kiện nháp", description = "400 EVENT_INCOMPLETE kèm errors[], 409 EVENT_NOT_DRAFT")
    public OrganizerEventDetail publish(@PathVariable UUID id) {
        return service.publish(CurrentUser.require(), id);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Xóa sự kiện nháp chưa có đơn")
    public void delete(@PathVariable UUID id) {
        service.delete(CurrentUser.require(), id);
    }

    @GetMapping("/{id}/orders")
    @Operation(summary = "Đơn hàng của sự kiện", description = "q khớp mã đơn, email hoặc tên khách")
    public PageResponse<OrganizerOrderRow> orders(@PathVariable UUID id,
                                                  @RequestParam(required = false) String status,
                                                  @RequestParam(required = false) String q,
                                                  @RequestParam(defaultValue = "0") int page,
                                                  @RequestParam(defaultValue = "12") int size) {
        return queries.orders(CurrentUser.require(), id, status, q, page, size);
    }
}
