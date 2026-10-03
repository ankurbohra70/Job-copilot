ALTER TABLE resumes ADD COLUMN layout_version VARCHAR(32);

ALTER TABLE candidate_profiles
    ADD COLUMN professional_links JSONB,
    ADD COLUMN fact_extractor_version VARCHAR(64),
    ADD COLUMN fact_extraction JSONB,
    ADD CONSTRAINT candidate_profiles_professional_links_shape CHECK (
        professional_links IS NULL OR (jsonb_typeof(professional_links) = 'array'
            AND jsonb_array_length(professional_links) <= 20)),
    ADD CONSTRAINT candidate_profiles_fact_extraction_shape CHECK (
        fact_extraction IS NULL OR jsonb_typeof(fact_extraction) = 'object');

-- Deliberately no UPDATE or capability defaults: historical records remain untouched.
