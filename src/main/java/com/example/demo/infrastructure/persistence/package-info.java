/**
 * Repository Spring Data JPA, mỗi entity một interface. Tên method sinh query tự động
 * (vd {@code findAllByEventIdIn}); method {@code findWithLockBy...} có @Lock = SELECT ... FOR UPDATE.
 * Chỉ service gọi repository. Schema do Flyway quản lý (resources/db/migration), Hibernate chỉ đối chiếu (validate).
 */
package com.example.demo.infrastructure.persistence;
