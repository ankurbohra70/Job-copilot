package com.jobcopilot.resume;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record ProfessionalLink(@NotNull Type type, @NotNull @Size(max = 2048) String url) {
    public enum Type { LINKEDIN, GITHUB, LEETCODE, WEBSITE, OTHER }
    @Override public String toString() { return "ProfessionalLink[redacted]"; }
}
