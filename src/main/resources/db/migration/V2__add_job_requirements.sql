ALTER TABLE jobs ADD COLUMN min_years_experience NUMERIC(4,2)
    CHECK (min_years_experience BETWEEN 0 AND 80);
CREATE TABLE job_skills (
    job_id BIGINT NOT NULL REFERENCES jobs(id) ON DELETE CASCADE,
    skill VARCHAR(100) NOT NULL,
    importance VARCHAR(16) NOT NULL CHECK (importance IN ('REQUIRED', 'PREFERRED')),
    PRIMARY KEY (job_id, skill)
);

