package com.jobcopilot.discovery.workday;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class WorkdaySourceTest {
    @Test void acceptsObservedHostFamiliesAndCanonicalizesLocaleOnly() {
        assertThat(new WorkdaySource("nvidia.wd5.myworkdayjobs.com", "nvidia",
                "NVIDIAExternalCareerSite", "EN-us").locale()).isEqualTo("en-US");
        assertThat(new WorkdaySource("wd3.myworkdaysite.com", "telesure", "Wday", "en-US").siteKey())
                .isEqualTo("Wday");
    }

    @Test void rejectsArbitraryOrAmbiguousCoordinates() {
        for (String host : new String[]{"example.com", "myworkdayjobs.com", "evil.myworkdayjobs.com.example",
                "HTTPS://wd3.myworkdaysite.com", "wd3.myworkdaysite.com:443", "wd3.myworkdaysite.com/path",
                "user@wd3.myworkdaysite.com", "-bad.myworkdayjobs.com"}) {
            assertThatThrownBy(() -> new WorkdaySource(host, "tenant", "Site", "en-US"))
                    .as(host).isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> new WorkdaySource("wd3.myworkdaysite.com", "Tenant", "Site", "en-US"))
                .isInstanceOf(IllegalArgumentException.class);
        for (String value : new String[]{"../site", "site/name", "site?x", "site#x", " site", "site "})
            assertThatThrownBy(() -> new WorkdaySource("wd3.myworkdaysite.com", "tenant", value, "en-US"))
                    .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new WorkdaySource("wd3.myworkdaysite.com", "tenant", "Site", "not_locale!"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void pageAndDetailRequestsEnforceObservedAndSafetyBounds() {
        assertThat(new WorkdayPageRequest(2, 20)).isEqualTo(new WorkdayPageRequest(2, 20));
        assertThatThrownBy(() -> new WorkdayPageRequest(-1, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new WorkdayPageRequest(0, 21)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new WorkdayPageRequest(1_000_001, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThat(new WorkdayDetailRequest("/job/US-CA/Engineer_JR1").externalPath()).startsWith("/job/");
        assertThat(new WorkdayDetailRequest("/job/New%20York/Engineer_JR1").externalPath())
                .isEqualTo("/job/New%20York/Engineer_JR1");
        for (String path : new String[]{"job/x", "/job/../x", "/job//x", "/job/x?y", "/job/x#z",
                "/job/x\\y", " /job/x", "/job/%252e%252e/admin", "/job/%252fadmin",
                "/job/", "/job/bad%ZZ", "/job/bad name"})
            assertThatThrownBy(() -> new WorkdayDetailRequest(path)).as(path)
                    .isInstanceOf(IllegalArgumentException.class);
    }
}
