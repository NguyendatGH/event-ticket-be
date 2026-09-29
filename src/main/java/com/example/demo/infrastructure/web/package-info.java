/**
 * Controller REST và xử lý lỗi chung. Controller chỉ nhận tham số/body (@Valid), lấy CurrentUser, gọi MỘT service.
 * Không chứa nghiệp vụ, không gọi repository. Quyền theo URL ở config/SecurityConfig.
 */
package com.example.demo.infrastructure.web;
