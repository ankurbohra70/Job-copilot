package com.jobcopilot.discovery;

import java.time.LocalDateTime;

interface ScheduledSyncCandidate {
    Long getSourceId();
    LocalDateTime getDueAt();
}
