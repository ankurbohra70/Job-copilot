package com.jobcopilot.discovery.lever;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LeverPageRequestTest {
    @Test void acceptsBoundaryAndPositivePagesAsDataOnly() {
        assertThat(new LeverPageRequest(0, 1)).isEqualTo(new LeverPageRequest(0, 1));
        assertThat(new LeverPageRequest(250, 100)).isEqualTo(new LeverPageRequest(250, 100));
        assertThat(LeverPageRequest.class.getDeclaredMethods())
                .extracting(java.lang.reflect.Method::getName)
                .doesNotContain("nextPage", "nextSkip", "fetchAll", "hasNext");
    }

    @Test void rejectsInvalidBounds() {
        assertThatThrownBy(() -> new LeverPageRequest(-1, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LeverPageRequest(0, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LeverPageRequest(0, 101)).isInstanceOf(IllegalArgumentException.class);
    }
}
