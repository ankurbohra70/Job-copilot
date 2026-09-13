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
        assertEquals(3, upgraded.migrate().migrationsExecuted);
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

    @Test void currentV4SchemaUpgradesWithNullableValidatedExtractionFingerprint() throws Exception {
        flyway("upgrade_v4", "4").migrate();
        try (var connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             var statement = connection.createStatement()) {
            statement.execute("""
                    INSERT INTO upgrade_v4.jobs(id, title, company, status, created_at, updated_at)
                    VALUES (100, 'Existing', 'Company', 'DISCOVERED', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                    """);
            statement.execute("""
                    INSERT INTO upgrade_v4.job_sources(id, provider, region, source_key, company_name, enabled, created_at, updated_at)
                    VALUES (100, 'LEVER', 'GLOBAL', 'existing', 'Company', true, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                    """);
            statement.execute("""
                    INSERT INTO upgrade_v4.external_job_listings(
                        id, job_source_id, job_id, external_job_id, availability, provider_content_digest,
                        first_seen_at, last_seen_at, last_verified_at)
                    VALUES (100, 100, 100, 'posting', 'LIVE', 'digest', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                    """);
        }

        var upgraded = flyway("upgrade_v4", null);
        assertEquals(2, upgraded.migrate().migrationsExecuted);
        upgraded.validate();
        try (var connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             var statement = connection.createStatement()) {
            try (var rows = statement.executeQuery(
                    "SELECT extraction_fingerprint FROM upgrade_v4.external_job_listings WHERE id = 100")) {
                assertTrue(rows.next());
                assertNull(rows.getString(1));
            }
            statement.execute("""
                    UPDATE upgrade_v4.external_job_listings
                    SET extraction_fingerprint = 'jc005-v1:sha256:abc' WHERE id = 100
                    """);
            assertThrows(Exception.class, () -> statement.execute("""
                    UPDATE upgrade_v4.external_job_listings SET extraction_fingerprint = ' ' WHERE id = 100
                    """));
            assertThrows(Exception.class, () -> statement.execute("""
                    UPDATE upgrade_v4.external_job_listings SET extraction_fingerprint = E'\t' WHERE id = 100
                    """));
        }
    }

    @Test void populatedV5SchemaAddsLeaseAndMakesExistingRunningRowsImmediatelyRecoverable() throws Exception {
        flyway("upgrade_v5", "5").migrate();
        try (var connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             var statement = connection.createStatement()) {
            statement.execute("""
                    INSERT INTO upgrade_v5.job_sources(
                        id, provider, region, source_key, company_name, enabled, created_at, updated_at)
                    VALUES (100, 'LEVER', 'GLOBAL', 'running', 'Running Company', true,
                            CURRENT_TIMESTAMP - INTERVAL '1 hour', CURRENT_TIMESTAMP)
                    """);
            statement.execute("""
                    INSERT INTO upgrade_v5.jobs(
                        id, title, company, status, source, external_job_id, created_at, updated_at)
                    VALUES (100, 'Preserved Job', 'Terminal Company', 'APPLIED', 'LEVER', 'posting-100',
                            CURRENT_TIMESTAMP - INTERVAL '1 hour', CURRENT_TIMESTAMP)
                    """);
            statement.execute("""
                    INSERT INTO upgrade_v5.job_sources(
                        id, provider, region, source_key, company_name, enabled, created_at, updated_at)
                    VALUES (101, 'LEVER', 'GLOBAL', 'terminal', 'Terminal Company', true,
                            CURRENT_TIMESTAMP - INTERVAL '1 hour', CURRENT_TIMESTAMP)
                    """);
            statement.execute("""
                    INSERT INTO upgrade_v5.external_job_listings(
                        id, job_source_id, job_id, external_job_id, availability, provider_content_digest,
                        extraction_fingerprint, first_seen_at, last_seen_at, last_verified_at)
                    VALUES (100, 101, 100, 'posting-100', 'CLOSED', 'digest-100',
                            'jc005-v1:sha256:abc', CURRENT_TIMESTAMP - INTERVAL '1 hour',
                            CURRENT_TIMESTAMP - INTERVAL '30 minutes', CURRENT_TIMESTAMP)
                    """);
            statement.execute("""
                    INSERT INTO upgrade_v5.job_source_sync_runs(
                        id, job_source_id, trigger, status, started_at)
                    VALUES (100, 100, 'SCHEDULED', 'RUNNING', CURRENT_TIMESTAMP - INTERVAL '30 minutes')
                    """);
            statement.execute("""
                    INSERT INTO upgrade_v5.job_sources(
                        id, provider, region, source_key, company_name, enabled, created_at, updated_at)
                    VALUES (102, 'LEVER', 'GLOBAL', 'future-running', 'Future Running Company', true,
                            CURRENT_TIMESTAMP - INTERVAL '1 hour', CURRENT_TIMESTAMP)
                    """);
            statement.execute("""
                    INSERT INTO upgrade_v5.job_source_sync_runs(
                        id, job_source_id, trigger, status, started_at)
                    VALUES (102, 102, 'SCHEDULED', 'RUNNING', CURRENT_TIMESTAMP + INTERVAL '1 hour')
                    """);
            statement.execute("""
                    INSERT INTO upgrade_v5.job_source_sync_runs(
                        id, job_source_id, trigger, status, started_at, completed_at,
                        discovered_count, created_count, updated_count, unchanged_count,
                        closed_count, reopened_count, ranking_ready_count, unready_count)
                    VALUES (101, 101, 'MANUAL', 'SUCCEEDED', CURRENT_TIMESTAMP - INTERVAL '20 minutes',
                            CURRENT_TIMESTAMP - INTERVAL '10 minutes', 8, 2, 1, 4, 1, 0, 6, 2)
                    """);
        }

        var upgraded = flyway("upgrade_v5", null);
        assertEquals(1, upgraded.migrate().migrationsExecuted);
        upgraded.validate();

        try (var connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             var statement = connection.createStatement()) {
            try (var rows = statement.executeQuery("""
                    SELECT status, lease_expires_at <= clock_timestamp() AS immediately_recoverable,
                           lease_expires_at
                    FROM upgrade_v5.job_source_sync_runs ORDER BY id
                    """)) {
                assertTrue(rows.next());
                assertEquals("RUNNING", rows.getString("status"));
                assertTrue(rows.getBoolean("immediately_recoverable"));
                assertTrue(rows.next());
                assertEquals("SUCCEEDED", rows.getString("status"));
                assertNull(rows.getTimestamp("lease_expires_at"));
                assertTrue(rows.next());
                assertEquals("RUNNING", rows.getString("status"));
                assertTrue(rows.getBoolean("immediately_recoverable"));
                assertFalse(rows.next());
            }
            try (var rows = statement.executeQuery("""
                    SELECT job.status, listing.availability, listing.provider_content_digest,
                           listing.extraction_fingerprint, run.discovered_count, run.created_count,
                           run.updated_count, run.unchanged_count, run.closed_count, run.reopened_count,
                           run.ranking_ready_count, run.unready_count
                    FROM upgrade_v5.jobs job
                    JOIN upgrade_v5.external_job_listings listing ON listing.job_id = job.id
                    JOIN upgrade_v5.job_source_sync_runs run ON run.id = 101
                    WHERE job.id = 100
                    """)) {
                assertTrue(rows.next());
                assertEquals("APPLIED", rows.getString("status"));
                assertEquals("CLOSED", rows.getString("availability"));
                assertEquals("digest-100", rows.getString("provider_content_digest"));
                assertEquals("jc005-v1:sha256:abc", rows.getString("extraction_fingerprint"));
                assertEquals(8, rows.getInt("discovered_count"));
                assertEquals(2, rows.getInt("created_count"));
                assertEquals(1, rows.getInt("updated_count"));
                assertEquals(4, rows.getInt("unchanged_count"));
                assertEquals(1, rows.getInt("closed_count"));
                assertEquals(0, rows.getInt("reopened_count"));
                assertEquals(6, rows.getInt("ranking_ready_count"));
                assertEquals(2, rows.getInt("unready_count"));
                assertFalse(rows.next());
            }
            assertThrows(Exception.class, () -> statement.execute("""
                    UPDATE upgrade_v5.job_source_sync_runs SET lease_expires_at = NULL WHERE id = 100
                    """));
            assertThrows(Exception.class, () -> statement.execute("""
                    INSERT INTO upgrade_v5.job_source_sync_runs(
                        job_source_id, trigger, status, started_at, lease_expires_at)
                    VALUES (100, 'MANUAL', 'RUNNING', CURRENT_TIMESTAMP,
                            CURRENT_TIMESTAMP + INTERVAL '5 minutes')
                    """));
        }
    }
}
