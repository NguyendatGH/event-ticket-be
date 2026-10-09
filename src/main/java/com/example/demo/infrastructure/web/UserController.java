package com.example.demo.infrastructure.web;

import com.example.demo.application.UserService;
import com.example.demo.application.dto.ChangePasswordRequest;
import com.example.demo.application.dto.UpdateProfileRequest;
import com.example.demo.application.dto.UserResponse;
import com.example.demo.infrastructure.security.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/users")
@Tag(name = "Users", description = "Hồ sơ cá nhân, quản lý user")
public class UserController {

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Lấy toàn bộ user", description = "Chỉ ADMIN. Trả về danh sách user sắp xếp theo thời điểm tạo")
    public List<UserResponse> getAll() {
        return userService.findAll();
    }

    @GetMapping("/me")
    @Operation(summary = "Hồ sơ của tôi")
    public UserResponse me() {
        return userService.me(CurrentUser.require());
    }

    @PutMapping("/me")
    @Operation(summary = "Sửa hồ sơ của tôi", description = "Họ tên, số điện thoại, giới thiệu, ảnh đại diện (URL từ POST /uploads/images)")
    public UserResponse updateMe(@Valid @RequestBody UpdateProfileRequest req) {
        return userService.updateProfile(CurrentUser.require(), req);
    }

    @PutMapping("/me/password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Đổi mật khẩu", description = "400 WRONG_PASSWORD nếu mật khẩu hiện tại sai")
    public void changePassword(@Valid @RequestBody ChangePasswordRequest req) {
        userService.changePassword(CurrentUser.require(), req);
    }
}
