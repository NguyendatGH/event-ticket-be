/**
 * Tầng nghiệp vụ. Interface {@code Xxx} ở đây, impl ở {@code application.impl}; nơi dùng inject interface.
 * Tính năng lớn tách đọc/ghi: {@code XxxQueries} chỉ đọc, {@code XxxService} thay đổi dữ liệu.
 * Controller truyền {@code UUID userId} vào; service không tự đọc SecurityContext.
 * Query động/join nhiều bảng thì dùng JdbcClient + {@link com.example.demo.application.support.SqlWhere} thay JPA.
 * Chiều phụ thuộc: web → application → domain + persistence.
 */
package com.example.demo.application;
