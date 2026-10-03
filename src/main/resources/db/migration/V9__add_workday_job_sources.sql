ALTER TABLE job_sources DROP CONSTRAINT uq_job_sources_identity;
ALTER TABLE job_sources DROP CONSTRAINT job_sources_provider_check;
ALTER TABLE job_sources DROP CONSTRAINT job_sources_region_check;
ALTER TABLE job_sources DROP CONSTRAINT job_sources_source_key_not_blank;
ALTER TABLE job_sources DROP CONSTRAINT job_sources_source_key_canonical_check;
ALTER TABLE job_sources DROP CONSTRAINT job_sources_source_key_control_free_check;

ALTER TABLE job_sources RENAME COLUMN region TO routing_region;
ALTER TABLE job_sources ALTER COLUMN routing_region DROP NOT NULL;
ALTER TABLE job_sources ALTER COLUMN source_key DROP NOT NULL;
ALTER TABLE job_sources
    ADD COLUMN endpoint_host VARCHAR(255),
    ADD COLUMN tenant_key VARCHAR(100),
    ADD COLUMN site_key VARCHAR(100),
    ADD COLUMN locale VARCHAR(35);

ALTER TABLE job_sources
    ADD CONSTRAINT job_sources_provider_check CHECK (provider IN ('LEVER', 'WORKDAY')),
    ADD CONSTRAINT job_sources_routing_region_check CHECK (
        routing_region IS NULL OR routing_region IN ('GLOBAL', 'EU')),
    ADD CONSTRAINT job_sources_source_key_shape_check CHECK (
        source_key IS NULL OR (
            btrim(source_key) <> ''
            AND source_key = lower(btrim(source_key))
            AND source_key !~ '[[:cntrl:]]')),
    ADD CONSTRAINT job_sources_endpoint_host_shape_check CHECK (
        endpoint_host IS NULL OR (
            endpoint_host = lower(btrim(endpoint_host))
            AND endpoint_host ~ '^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?(\.[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?)+$')),
    ADD CONSTRAINT job_sources_tenant_key_shape_check CHECK (
        tenant_key IS NULL OR (
            tenant_key = lower(btrim(tenant_key))
            AND tenant_key ~ '^[a-z0-9][a-z0-9_-]{0,99}$')),
    ADD CONSTRAINT job_sources_site_key_shape_check CHECK (
        site_key IS NULL OR (
            site_key = btrim(site_key)
            AND site_key ~ '^[A-Za-z0-9][A-Za-z0-9_-]{0,99}$')),
    ADD CONSTRAINT job_sources_locale_shape_check CHECK (
        locale IS NULL OR (
            locale = btrim(locale)
            AND locale ~ '^[a-z]{2,8}(-([A-Z]{2}|[A-Za-z0-9]{1,8}))*$')),
    ADD CONSTRAINT job_sources_provider_configuration_check CHECK (
        (provider = 'LEVER'
            AND routing_region IS NOT NULL AND source_key IS NOT NULL
            AND endpoint_host IS NULL AND tenant_key IS NULL AND site_key IS NULL AND locale IS NULL)
        OR
        (provider = 'WORKDAY'
            AND routing_region IS NULL AND source_key IS NULL
            AND endpoint_host IS NOT NULL AND tenant_key IS NOT NULL
            AND site_key IS NOT NULL AND locale IS NOT NULL));

CREATE UNIQUE INDEX uq_job_sources_lever_identity
    ON job_sources(provider, routing_region, source_key)
    WHERE provider = 'LEVER';

CREATE UNIQUE INDEX uq_job_sources_workday_identity
    ON job_sources(provider, endpoint_host, tenant_key, site_key, locale)
    WHERE provider = 'WORKDAY';
