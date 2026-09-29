package com.example.demo.application.impl;

import com.example.demo.application.UserService;
import com.example.demo.application.dto.ChangePasswordRequest;
import com.example.demo.application.dto.UpdateProfileRequest;
import com.example.demo.application.dto.UserResponse;
import com.example.demo.domain.common.DomainException;
import com.example.demo.domain.user.User;
import com.example.demo.infrastructure.persistence.OrganizerRepository;
import com.example.demo.infrastructure.persistence.UserRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

import static com.example.demo.application.support.Texts.blankToNull;

@Service
public class UserServiceImpl implements UserService {

    private final UserRepository users;
    private final OrganizerRepository organizers;
    private final PasswordEncoder passwordEncoder;

    public UserServiceImpl(UserRepository users, OrganizerRepository organizers, PasswordEncoder passwordEncoder) {
        this.users = users;
        this.organizers = organizers;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    @Transactional(readOnly = true)
    public List<UserResponse> findAll() {
        return users.findAllByOrderByCreatedAtAsc().stream()
                .map(UserResponse::from)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public UserResponse me(UUID userId) {
        return toResponse(requireUser(userId));
    }

    @Override
    @Transactional
    public UserResponse updateProfile(UUID userId, UpdateProfileRequest req) {
        User user = requireUser(userId);
        user.updateProfile(req.fullName().trim(), blankToNull(req.phone()), blankToNull(req.bio()), blankToNull(req.avatarUrl()));
        return toResponse(user);
    }

    /** 400 WRONG_PASSWORD (errors field currentPassword) nếu mật khẩu hiện tại sai. Các phiên khác vẫn giữ. */
    @Override
    @Transactional
    public void changePassword(UUID userId, ChangePasswordRequest req) {
        User user = requireUser(userId);
        if (!passwordEncoder.matches(req.currentPassword(), user.getPasswordHash())) {
            throw DomainException.badRequest("WRONG_PASSWORD", "Mật khẩu hiện tại không đúng")
                    .withError("currentPassword", "Mật khẩu hiện tại không đúng");
        }
        user.changePasswordHash(passwordEncoder.encode(req.newPassword()));
    }

    /** JWT còn hạn nhưng user đã bị xóa → 401 để FE đăng xuất. */
    @Override
    public User requireUser(UUID userId) {
        return users.findById(userId)
                .orElseThrow(() -> DomainException.unauthorized("UNAUTHORIZED", "Tài khoản không còn tồn tại"));
    }

    @Override
    public UserResponse toResponse(User user) {
        return UserResponse.from(user, organizers.findByUserId(user.getId()).orElse(null));
    }
}
