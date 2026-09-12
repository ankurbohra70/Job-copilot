package com.jobcopilot.discovery.lever;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import static org.assertj.core.api.Assertions.assertThat;

class LeverFailureClassificationTest {
    @ParameterizedTest
    @CsvSource({
            "301, REQUEST_REJECTED", "307, REQUEST_REJECTED", "400, REQUEST_REJECTED",
            "403, REQUEST_REJECTED", "404, SOURCE_NOT_FOUND", "408, TIMEOUT",
            "429, RATE_LIMITED", "500, PROVIDER_UNAVAILABLE", "503, PROVIDER_UNAVAILABLE"
    })
    void classifiesStatuses(int status, LeverFetchResult.FailureKind expected) {
        assertThat(HttpLeverPostingGateway.classify(status)).isEqualTo(expected);
    }
}
