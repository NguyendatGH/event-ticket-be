/**
 * Repository Spring Data JPA, mỗi entity một interface; {@code findWithLockBy...} có @Lock = SELECT ... FOR UPDATE.
 * Chỉ service gọi repository. Schema do Flyway quản lý, Hibernate chỉ validate.
 */
package com.example.demo.infrastructure.persistence;
