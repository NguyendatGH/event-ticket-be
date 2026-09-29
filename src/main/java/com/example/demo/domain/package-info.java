/**
 * Entity JPA, enum trạng thái và quy tắc nghiệp vụ thuần (vd Inventory.reserve không cho âm kho).
 * Không phụ thuộc application/infrastructure nên test được bằng unit test thường (không Spring, không DB).
 * domain/common: thứ dùng chung (DomainException, VietnamTime, Slugs).
 */
package com.example.demo.domain;
