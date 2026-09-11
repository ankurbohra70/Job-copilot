package com.jobcopilot.intelligence.evaluation;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EvaluationDatasetLoaderTest {
    @Test
    void loadsExactlyTwentyFourReviewedCasesWithCompleteCoverage() {
        LoadedEvaluation loaded = new EvaluationDatasetLoader().load();

        assertThat(loaded.dataset().cases()).hasSize(24)
                .allMatch(value -> "REVIEWED".equals(value.reviewStatus()));
        assertThat(loaded.responses().cases()).hasSize(24);
        assertThat(loaded.dataset().cases()).extracting(EvaluationCase::id).isSorted().doesNotHaveDuplicates();
        assertThat(loaded.dataset().cases().stream().flatMap(value -> value.tags().stream()))
                .contains("framework-overlap", "range", "conditional", "unsupported-claim", "canonical-stale");
    }

    @Test
    void rejectsVersionDriftAndMissingResponses() throws Exception {
        String cases = resource("cases.json");
        String responses = resource("scripted-responses.json");
        EvaluationDatasetLoader loader = new EvaluationDatasetLoader();

        assertThatThrownBy(() -> loader.load(cases.replaceFirst("jc007-evaluation-v1", "drift"), responses))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("dataset version");
        assertThatThrownBy(() -> loader.load(cases, responses.replaceFirst("01-required-skill", "unknown-case")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("unknown case");
    }

    @Test
    void rejectsMalformedGoldTagsAnchorsOccurrencesAndMinimums() throws Exception {
        String cases = resource("cases.json");
        String responses = resource("scripted-responses.json");
        EvaluationDatasetLoader loader = new EvaluationDatasetLoader();

        assertRejected(loader, replaceOnce(cases, "\"reviewStatus\": \"REVIEWED\",", ""), responses,
                "manually reviewed");
        assertRejected(loader, replaceOnce(cases, "\"skills\", \"required\"", "\"unknown-tag\", \"required\""),
                responses, "unknown evaluation tag");
        assertRejected(loader, replaceOnce(cases, "\"quote\": \"Java is required.\", \"occurrence\": 1",
                "\"quote\": \"missing anchor\", \"occurrence\": 1"), responses, "evidence occurrence");
        assertRejected(loader, replaceOnce(cases, "\"quote\": \"Java is required.\", \"occurrence\": 1",
                "\"quote\": \"Java is required.\", \"occurrence\": 2"), responses, "evidence occurrence");
        assertRejected(loader, replaceOnce(cases, "\"status\": \"NOT_STATED\", \"months\": null",
                "\"status\": \"NOT_STATED\", \"months\": 1"), responses, "aggregate minimum");
        assertRejected(loader, replaceOnce(cases, "{\"name\": \"Redis\", \"importance\": \"REQUIRED\"",
                "{\"name\": \"Java\", \"importance\": \"REQUIRED\""), responses, "duplicate gold skill");
        assertRejected(loader, replaceOnce(cases, "{\"name\": \"Docker\", \"importance\": \"PREFERRED\"",
                "{\"name\": \"Java\", \"importance\": \"PREFERRED\""), responses, "cross-class");
    }

    private static void assertRejected(EvaluationDatasetLoader loader, String cases, String responses,
            String message) {
        assertThatThrownBy(() -> loader.load(cases, responses)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(message);
    }

    private static String replaceOnce(String source, String target, String replacement) {
        int at = source.indexOf(target);
        assertThat(at).isGreaterThanOrEqualTo(0);
        return source.substring(0, at) + replacement + source.substring(at + target.length());
    }

    private static String resource(String name) throws Exception {
        try (var input = EvaluationDatasetLoaderTest.class.getResourceAsStream(
                "/job-intelligence/evaluation/v1/" + name)) {
            assertThat(input).isNotNull();
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
