ALTER TABLE candidate_profiles
    ADD COLUMN status VARCHAR(16) NOT NULL DEFAULT 'CONFIRMED',
    ADD COLUMN revision BIGINT NOT NULL DEFAULT 1,
    ADD COLUMN confirmed_at TIMESTAMP(6);

UPDATE candidate_profiles
SET status = 'CONFIRMED',
    revision = 1,
    confirmed_at = COALESCE(updated_at, created_at);

ALTER TABLE candidate_profiles
    ADD CONSTRAINT candidate_profiles_status_check CHECK (status IN ('DRAFT', 'CONFIRMED')),
    ADD CONSTRAINT candidate_profiles_revision_check CHECK (revision >= 1),
    ADD CONSTRAINT candidate_profiles_confirmation_check CHECK (
        (status = 'DRAFT' AND confirmed_at IS NULL)
        OR (status = 'CONFIRMED' AND confirmed_at IS NOT NULL));

ALTER TABLE job_search_preferences
    ALTER COLUMN default_resume_strategy DROP NOT NULL,
    ADD COLUMN relocation_willing VARCHAR(16) NOT NULL DEFAULT 'UNKNOWN',
    ADD COLUMN compensation_currency VARCHAR(3),
    ADD COLUMN minimum_compensation NUMERIC(14,2),
    ADD COLUMN desired_compensation NUMERIC(14,2);

INSERT INTO job_search_preferences(
    candidate_profile_id, default_resume_strategy, relocation_willing,
    compensation_currency, minimum_compensation, desired_compensation,
    created_at, updated_at)
SELECT id, NULL, relocation_willing, compensation_currency,
       minimum_compensation, desired_compensation, updated_at, updated_at
FROM candidate_profiles profile
WHERE NOT EXISTS (
    SELECT 1 FROM job_search_preferences preference
    WHERE preference.candidate_profile_id = profile.id)
  AND (relocation_willing <> 'UNKNOWN'
       OR compensation_currency IS NOT NULL
       OR minimum_compensation IS NOT NULL
       OR desired_compensation IS NOT NULL);

UPDATE job_search_preferences preference
SET relocation_willing = profile.relocation_willing,
    compensation_currency = profile.compensation_currency,
    minimum_compensation = profile.minimum_compensation,
    desired_compensation = profile.desired_compensation,
    updated_at = GREATEST(preference.updated_at, profile.updated_at)
FROM candidate_profiles profile
WHERE preference.candidate_profile_id = profile.id
  AND (profile.relocation_willing <> 'UNKNOWN'
       OR profile.compensation_currency IS NOT NULL
       OR profile.minimum_compensation IS NOT NULL
       OR profile.desired_compensation IS NOT NULL);

UPDATE candidate_profiles
SET relocation_willing = 'UNKNOWN',
    compensation_currency = NULL,
    minimum_compensation = NULL,
    desired_compensation = NULL;

ALTER TABLE job_search_preferences
    ADD CONSTRAINT job_search_preferences_relocation_check
        CHECK (relocation_willing IN ('YES', 'NO', 'UNKNOWN')),
    ADD CONSTRAINT job_search_preferences_compensation_check CHECK (
        (minimum_compensation IS NULL AND desired_compensation IS NULL AND compensation_currency IS NULL)
        OR (compensation_currency IS NOT NULL
            AND (minimum_compensation IS NOT NULL OR desired_compensation IS NOT NULL)
            AND compensation_currency ~ '^[A-Z]{3}$'
            AND (minimum_compensation IS NULL OR minimum_compensation >= 0)
            AND (desired_compensation IS NULL OR desired_compensation >= 0)
            AND (minimum_compensation IS NULL OR desired_compensation IS NULL
                 OR desired_compensation >= minimum_compensation)));
