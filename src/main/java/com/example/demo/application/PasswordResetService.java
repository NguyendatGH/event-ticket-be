package com.example.demo.application;

import com.example.demo.application.dto.ForgotPasswordResponse;

public interface PasswordResetService {

    ForgotPasswordResponse forgotPassword(String email);

    void resetPassword(String rawToken, String newPassword);
}
