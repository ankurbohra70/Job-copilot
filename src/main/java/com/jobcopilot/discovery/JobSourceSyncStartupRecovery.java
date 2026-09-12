package com.jobcopilot.discovery;

import java.time.Clock;
import java.time.LocalDateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Component
class JobSourceSyncStartupRecovery implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(JobSourceSyncStartupRecovery.class);
    private final DiscoverySyncTransactions transactions;
    private final Clock clock;

    JobSourceSyncStartupRecovery(DiscoverySyncTransactions transactions, Clock clock) {
        this.transactions = transactions;
        this.clock = clock;
    }

    @Override
    public void run(ApplicationArguments args) {
        int recovered = transactions.recover(LocalDateTime.now(clock));
        if (recovered > 0) log.warn("Abandoned stale discovery sync runs count={}", recovered);
    }
}
