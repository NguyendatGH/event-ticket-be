/**
 * Mô hình nghiệp vụ: entity JPA (Event, Order, Ticket...), enum trạng thái, và các quy tắc thuần
 * (vd Event.publish kiểm tra đủ thông tin, Inventory.reserve không cho âm kho). Mỗi thư mục con là một nhóm nghiệp vụ.
 * Domain không phụ thuộc application hay infrastructure, nên test được bằng unit test thường (không Spring, không DB).
 * domain/common: thứ dùng chung (DomainException, VietnamTime, Slugs).
 */
package com.example.demo.domain;
