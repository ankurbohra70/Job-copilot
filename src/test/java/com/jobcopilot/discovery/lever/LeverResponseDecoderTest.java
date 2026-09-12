package com.jobcopilot.discovery.lever;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LeverResponseDecoderTest {
    private static final String FULL_POSTING = """
            {"id":" Post-A ","text":"Backend Engineer",
             "hostedUrl":"https://jobs.lever.co/example/Post-A",
             "applyUrl":"https://apply.example.net/example/Post-A/apply",
             "categories":{"location":"Remote","allLocations":["Remote","Pune"],
               "commitment":"Full time","team":"Platform","department":"Engineering"},
             "country":"IN","workplaceType":"future-flexible","createdAt":1553186035299,
             "description":"<div>Build systems</div>","descriptionPlain":"Build systems",
             "lists":[{"text":"Requirements","content":"<li>Java</li>"}],
             "additional":"<div>Equal opportunity</div>","additionalPlain":"Equal opportunity",
             "unknownFutureField":{"safe":true}}
            """;
    private final LeverResponseDecoder decoder = new LeverResponseDecoder();

    @Test void decodesRepresentativeResponsePreservingOrderVocabularyAndContent() {
        var result = decoded("[" + FULL_POSTING + "," + minimal("Post-B") + "]");

        assertThat(result).extracting(LeverPosting::externalId).containsExactly("Post-A", "Post-B");
        LeverPosting first = result.getFirst();
        assertThat(first.title()).isEqualTo("Backend Engineer");
        assertThat(first.hostedUrl().toString()).isEqualTo("https://jobs.lever.co/example/Post-A");
        assertThat(first.applyUrl().getHost()).isEqualTo("apply.example.net");
        assertThat(first.categories().allLocations()).containsExactly("Remote", "Pune");
        assertThat(first.categories().team()).isEqualTo("Platform");
        assertThat(first.workplaceType()).isEqualTo("future-flexible");
        assertThat(first.providerCreatedAt()).isEqualTo(Instant.ofEpochMilli(1553186035299L));
        assertThat(first.content().descriptionHtml()).isEqualTo("<div>Build systems</div>");
        assertThat(first.content().sections()).containsExactly(
                new LeverPosting.Section("Requirements", "<li>Java</li>"));
        assertThatThrownBy(() -> result.add(first)).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> first.categories().allLocations().add("Delhi"))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> first.content().sections().clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test void acceptsEmptyAndMissingOrNullOptionalValues() {
        assertThat(decoded("[]")).isEmpty();
        var missing = decoded("[" + minimal("one") + "]").getFirst();
        assertThat(missing.categories().allLocations()).isEmpty();
        assertThat(missing.content().sections()).isEmpty();
        assertThat(missing.country()).isNull();
        assertThat(missing.providerCreatedAt()).isNull();

        String nulls = minimal("two").replace("}",
                ",\"categories\":null,\"lists\":null,\"country\":null,\"workplaceType\":null}");
        var nullable = decoded("[" + nulls + "]").getFirst();
        assertThat(nullable.categories().allLocations()).isEmpty();
        assertThat(nullable.content().sections()).isEmpty();
    }

    @Test void ignoresOnlyMalformedOptionalCreatedAt() {
        for (String createdAt : List.of("\"not-a-number\"", "{}", "[]", "true", "false", "-1", "1.5",
                "null", "999999999999999999999")) {
            String posting = minimal("created").replace("}", ",\"createdAt\":" + createdAt + "}");
            assertThat(decoded("[" + posting + "]").getFirst().providerCreatedAt()).isNull();
        }
        assertInvalid("[" + minimal("wrong-country").replace("}", ",\"country\":42}") + "]");
        assertInvalid("[" + minimal("wrong-workplace").replace("}", ",\"workplaceType\":{}}") + "]");
    }

    @Test void distinguishesMalformedJsonFromStructurallyInvalidJson() {
        assertThat(decoder.decode(bytes("[not-json")))
                .isInstanceOf(LeverResponseDecoder.DecodeResult.Malformed.class);
        assertThat(decoder.decode(bytes("")))
                .isInstanceOf(LeverResponseDecoder.DecodeResult.Malformed.class);
        assertThat(decoder.decode(bytes("[] []")))
                .isInstanceOf(LeverResponseDecoder.DecodeResult.Malformed.class);
        assertInvalid("{}");
        assertInvalid("[42]");
    }

    @Test void rejectsInvalidRequiredIdentityTitleAndUrls() {
        assertInvalid("[{}]");
        assertInvalid("[" + minimal("").replace("\"id\":\"\"", "\"id\":\"   \"") + "]");
        assertInvalid("[" + minimal("bad\\nid") + "]");
        assertInvalid("[" + minimal("x".repeat(256)) + "]");
        assertInvalid("[" + minimal("missing-title").replace("\"text\":\"Title\",", "") + "]");
        assertInvalid("[" + minimal("blank-title").replace("\"text\":\"Title\"", "\"text\":\"  \"") + "]");
        assertInvalid("[" + minimal("http-url").replace("https://jobs.example/", "http://jobs.example/") + "]");
        assertInvalid("[" + minimal("relative").replace("https://jobs.example/relative", "/relative") + "]");
        assertInvalid("[" + minimal("userinfo").replace("https://jobs.example/userinfo",
                "https://user@jobs.example/userinfo") + "]");
        assertInvalid("[" + minimal("bad-apply").replace("https://apply.example/bad-apply", "not-a-url") + "]");
    }

    @Test void rejectsWrongTypesForRequiredFieldsWithoutWeakeningCreatedAtIsolation() {
        for (String field : List.of("id", "text", "hostedUrl", "applyUrl")) {
            for (String wrongType : List.of("null", "42", "{}", "[]", "true")) {
                String posting = minimal("typed").replace("\"" + field + "\":\""
                        + expectedValue(field, "typed") + "\"", "\"" + field + "\":" + wrongType);
                assertInvalid("[" + posting + "]");
            }
        }
        assertInvalid("[" + minimal("categories-number").replace("}", ",\"categories\":42}") + "]");
        assertInvalid("[" + minimal("lists-array-value").replace("}", ",\"lists\":[42]}") + "]");
    }

    @Test void rejectsDuplicatePropertiesAsAmbiguousProviderStructure() {
        String duplicateId = minimal("first").replace("\"id\":\"first\"",
                "\"id\":\"first\",\"id\":\"second\"");
        assertThat(decoder.decode(bytes("[" + duplicateId + "]")))
                .isInstanceOf(LeverResponseDecoder.DecodeResult.Malformed.class);
    }

    @Test void retainsEmbeddedTitleControlsAsUntrustedProviderContent() {
        String posting = minimal("controlled-title").replace("\"text\":\"Title\"",
                "\"text\":\"Line one\\nLine two\"");
        assertThat(decoded("[" + posting + "]").getFirst().title()).isEqualTo("Line one\nLine two");
    }

    @Test void rejectsDuplicateIdentityAndKnownContainerCorruptionAtomically() {
        assertInvalid("[" + minimal("same") + "," + minimal("same") + "]");
        assertInvalid("[" + minimal("categories").replace("}", ",\"categories\":[]}") + "]");
        assertInvalid("[" + minimal("locations").replace("}",
                ",\"categories\":{\"allLocations\":\"Remote\"}}") + "]");
        assertInvalid("[" + minimal("lists").replace("}", ",\"lists\":{}}") + "]");
        assertInvalid("[" + minimal("good") + ",{}]");
    }

    private List<LeverPosting> decoded(String json) {
        var result = decoder.decode(bytes(json));
        assertThat(result).isInstanceOf(LeverResponseDecoder.DecodeResult.Decoded.class);
        return ((LeverResponseDecoder.DecodeResult.Decoded) result).postings();
    }

    private void assertInvalid(String json) {
        assertThat(decoder.decode(bytes(json))).isInstanceOf(LeverResponseDecoder.DecodeResult.Invalid.class);
    }

    private static byte[] bytes(String value) { return value.getBytes(StandardCharsets.UTF_8); }

    private static String expectedValue(String field, String id) {
        return switch (field) {
            case "id" -> id;
            case "text" -> "Title";
            case "hostedUrl" -> "https://jobs.example/" + id;
            case "applyUrl" -> "https://apply.example/" + id;
            default -> throw new IllegalArgumentException(field);
        };
    }

    static String minimal(String id) {
        return "{\"id\":\"" + id + "\",\"text\":\"Title\",\"hostedUrl\":\"https://jobs.example/"
                + id + "\",\"applyUrl\":\"https://apply.example/" + id + "\"}";
    }
}
