/**
 * Tầng nghiệp vụ (service). Mỗi tính năng một service, tách làm hai phần:
 * <ul>
 *   <li>Interface {@code Xxx} ở package này (vd UserService): khai báo các method mà controller và service khác gọi.
 *       Nơi dùng luôn inject interface, không inject class impl.</li>
 *   <li>Class {@code XxxImpl} ở package {@code application.impl} (vd UserServiceImpl): {@code @Service}/{@code @Component},
 *       chứa logic, {@code @Transactional}, các field repository.</li>
 *   <li>Controller đọc user hiện tại (CurrentUser) rồi truyền {@code UUID userId} vào service;
 *       service không tự đọc SecurityContext.</li>
 *   <li>Tính năng lớn tách ĐỌC / GHI: {@code XxxQueries} chỉ đọc (vd OrderQueries, OrganizerEventQueries),
 *       {@code XxxService} thay đổi dữ liệu (vd CheckoutService, OrganizerEventService).</li>
 *   <li>Đọc/ghi entity đơn giản dùng repository (Spring Data JPA). Danh sách cần lọc động, join nhiều bảng hoặc
 *       count/sum thì viết SQL bằng JdbcClient, ghép WHERE bằng {@link com.example.demo.application.support.SqlWhere}.</li>
 *   <li>Lỗi nghiệp vụ: ném {@link com.example.demo.domain.common.DomainException} (mã lỗi + HTTP status),
 *       GlobalExceptionHandler đổi thành JSON problem+json.</li>
 * </ul>
 * Chiều phụ thuộc: infrastructure/web (controller) → application (service) → domain + infrastructure/persistence.
 */
package com.example.demo.application;
