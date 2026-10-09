package com.example.demo.infrastructure.seed;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;

@Component
@Profile("dev")
public class DevDataSeeder implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(DevDataSeeder.class);

    private final JdbcTemplate jdbc;
    private final boolean seedOnStartup;

    public DevDataSeeder(JdbcTemplate jdbc, @Value("${app.seed.on-startup:false}") boolean seedOnStartup) {
        this.jdbc = jdbc;
        this.seedOnStartup = seedOnStartup;
    }

    @Override
    @Transactional
    public void run(String... args) {
        if (!seedOnStartup) {
            log.info("app.seed.on-startup=false: không seed lúc khởi động, dùng db/seed-dev.sql hoặc POST /dev/seed");
            return;
        }
        if (count("events") == 0) runScript();
    }

    @Transactional
    public Map<String, Object> reseed() {
        jdbc.execute("truncate table events, idempotency_records cascade");
        runScript();
        return Map.of("users", count("users"), "organizers", count("organizers"), "events", count("events"),
                "orders", count("orders"), "tickets", count("tickets"));
    }

    private void runScript() {
        String sql;
        try {
            sql = new ClassPathResource("seed/seed-dev.sql").getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        jdbc.execute(sql);
    }

    private long count(String table) {
        return jdbc.queryForObject("select count(*) from " + table, Long.class);
    }
}
