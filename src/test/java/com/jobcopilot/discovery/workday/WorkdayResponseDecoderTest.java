package com.jobcopilot.discovery.workday;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class WorkdayResponseDecoderTest {
    private final WorkdayResponseDecoder decoder = new WorkdayResponseDecoder();
    private final WorkdaySource source = new WorkdaySource(
            "company.wd5.myworkdayjobs.com", "company", "External_Site", "en-US");

    @Test void decodesSanitizedFixturesCapturedFromThreeStructurallyDifferentTenants() throws Exception {
        for (Fixture fixture : List.of(
                new Fixture("workday", new WorkdaySource("workday.wd5.myworkdayjobs.com",
                        "workday", "Workday", "en-US")),
                new Fixture("nvidia", new WorkdaySource("nvidia.wd5.myworkdayjobs.com",
                        "nvidia", "NVIDIAExternalCareerSite", "en-US")),
                new Fixture("telesure", new WorkdaySource("wd3.myworkdaysite.com",
                        "telesure", "Wday", "en-US")))) {
            try (var searchBody = resource(fixture.name() + "-search.json")) {
                var search = decoder.decodeSearch(searchBody, new WorkdayPageRequest(0, 20));
                assertThat(search).as(fixture.name()).isInstanceOf(
                        WorkdayResponseDecoder.SearchDecodeResult.Decoded.class);
            }
            try (var detailBody = resource(fixture.name() + "-detail.json")) {
                var detail = decoder.decodeDetail(detailBody, fixture.source());
                assertThat(detail).as(fixture.name()).isInstanceOf(
                        WorkdayResponseDecoder.DetailDecodeResult.Decoded.class);
            }
        }
    }

    @Test void decodesObservedSearchShapeAndIgnoresTenantSpecificFacets() {
        var decoded = search("""
                {"total":42,"jobPostings":[
                  {"title":"Engineer","externalPath":"/job/Remote/Engineer_JR1",
                   "locationsText":"2 Locations","postedOn":"Posted Today","remoteType":"Hybrid",
                   "bulletFields":["JR1"],"future":{"safe":true}}
                ],"facets":[{"facetParameter":"tenantCustom"}],"userAuthenticated":false}
                """, new WorkdayPageRequest(0, 20));
        assertThat(decoded.reportedTotal()).isEqualTo(42);
        assertThat(decoded.postings()).singleElement().satisfies(posting -> {
            assertThat(posting.title()).isEqualTo("Engineer");
            assertThat(posting.remoteType()).isEqualTo("Hybrid");
            assertThat(posting.bulletFields()).containsExactly("JR1");
        });
        assertThatThrownBy(() -> decoded.postings().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test void acceptsObservedZeroTotalOnNonzeroOffsetAndOptionalNulls() {
        var decoded = search("""
                {"total":0,"jobPostings":[{"title":"Engineer","externalPath":"/job/X/Engineer_JR1",
                  "locationsText":null,"postedOn":null,"remoteType":null,"bulletFields":null}]}
                """, new WorkdayPageRequest(2, 2));
        assertThat(decoded.postings()).hasSize(1);
        assertThat(decoded.postings().getFirst().locationsText()).isNull();
    }

    @Test void strictlyRecognizesObservedNonPostingMarkerAndRetainsProviderRowCount() {
        var decoded = search("""
                {"total":2,"jobPostings":[
                  {"bulletFields":["marker"]},
                  {"title":"Engineer","externalPath":"/job/X/Engineer_JR1"}]}
                """, new WorkdayPageRequest(0, 20));
        assertThat(decoded.postings()).singleElement();
        assertThat(decoded.returnedRowCount()).isEqualTo(2);

        for (String invalid : List.of(
                "{\"total\":1,\"jobPostings\":[{\"bulletFields\":[]}]}",
                "{\"total\":1,\"jobPostings\":[{\"bulletFields\":[\"marker\"],\"future\":true}]}",
                "{\"total\":1,\"jobPostings\":[{\"bulletFields\":[{}]}]}"))
            assertInvalidSearch(invalid);
    }

    @Test void laterTotalMayBeOmittedButInitialTotalIsRequired() {
        String body = "{\"jobPostings\":[]}";
        assertThat(search(body, new WorkdayPageRequest(20, 20)).reportedTotal()).isNull();
        assertThat(decoder.decodeSearch(bytes(body), new WorkdayPageRequest(0, 20)))
                .isInstanceOf(WorkdayResponseDecoder.SearchDecodeResult.Invalid.class);
    }

    @Test void rejectsMalformedAmbiguousAndInvalidSearchAtomically() {
        assertMalformedSearch("{");
        assertMalformedSearch("{\"total\":1,\"total\":2,\"jobPostings\":[]}");
        assertMalformedSearch("{\"total\":0,\"jobPostings\":[]} []");
        for (String json : new String[]{"[]", "{}", "{\"total\":-1,\"jobPostings\":[]}",
                "{\"total\":1.5,\"jobPostings\":[]}", "{\"total\":999999999999,\"jobPostings\":[]}",
                "{\"total\":1,\"jobPostings\":null}",
                "{\"total\":1,\"jobPostings\":[{}]}",
                "{\"total\":2,\"jobPostings\":[{\"title\":\"A\",\"externalPath\":\"/job/x\"},"
                    + "{\"title\":\"B\",\"externalPath\":\"/job/x\"}]}",
                "{\"total\":1,\"jobPostings\":[{\"title\":\"A\",\"externalPath\":\"https://evil/x\"}]}",
                "{\"total\":1,\"jobPostings\":[{\"title\":\"A\",\"externalPath\":\"/job/%2e%2e/admin\"}]}",
                "{\"total\":1,\"jobPostings\":[{\"title\":\"A\",\"externalPath\":\"/job/x\",\"bulletFields\":{}}]}"})
            assertInvalidSearch(json);
        assertInvalidSearch("{\"total\":21,\"jobPostings\":[" +
                java.util.stream.IntStream.range(0, 21).mapToObj(i ->
                    "{\"title\":\"A\",\"externalPath\":\"/job/x" + i + "\"}")
                    .collect(java.util.stream.Collectors.joining(",")) + "]}");
    }

    @Test void decodesCrossTenantDetailFieldsWithoutSelectingAnIdentity() {
        var detail = detail(minimalDetail().replace("</p>", "<script>alert(1)</script></p>"));
        assertThat(detail.opaqueId()).isEqualTo("fc437d72ea74100106e3deaa1e3a0000");
        assertThat(detail.jobReqId()).isEqualTo("JR-1");
        assertThat(detail.jobPostingId()).isEqualTo("Engineer_JR-1");
        assertThat(detail.descriptionHtml()).contains("<script>");
        assertThat(detail.startDate()).isEqualTo("2026-09-16");
        assertThat(detail.externalUrl().getHost()).isEqualTo(source.endpointHost());
        assertThat(detail.posted()).isTrue();
        assertThat(detail.canApply()).isTrue();
    }

    @Test void optionalDetailVocabularyMayBeMissingOrNull() {
        String json = minimalDetail()
                .replace("\"location\":\"Remote\",", "")
                .replace("\"postedOn\":\"Posted Today\",", "")
                .replace("\"startDate\":\"2026-09-16\",", "")
                .replace("\"timeType\":\"Full time\",", "")
                .replace("\"remoteType\":\"Hybrid\",", "")
                .replace("\"jobReqId\":\"JR-1\",", "")
                .replace("\"jobPostingId\":\"Engineer_JR-1\",", "")
                .replace("\"jobPostingSiteId\":\"External_Site\",", "");
        WorkdayJobDetail detail = detail(json);
        assertThat(detail.location()).isNull();
        assertThat(detail.jobReqId()).isNull();
        assertThat(detail.jobPostingSiteId()).isNull();
    }

    @Test void rejectsWrongHostSiteTypesAndMissingRequiredDetail() {
        assertInvalidDetail("{}");
        assertInvalidDetail(minimalDetail().replace("\"jobDescription\":\"<p>Build systems</p>\",", ""));
        assertInvalidDetail(minimalDetail().replace("\"externalUrl\":\"https://company.wd5.myworkdayjobs.com/External_Site/job/Remote/Engineer_JR-1\",", ""));
        assertInvalidDetail(minimalDetail().replace(source.endpointHost(), "evil.example"));
        assertInvalidDetail(minimalDetail().replace("External_Site", "OtherSite"));
        assertInvalidDetail(minimalDetail().replace("\"jobPostingSiteId\":\"External_Site\",", "")
                .replace("/External_Site/job/", "/OtherSite/job/"));
        assertInvalidDetail(minimalDetail().replace("/External_Site/job/Remote/Engineer_JR-1",
                "/External_Site/job/../../OtherSite/job/Engineer_JR-1"));
        assertInvalidDetail(minimalDetail().replace("/External_Site/job/Remote/Engineer_JR-1",
                "/External_Site/job/%2e%2e/%2e%2e/OtherSite/job/Engineer_JR-1"));
        assertInvalidDetail(minimalDetail().replace("\"posted\":true", "\"posted\":null"));
        assertInvalidDetail(minimalDetail().replace("\"id\":\"fc437d72ea74100106e3deaa1e3a0000\",", ""));
        assertInvalidDetail(minimalDetail().replace("\"jobDescription\":\"<p>Build systems</p>\"",
                "\"jobDescription\":{}"));
        assertMalformedDetail(minimalDetail().replace("\"id\":", "\"id\":\"duplicate\",\"id\":"));
        assertMalformedDetail("{");
    }

    @Test void rejectsCrossSiteUrlForObservedMyworkdaysiteUrlShape() {
        WorkdaySource site = new WorkdaySource("wd3.myworkdaysite.com", "telesure", "Wday", "en-US");
        String detail = """
                {"jobPostingInfo":{"id":"opaque","title":"Engineer","jobDescription":"<p>Safe</p>",
                "posted":true,"canApply":true,
                "externalUrl":"https://wd3.myworkdaysite.com/recruiting/other/Wday/job/X/Role"}}
                """;
        assertThat(decoder.decodeDetail(bytes(detail), site))
                .isInstanceOf(WorkdayResponseDecoder.DetailDecodeResult.Invalid.class);
    }

    @Test void parserConstraintsRejectExcessiveStringsAndNesting() {
        assertMalformedDetail(minimalDetail().replace("<p>Build systems</p>",
                "x".repeat(WorkdayResponseDecoder.MAX_STRING_LENGTH + 1)));
        String nested = "{\"total\":0,\"jobPostings\":[],\"x\":" + "[".repeat(40) + "0" + "]".repeat(40) + "}";
        assertMalformedSearch(nested);
    }

    @Test void diagnosticStringsNeverExposeProviderDescriptionsOrDetailPaths() {
        WorkdayJobDetail detail = detail(minimalDetail().replace("Build systems", "PRIVATE_DESCRIPTION_CANARY"));
        WorkdayDetailRequest request = new WorkdayDetailRequest("/job/PRIVATE_PATH_CANARY");
        WorkdaySearchPosting posting = new WorkdaySearchPosting("PRIVATE_TITLE_CANARY",
                request.externalPath(), null, null, null, List.of());
        assertThat(detail.toString()).doesNotContain("PRIVATE_DESCRIPTION_CANARY");
        assertThat(request.toString()).doesNotContain("PRIVATE_PATH_CANARY");
        assertThat(posting.toString()).doesNotContain("PRIVATE_TITLE_CANARY", "PRIVATE_PATH_CANARY");
        assertThat(new WorkdayFetchResult.DetailSuccess(source, request, detail, java.time.Duration.ZERO).toString())
                .doesNotContain("PRIVATE_DESCRIPTION_CANARY", "PRIVATE_PATH_CANARY");
        assertThat(new WorkdayFetchResult.SearchSuccess(source, new WorkdayPageRequest(0, 1), 1,
                List.of(posting), java.time.Duration.ZERO).toString())
                .doesNotContain("PRIVATE_TITLE_CANARY", "PRIVATE_PATH_CANARY");
        assertThat(new WorkdayFetchResult.Failure(source, WorkdayFetchResult.Operation.DETAIL, null, request,
                WorkdayFetchResult.FailureKind.INVALID_RESPONSE, 200, java.time.Duration.ZERO).toString())
                .doesNotContain("PRIVATE_PATH_CANARY");
    }

    private WorkdayResponseDecoder.SearchDecodeResult.Decoded search(String json, WorkdayPageRequest page) {
        var result = decoder.decodeSearch(bytes(json), page);
        assertThat(result).isInstanceOf(WorkdayResponseDecoder.SearchDecodeResult.Decoded.class);
        return (WorkdayResponseDecoder.SearchDecodeResult.Decoded) result;
    }
    private WorkdayJobDetail detail(String json) {
        var result = decoder.decodeDetail(bytes(json), source);
        assertThat(result).isInstanceOf(WorkdayResponseDecoder.DetailDecodeResult.Decoded.class);
        return ((WorkdayResponseDecoder.DetailDecodeResult.Decoded) result).detail();
    }
    private void assertInvalidSearch(String json) { assertThat(decoder.decodeSearch(bytes(json), new WorkdayPageRequest(0, 20)))
            .isInstanceOf(WorkdayResponseDecoder.SearchDecodeResult.Invalid.class); }
    private void assertMalformedSearch(String json) { assertThat(decoder.decodeSearch(bytes(json), new WorkdayPageRequest(0, 20)))
            .isInstanceOf(WorkdayResponseDecoder.SearchDecodeResult.Malformed.class); }
    private void assertInvalidDetail(String json) { assertThat(decoder.decodeDetail(bytes(json), source))
            .isInstanceOf(WorkdayResponseDecoder.DetailDecodeResult.Invalid.class); }
    private void assertMalformedDetail(String json) { assertThat(decoder.decodeDetail(bytes(json), source))
            .isInstanceOf(WorkdayResponseDecoder.DetailDecodeResult.Malformed.class); }
    private static ByteArrayInputStream bytes(String value) {
        return new ByteArrayInputStream(value.getBytes(StandardCharsets.UTF_8));
    }
    private static java.io.InputStream resource(String name) {
        var value = WorkdayResponseDecoderTest.class.getResourceAsStream("/workday/fixtures/" + name);
        if (value == null) throw new IllegalStateException("Missing fixture " + name);
        return value;
    }
    private record Fixture(String name, WorkdaySource source) {}
    private String minimalDetail() {
        return """
                {"jobPostingInfo":{"id":"fc437d72ea74100106e3deaa1e3a0000","title":"Engineer",
                "jobDescription":"<p>Build systems</p>","location":"Remote","postedOn":"Posted Today",
                "startDate":"2026-09-16","timeType":"Full time","remoteType":"Hybrid",
                "jobReqId":"JR-1","jobPostingId":"Engineer_JR-1","jobPostingSiteId":"External_Site",
                "externalUrl":"https://company.wd5.myworkdayjobs.com/External_Site/job/Remote/Engineer_JR-1",
                "posted":true,"canApply":true},"hiringOrganization":{"name":"Company"},"future":true}
                """;
    }
}
