/**
 * Adapter cổng thanh toán, cài đặt interface domain/payment/PaymentGatewayPort: payos (gọi PayOS thật).
 * Đây là adapter duy nhất khi chạy app; test dùng MockPaymentGateway ở src/test/java/com/example/demo/support.
 * Service chỉ biết PaymentGatewayPort nên đổi cổng không phải sửa nghiệp vụ.
 */
package com.example.demo.infrastructure.gateway;
