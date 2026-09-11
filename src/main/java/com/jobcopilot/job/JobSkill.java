package com.jobcopilot.job;

import jakarta.persistence.*;
import java.util.Objects;

@Embeddable
class JobSkill {
    enum Importance { REQUIRED, PREFERRED }
    @Column(nullable = false, length = 100) private String skill;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 16) private Importance importance;
    protected JobSkill() {}
    JobSkill(String skill, Importance importance) { this.skill = skill; this.importance = importance; }
    String skill() { return skill; }
    Importance importance() { return importance; }
    @Override public boolean equals(Object o) { return o instanceof JobSkill other && Objects.equals(skill, other.skill); }
    @Override public int hashCode() { return Objects.hashCode(skill); }
}

