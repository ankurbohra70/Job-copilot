package com.jobcopilot.resume;

import jakarta.persistence.*;

@Embeddable
record PreferenceRole(
        @Enumerated(EnumType.STRING) @Column(name = "kind", length = 16, nullable = false) JobSearchPreference.RoleKind kind,
        @Column(name = "role_title", length = 255, nullable = false) String roleTitle) {
    protected PreferenceRole() { this(null, null); }
}
