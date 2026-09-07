package com.jobcopilot.resume;

import java.time.LocalDate;
import java.util.List;

public record CandidateProfileData(List<String> skills, Integer totalExperienceMonths,
        Integer observedExperienceMonths, Assessment experienceAssessment,
        List<WorkExperience> workExperience, List<Education> education, List<Project> projects,
        List<String> keywords, List<String> roleCategories, List<Evidence> evidence, List<String> warnings) {
    public enum Assessment { KNOWN, UNKNOWN }
    public enum DatePrecision { MONTH, YEAR }
    public record PartialDate(int year, Integer month, DatePrecision precision) {}
    public record Evidence(String kind, String value, String sourceText) {}
    public record WorkExperience(String title, String company, PartialDate start, PartialDate end,
            boolean ongoing, List<String> bullets, String sourceText) {
        public WorkExperience { bullets = List.copyOf(bullets); }
    }
    public record Education(String institution, String qualification, PartialDate start, PartialDate end, String sourceText) {}
    public record Project(String title, List<String> bullets, List<String> technologies, String sourceText) {
        public Project { bullets = List.copyOf(bullets); technologies = List.copyOf(technologies); }
    }
    public CandidateProfileData {
        skills = List.copyOf(skills); workExperience = List.copyOf(workExperience);
        education = List.copyOf(education); projects = List.copyOf(projects);
        keywords = List.copyOf(keywords); roleCategories = List.copyOf(roleCategories);
        evidence = List.copyOf(evidence); warnings = List.copyOf(warnings);
        if (totalExperienceMonths != null && totalExperienceMonths < 0) throw new IllegalArgumentException("Negative experience");
        if ((experienceAssessment == Assessment.KNOWN) != (totalExperienceMonths != null))
            throw new IllegalArgumentException("Experience assessment must agree with total");
    }
}

