package com.jobcopilot;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import java.sql.DriverManager;
import static org.junit.jupiter.api.Assertions.*;

@Testcontainers
class SchemaMigrationIntegrationTest {
    @Container static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.11-alpine");

    private Flyway flyway(String schema, String target) {
        var config = Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .schemas(schema).defaultSchema(schema).cleanDisabled(true).baselineOnMigrate(false);
        if (target != null) config.target(target);
        return config.load();
    }

    @Test void cleanDatabaseMigratesAndCanBeValidatedRepeatedly() {
        var flyway = flyway("fresh", null);
        assertTrue(flyway.migrate().migrationsExecuted > 0);
        flyway.validate();
        assertEquals(0, flyway.migrate().migrationsExecuted);
    }

    @Test void populatedLegacySchemaRequiresExplicitBaselineAndPreservesRows() throws Exception {
        flyway("legacy", "1").migrate();
        try (var connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             var statement = connection.createStatement()) {
            statement.execute("INSERT INTO legacy.jobs(title, company, created_at, updated_at) VALUES ('Existing', 'Company', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)");
            // Only the isolated test schema loses its history, simulating pre-Flyway Hibernate DDL.
            statement.execute("DROP TABLE legacy.flyway_schema_history");
        }
        var adopter = flyway("legacy", null);
        assertThrows(Exception.class, adopter::migrate);
        adopter.baseline();
        adopter.migrate();
        adopter.validate();
        try (var connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             var statement = connection.createStatement();
             var rows = statement.executeQuery("SELECT id, title, status FROM legacy.jobs")) {
            assertTrue(rows.next());
            assertEquals(1, rows.getLong("id"));
            assertEquals("Existing", rows.getString("title"));
            assertEquals("DISCOVERED", rows.getString("status"));
            assertFalse(rows.next());
        }
    }

    @Test void currentV3SchemaUpgradesToDiscoveryDomainWithoutChangingExistingJobs() throws Exception {
        flyway("upgrade_v3", "3").migrate();
        try (var connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             var statement = connection.createStatement()) {
            statement.execute("""
                    INSERT INTO upgrade_v3.jobs(title, company, status, created_at, updated_at)
                    VALUES ('Existing V3 Job', 'Company', 'APPLIED', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                    """);
        }

        var upgraded = flyway("upgrade_v3", null);
        assertEquals(1, upgraded.migrate().migrationsExecuted);
        upgraded.validate();

        try (var connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             var statement = connection.createStatement()) {
            try (var rows = statement.executeQuery("SELECT title, status FROM upgrade_v3.jobs")) {
                assertTrue(rows.next());
                assertEquals("Existing V3 Job", rows.getString("title"));
                assertEquals("APPLIED", rows.getString("status"));
                assertFalse(rows.next());
            }
            for (String table : new String[]{"job_sources", "external_job_listings", "job_source_sync_runs"}) {
                try (var rows = statement.executeQuery("""
                        SELECT COUNT(*) FROM information_schema.tables
                        WHERE table_schema = 'upgrade_v3' AND table_name = '%s'
                        """.formatted(table))) {
                    assertTrue(rows.next());
                    assertEquals(1, rows.getInt(1));
                }
            }
        }
    }
}
