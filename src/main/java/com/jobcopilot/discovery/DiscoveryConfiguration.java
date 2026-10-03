package com.jobcopilot.discovery;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class DiscoveryConfiguration {
    @Bean
    Clock discoveryClock() {
        return Clock.systemDefaultZone();
    }
}
