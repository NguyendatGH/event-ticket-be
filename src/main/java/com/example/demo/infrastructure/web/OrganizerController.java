package com.example.demo.infrastructure.web;

import com.example.demo.application.AuthService;
import com.example.demo.application.OrganizerService;
import com.example.demo.application.dto.AuthResponse;
import com.example.demo.application.dto.OrganizerProfileRequest;
import com.example.demo.application.dto.OrganizerResponse;
import com.example.demo.infrastructure.security.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Hồ sơ ban tổ chức: nâng cấp tài khoản (POST /me/organizer → AuthService, vì phải phát JWT mới mang role mới),
 * xem/sửa hồ sơ của mình, trang public và danh sách BTC nổi bật cho trang chủ (→ OrganizerService). Quyền theo SecurityConfig (ui-api-contract §4.7).
 */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "Organizers", description = "Hồ sơ ban tổ chức")
public class OrganizerController {

    private final OrganizerService organizerService;
    private final AuthService authService;

    public OrganizerController(OrganizerService organizerService, AuthService authService) {
        this.organizerService = organizerService;
        this.authService = authService;
    }

    @PostMapping("/me/organizer")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Trở thành ban tổ chức", description = "CUSTOMER → ORGANIZER + tạo hồ sơ; trả phiên mới (JWT mang role mới)")
    public AuthResponse becomeOrganizer(@Valid @RequestBody OrganizerProfileRequest req) {
        return authService.becomeOrganizer(CurrentUser.require(), req);
    }

    @GetMapping("/organizer/profile")
    @Operation(summary = "Hồ sơ BTC của tôi", description = "404 ORGANIZER_NOT_FOUND nếu tài khoản (vd ADMIN) chưa có hồ sơ")
    public OrganizerResponse myProfile() {
        return organizerService.mine(CurrentUser.require());
    }

    @PutMapping("/organizer/profile")
    @Operation(summary = "Sửa hồ sơ BTC của tôi", description = "Slug giữ nguyên")
    public OrganizerResponse updateMyProfile(@Valid @RequestBody OrganizerProfileRequest req) {
        return organizerService.updateMine(CurrentUser.require(), req);
    }

    @GetMapping("/organizers")
    @SecurityRequirements
    @Operation(summary = "BTC nổi bật (trang chủ)",
            description = "Đã xác minh trước, rồi nhiều sự kiện sắp diễn ra hơn, rồi theo tên. Bỏ BTC không còn sự kiện chưa kết thúc. size mặc định 12, tối đa 24")
    public List<OrganizerResponse> featured(@RequestParam(defaultValue = "12") int size) {
        return organizerService.featured(size);
    }

    @GetMapping("/organizers/{idOrSlug}")
    @SecurityRequirements
    @Operation(summary = "Trang public của BTC", description = "Tìm theo slug hoặc id")
    public OrganizerResponse publicProfile(@PathVariable String idOrSlug) {
        return organizerService.publicProfile(idOrSlug);
    }
}
