package com.jobcopilot.discovery.lever;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LeverSourceTest {
    @Test void acceptsAlreadyCanonicalSourceWithoutChangingIt() {
        var source = new LeverSource(LeverRegion.GLOBAL, "example-site");
        assertThat(source.sourceKey()).isEqualTo("example-site");
        assertThat(source.region()).isEqualTo(LeverRegion.GLOBAL);
    }

    @Test void mirrorsPhaseOneCanonicalValuesExceptEndpointDotSegments() {
        for (String canonical : new String[]{"\u2003", "a.b", "a..b", "-example", "example-", "a_b",
                "a/b", "a?b", "a#b", "a%b", "a&b", "a=b", "café"}) {
            assertThat(new LeverSource(LeverRegion.GLOBAL, canonical).sourceKey()).isEqualTo(canonical);
        }
        assertThatThrownBy(() -> new LeverSource(LeverRegion.GLOBAL, "."))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LeverSource(LeverRegion.GLOBAL, ".."))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void rejectsInsteadOfNormalizingNoncanonicalInput() {
        assertThatThrownBy(() -> new LeverSource(LeverRegion.GLOBAL, "Example-Site"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LeverSource(LeverRegion.GLOBAL, " example-site"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LeverSource(LeverRegion.GLOBAL, "example-site "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void rejectsMissingBlankControlledAndOverlengthInput() {
        assertThatThrownBy(() -> new LeverSource(null, "site")).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new LeverSource(LeverRegion.EU, null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new LeverSource(LeverRegion.EU, "   ")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LeverSource(LeverRegion.EU, "site\t"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LeverSource(LeverRegion.EU, "x".repeat(101)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
