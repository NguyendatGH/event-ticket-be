package com.example.demo.application;

import com.example.demo.application.dto.ChangePasswordRequest;
import com.example.demo.application.dto.UpdateProfileRequest;
import com.example.demo.application.dto.UserResponse;
import com.example.demo.domain.user.User;

import java.util.List;
import java.util.UUID;

public interface UserService {

    List<UserResponse> findAll();

    UserResponse me(UUID userId);

    UserResponse updateProfile(UUID userId, UpdateProfileRequest req);

    void changePassword(UUID userId, ChangePasswordRequest req);

    User requireUser(UUID userId);

    UserResponse toResponse(User user);
}
