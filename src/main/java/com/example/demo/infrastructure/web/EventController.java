package com.example.demo.infrastructure.web;

import com.example.demo.application.EventService;
import com.example.demo.application.dto.EventFacets;
import com.example.demo.application.dto.EventResponse;
import com.example.demo.application.dto.PageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/v1")
@Tag(name = "Events", description = "Danh sách và chi tiết sự kiện, công khai")
@SecurityRequirements
public class EventController {

    private final EventService eventService;

    public EventController(EventService eventService) {
        this.eventService = eventService;
    }

    @GetMapping("/events")
    @Operation(summary = "Danh sách sự kiện",
            description = "PUBLISHED/UPCOMING chưa kết thúc (includePast=true để lấy cả đã qua). q khớp tên sự kiện, tagline, "
                    + "tên BTC, tên địa điểm. from/to là ngày giờ VN gồm cả hai đầu; when = today|weekend|week|month. "
                    + "priceMin/priceMax so với giá vé thấp nhất. sort = date (mặc định)|-date|price|-price|newest|popular")
    public PageResponse<EventResponse> list(@RequestParam(required = false) String q,
                                            @RequestParam(required = false) String category,
                                            @RequestParam(required = false) String city,
                                            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                            @RequestParam(required = false) String when,
                                            @RequestParam(required = false) Long priceMin,
                                            @RequestParam(required = false) Long priceMax,
                                            @RequestParam(required = false) String organizer,
                                            @RequestParam(required = false) String sort,
                                            @RequestParam(defaultValue = "false") boolean includePast,
                                            @RequestParam(defaultValue = "0") int page,
                                            @RequestParam(defaultValue = "12") int size) {
        return eventService.list(new EventService.Filter(q, category, city, from, to, when, priceMin, priceMax, organizer, includePast),
                sort, page, size);
    }

    @GetMapping("/events/featured")
    @Operation(summary = "Sự kiện nổi bật")
    public List<EventResponse> featured() {
        return eventService.featured();
    }

    @GetMapping("/events/upcoming")
    @Operation(summary = "Sự kiện sắp diễn ra gần nhất", description = "limit mặc định 8, tối đa 24")
    public List<EventResponse> upcoming(@RequestParam(defaultValue = "8") int limit) {
        return eventService.upcoming(limit);
    }

    @GetMapping("/events/facets")
    @Operation(summary = "Danh mục, thành phố, khoảng giá của các sự kiện đang liệt kê")
    public EventFacets facets() {
        return eventService.facets();
    }

    @GetMapping("/events/{idOrSlug}")
    @Operation(summary = "Chi tiết sự kiện", description = "Nhận UUID hoặc slug. Có tiers kèm số vé còn lại. DRAFT → 404")
    public EventResponse get(@PathVariable String idOrSlug) {
        return eventService.get(idOrSlug);
    }

    @GetMapping("/events/{idOrSlug}/related")
    @Operation(summary = "Sự kiện cùng danh mục")
    public List<EventResponse> related(@PathVariable String idOrSlug, @RequestParam(defaultValue = "4") int limit) {
        return eventService.related(idOrSlug, limit);
    }

    @GetMapping("/events/{idOrSlug}/more-from-organizer")
    @Operation(summary = "Sự kiện khác của cùng BTC")
    public List<EventResponse> moreFromOrganizer(@PathVariable String idOrSlug, @RequestParam(defaultValue = "4") int limit) {
        return eventService.moreFromOrganizer(idOrSlug, limit);
    }

    @GetMapping("/organizers/{idOrSlug}/events")
    @Operation(summary = "Sự kiện của một BTC", description = "scope = upcoming (mặc định) | past")
    public PageResponse<EventResponse> byOrganizer(@PathVariable String idOrSlug,
                                                   @RequestParam(defaultValue = "upcoming") String scope,
                                                   @RequestParam(defaultValue = "0") int page,
                                                   @RequestParam(defaultValue = "12") int size) {
        return eventService.byOrganizer(idOrSlug, scope, page, size);
    }
}
