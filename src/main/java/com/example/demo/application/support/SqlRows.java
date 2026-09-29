package com.example.demo.application.support;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;

/** Đọc giá trị từ ResultSet khi query bằng JdbcClient (không qua entity). */
public final class SqlRows {

    private SqlRows() {}

    /** Cột timestamptz → Instant; NULL → null. */
    public static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime t = rs.getObject(column, OffsetDateTime.class);
        return t == null ? null : t.toInstant();
    }
}
