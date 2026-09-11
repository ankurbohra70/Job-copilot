package com.jobcopilot.intelligence;

import java.math.BigDecimal;
import java.util.List;

/** Accepted payload. Only the later deterministic validator may establish acceptance. */
public record JobIntelligence(Facts facts, Interpretations interpretations, List<Evidence> evidence,
        List<Uncertainty> uncertainties, MinimumExperience minimumExperience) {
    public JobIntelligence { evidence = List.copyOf(evidence); uncertainties = List.copyOf(uncertainties); }
    public enum Importance { REQUIRED, PREFERRED, UNSPECIFIED }
    public enum Unit { YEARS, MONTHS }
    public enum Scope { OVERALL, RELEVANT, SKILL_SPECIFIC }
    public enum Source { TITLE, DESCRIPTION }
    public enum Role { BACKEND, FRONTEND, FULL_STACK, DATA_ENGINEERING, PLATFORM_DEVOPS, OTHER }
    public enum Seniority { INTERN, ENTRY, MID, SENIOR, LEAD, STAFF_PLUS, MANAGEMENT }
    public enum UncertaintyCode { AMBIGUOUS_CLASSIFICATION, AMBIGUOUS_EXPERIENCE, CONFLICTING_SOURCE, INSUFFICIENT_CONTEXT }
    public enum MinimumStatus { KNOWN, NOT_STATED, AMBIGUOUS }
    public record Facts(List<Skill> skills, List<ExperienceClause> experienceClauses, List<Qualification> qualifications) {
        public Facts { skills = List.copyOf(skills); experienceClauses = List.copyOf(experienceClauses); qualifications = List.copyOf(qualifications); }
    }
    public record Interpretations(RoleClaim roleFamily, SeniorityClaim seniority,
            List<TextClaim> responsibilities, List<TextClaim> technicalConcepts) {
        public Interpretations { responsibilities = List.copyOf(responsibilities); technicalConcepts = List.copyOf(technicalConcepts); }
    }
    public record Skill(String name, Importance importance, List<String> evidenceIds) {
        public Skill { evidenceIds = List.copyOf(evidenceIds); }
    }
    public record ExperienceClause(String text, BigDecimal minimum, Unit unit, Importance importance,
            Scope scope, boolean conditional, List<String> evidenceIds) {
        public ExperienceClause { evidenceIds = List.copyOf(evidenceIds); }
    }
    public record Qualification(String text, Importance importance, List<String> evidenceIds) {
        public Qualification { evidenceIds = List.copyOf(evidenceIds); }
    }
    public record TextClaim(String text, List<String> evidenceIds) {
        public TextClaim { evidenceIds = List.copyOf(evidenceIds); }
    }
    public record RoleClaim(Role value, List<String> evidenceIds) {
        public RoleClaim { evidenceIds = List.copyOf(evidenceIds); }
    }
    public record SeniorityClaim(Seniority value, List<String> evidenceIds) {
        public SeniorityClaim { evidenceIds = List.copyOf(evidenceIds); }
    }
    public record Evidence(String id, Source source, String quote) {}
    public record Uncertainty(UncertaintyCode code, String target, List<String> evidenceIds) {
        public Uncertainty { evidenceIds = List.copyOf(evidenceIds); }
    }
    /** Java-derived; deliberately absent from the provider output contract. */
    public record MinimumExperience(MinimumStatus status, BigDecimal months) {}
}
