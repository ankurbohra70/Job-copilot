package com.jobcopilot.discovery.dto;

import com.jobcopilot.discovery.JobSourceSyncStatus;
import com.jobcopilot.discovery.JobSourceSyncTrigger;
import java.time.LocalDateTime;

public record JobSourceSyncRunResponse(
        long runId,
        long sourceId,
        JobSourceSyncTrigger trigger,
        JobSourceSyncStatus status,
        LocalDateTime startedAt,
        LocalDateTime completedAt,
        String failureCode,
        int discoveredCount,
        int createdCount,
        int updatedCount,
        int unchangedCount,
        int closedCount,
        int reopenedCount,
        int rankingReadyCount,
        int unreadyCount) {
}
