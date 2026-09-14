package com.jobcopilot.resume;

import com.jobcopilot.application.ResumeStrategy;
import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "resume_routes")
class ResumeRoute {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "candidate_profile_id", nullable = false)
    private CandidateProfile candidateProfile;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "resume_id", nullable = false)
    private Resume resume;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 16) private ResumeStrategy strategy;
    @Column(length = 255) private String roleFamily;
    @Column(nullable = false) private boolean isDefault;
    @Column(nullable = false, length = 255) private String variantLabel;
    @Column(nullable = false) private boolean approved;
    @Column(nullable = false, updatable = false) private LocalDateTime createdAt;
    protected ResumeRoute() {}
    ResumeRoute(CandidateProfile profile, Resume resume, ResumeStrategy strategy, String roleFamily,
            boolean isDefault, String variantLabel, boolean approved) {
        candidateProfile = profile; this.resume = resume; this.strategy = java.util.Objects.requireNonNull(strategy);
        this.isDefault = isDefault; this.roleFamily = isDefault ? null : required(roleFamily, "roleFamily");
        this.variantLabel = required(variantLabel, "variantLabel"); this.approved = approved;
    }
    @PrePersist void onCreate() { createdAt = LocalDateTime.now(); }
    Long id() { return id; }
    Long profileId() { return candidateProfile.id(); }
    Long resumeId() { return resume.id(); }
    ResumeStrategy strategy() { return strategy; }
    String roleFamily() { return roleFamily; }
    boolean isDefault() { return isDefault; }
    String variantLabel() { return variantLabel; }
    boolean approved() { return approved; }
    boolean usable() { return resume.usableRouteSource(); }
    LocalDateTime createdAt() { return createdAt; }
    ResumeRouteSnapshot snapshot() { return new ResumeRouteSnapshot(id, profileId(), resumeId(), strategy, roleFamily,
            isDefault, variantLabel, approved, resume.fileName(), resume.extractorVersion(), createdAt); }
    private static String required(String value, String name) {
        if (value == null || value.strip().isEmpty() || value.length() > 255 || value.chars().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException(name + " must be non-blank and at most 255 characters");
        return value.strip();
    }
}
