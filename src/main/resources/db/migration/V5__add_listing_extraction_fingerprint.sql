ALTER TABLE external_job_listings
    ADD COLUMN extraction_fingerprint VARCHAR(128);

ALTER TABLE external_job_listings
    ADD CONSTRAINT external_job_listings_extraction_fingerprint_not_blank
    CHECK (extraction_fingerprint IS NULL OR extraction_fingerprint ~ '[^[:space:]]');
