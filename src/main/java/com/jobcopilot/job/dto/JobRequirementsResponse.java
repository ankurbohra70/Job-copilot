package com.jobcopilot.job.dto;

import java.math.BigDecimal;
import java.util.List;

public record JobRequirementsResponse(List<String> requiredSkills, List<String> preferredSkills,
                                      BigDecimal minYearsExperience) {
    public JobRequirementsResponse { requiredSkills = List.copyOf(requiredSkills); preferredSkills = List.copyOf(preferredSkills); }
}

