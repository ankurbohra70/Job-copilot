package com.jobcopilot.discovery;

import java.time.LocalDateTime;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
class DiscoveryDatabaseTime {
    private final JdbcTemplate jdbc;

    DiscoveryDatabaseTime(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    LocalDateTime now() {
        return jdbc.queryForObject("select clock_timestamp()::timestamp", LocalDateTime.class);
    }
}
