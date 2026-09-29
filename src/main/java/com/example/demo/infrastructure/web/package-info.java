/**
 * Controller REST (/api/v1/**, /webhooks/**) và xử lý lỗi chung. Controller chỉ làm 3 việc: nhận tham số/body (@Valid),
 * lấy user hiện tại (CurrentUser), gọi MỘT service và trả kết quả. Không chứa nghiệp vụ, không gọi repository.
 * Quyền truy cập theo URL cấu hình ở config/SecurityConfig; lỗi được GlobalExceptionHandler đổi thành problem+json.
 */
package com.example.demo.infrastructure.web;
