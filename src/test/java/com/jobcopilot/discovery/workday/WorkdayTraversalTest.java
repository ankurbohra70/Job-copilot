package com.jobcopilot.discovery.workday;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class WorkdayTraversalTest {
    private static final WorkdaySource SOURCE = new WorkdaySource(
            "acme.wd3.myworkdayjobs.com", "acme", "External", "en-US");

    @Test void singleShortPageIsCompleteOnlyAfterEveryDetail() {
        Stub gateway = new Stub().page(0, 2, 0, 2);
        var result = new WorkdayTraversal(gateway).traverse(SOURCE);
        assertThat(result).isInstanceOf(WorkdayTraversal.Complete.class);
        assertThat(((WorkdayTraversal.Complete) result).observations()).hasSize(2);
        assertThat(gateway.searchOffsets).containsExactly(0);
        assertThat(gateway.detailCalls).hasValue(2);
    }

    @Test void fullPageRequiresAnObservedEmptyTerminalPage() {
        Stub gateway = new Stub().page(0, 20, 0, 20).page(20, 0, 20, 0);
        assertThat(new WorkdayTraversal(gateway).traverse(SOURCE))
                .isInstanceOf(WorkdayTraversal.Complete.class);
        assertThat(gateway.searchOffsets).containsExactly(0, 20);
        assertThat(gateway.detailCalls).hasValue(20);
    }

    @Test void threePagesDoNotTerminateOnLaterZeroOrAbsentTotal() {
        Stub gateway = new Stub().page(0, 45, 0, 20).page(20, 0, 20, 20)
                .page(40, null, 40, 5);
        var result = new WorkdayTraversal(gateway).traverse(SOURCE);
        assertThat(result).isInstanceOf(WorkdayTraversal.Complete.class);
        assertThat(((WorkdayTraversal.Complete) result).observations()).hasSize(45);
        assertThat(gateway.searchOffsets).containsExactly(0, 20, 40);
        assertThat(gateway.detailCalls).hasValue(45);
    }

    @Test void nonPostingMarkerConsumesProviderPaginationWithoutPrematureCompletion() {
        Stub gateway = new Stub().page(0, 22, 0, 19, 20).page(20, 0, 20, 2, 2);
        var result = new WorkdayTraversal(gateway).traverse(SOURCE);
        assertThat(result).isInstanceOf(WorkdayTraversal.Complete.class);
        assertThat(((WorkdayTraversal.Complete) result).observations()).hasSize(21);
        assertThat(gateway.searchOffsets).containsExactly(0, 20);
        assertThat(gateway.detailCalls).hasValue(21);
    }

    @Test void zeroJobsNeedsAnActualEmptyFirstPage() {
        Stub gateway = new Stub().page(0, 0, 0, 0);
        assertThat(new WorkdayTraversal(gateway).traverse(SOURCE))
                .isInstanceOf(WorkdayTraversal.Complete.class);
    }

    @Test void changedTotalsAndEmptyIntermediatePageAreIncomplete() {
        for (Integer later : List.of(39, 41)) {
            Stub gateway = new Stub().page(0, 40, 0, 20).page(20, later, 20, 20);
            assertFailure(gateway, "INCONSISTENT_PAGINATION");
        }
        assertFailure(new Stub().page(0, 45, 0, 20).page(20, 0, 20, 0),
                "INCONSISTENT_PAGINATION");
        assertFailure(new Stub().page(0, 25, 0, 20).page(20, null, 20, 3),
                "INCONSISTENT_PAGINATION");
        assertFailure(new Stub().page(0, 20, 0, 19), "INCONSISTENT_PAGINATION");
    }

    @Test void repeatedPageAndDuplicateOpaqueIdentityAreIncomplete() {
        assertFailure(new Stub().page(0, 40, 0, 20).page(20, 0, 0, 20),
                "DUPLICATE_EXTERNAL_ID_DURING_TRAVERSAL");
        Stub reusedIdentity = new Stub().page(0, 2, 0, 2);
        reusedIdentity.ids.put(1, "id-0");
        assertFailure(reusedIdentity, "DUPLICATE_EXTERNAL_ID_DURING_TRAVERSAL");
    }

    @Test void responseForAnOldOffsetCannotAdvanceTraversal() {
        Stub gateway = new Stub().page(0, 40, 0, 20).page(20, 40, 20, 20);
        gateway.pages.put(20, gateway.pages.get(0));
        assertFailure(gateway, "INVALID_RESPONSE");
    }

    @Test void limitsFailExplicitlyWithoutReturningACompleteResult() {
        assertThat(new WorkdayTraversal(new Stub().page(0, 40, 0, 20), 1, 100)
                .traverse(SOURCE)).isEqualTo(new WorkdayTraversal.Incomplete(
                        "TRAVERSAL_LIMIT_EXCEEDED", 0, 20));
        assertThat(new WorkdayTraversal(new Stub().page(0, 21, 0, 20), 10, 20)
                .traverse(SOURCE)).isEqualTo(new WorkdayTraversal.Incomplete(
                        "TRAVERSAL_LIMIT_EXCEEDED", 0, 0));
        Stub oversizedAggregate = new Stub().page(0, 17, 0, 17);
        oversizedAggregate.description = "x".repeat(WorkdayResponseDecoder.MAX_STRING_LENGTH);
        assertFailure(oversizedAggregate, "TRAVERSAL_LIMIT_EXCEEDED");
    }

    @Test void everyGatewayDetailFailureEndsTheTraversal() {
        for (WorkdayFetchResult.FailureKind kind : List.of(
                WorkdayFetchResult.FailureKind.SOURCE_NOT_FOUND,
                WorkdayFetchResult.FailureKind.RATE_LIMITED,
                WorkdayFetchResult.FailureKind.PROVIDER_UNAVAILABLE,
                WorkdayFetchResult.FailureKind.TIMEOUT,
                WorkdayFetchResult.FailureKind.CONNECTION_FAILURE,
                WorkdayFetchResult.FailureKind.MALFORMED_RESPONSE,
                WorkdayFetchResult.FailureKind.INVALID_RESPONSE,
                WorkdayFetchResult.FailureKind.RESPONSE_TOO_LARGE)) {
            Stub gateway = new Stub().page(0, 2, 0, 2);
            gateway.detailFailure = kind;
            assertFailure(gateway, kind.name());
            assertThat(gateway.detailCalls).hasValue(1);
        }
    }

    @Test void failedLaterSearchPageCannotClaimCompleteness() {
        Stub gateway = new Stub().page(0, 40, 0, 20);
        gateway.searchFailureOffset = 20;
        assertFailure(gateway, "TIMEOUT");
        assertThat(gateway.detailCalls).hasValue(20);
    }

    @Test void detailMismatchOrUnusableDetailFailsWithoutSkippingTheRow() {
        for (int variant = 1; variant <= 6; variant++) {
            Stub gateway = new Stub().page(0, 1, 0, 1);
            gateway.detailVariant = variant;
            assertFailure(gateway, "POSTING_MAPPING_FAILED");
        }
    }

    @Test void repeatedDiscoveryKeepsProviderIdentityAcrossPresentationEdits() {
        Stub first = new Stub().page(0, 1, 0, 1);
        String id = ((WorkdayTraversal.Complete) new WorkdayTraversal(first).traverse(SOURCE))
                .observations().getFirst().detail().opaqueId();
        Stub edited = new Stub().page(0, 1, 0, 1);
        edited.description = "A changed description";
        String nextId = ((WorkdayTraversal.Complete) new WorkdayTraversal(edited).traverse(SOURCE))
                .observations().getFirst().detail().opaqueId();
        assertThat(nextId).isEqualTo(id);
        // Repost stability needs live provider evidence; this only proves no presentation fingerprint is used here.
    }

    @Test void titleLocationAndUrlQueryChangesDoNotCreateAPresentationFingerprint() {
        Stub first = new Stub().page(0, 1, 0, 1);
        String id = ((WorkdayTraversal.Complete) new WorkdayTraversal(first).traverse(SOURCE))
                .observations().getFirst().detail().opaqueId();
        Stub changed = new Stub();
        changed.title = "Staff Engineer";
        changed.location = "Berlin";
        changed.urlQuery = "?source=careers";
        changed.page(0, 1, 0, 1);
        var result = new WorkdayTraversal(changed).traverse(SOURCE);
        assertThat(result).isInstanceOf(WorkdayTraversal.Complete.class);
        assertThat(((WorkdayTraversal.Complete) result).observations().getFirst().detail().opaqueId())
                .isEqualTo(id);
    }

    private static void assertFailure(Stub gateway, String expected) {
        var result = new WorkdayTraversal(gateway).traverse(SOURCE);
        assertThat(result).isInstanceOf(WorkdayTraversal.Incomplete.class);
        assertThat(((WorkdayTraversal.Incomplete) result).failureCode()).isEqualTo(expected);
    }

    private static final class Stub implements WorkdayPostingGateway {
        private final Map<Integer, WorkdayFetchResult.SearchSuccess> pages = new HashMap<>();
        private final Map<Integer, String> ids = new HashMap<>();
        private final List<Integer> searchOffsets = new ArrayList<>();
        private final AtomicInteger detailCalls = new AtomicInteger();
        private WorkdayFetchResult.FailureKind detailFailure;
        private Integer searchFailureOffset;
        private int detailVariant;
        private String description = "Build reliable services";
        private String title;
        private String location = "Paris";
        private String urlQuery = "";

        Stub page(int offset, Integer total, int firstId, int count) {
            return page(offset, total, firstId, count, count);
        }

        Stub page(int offset, Integer total, int firstId, int count, int returnedRowCount) {
            List<WorkdaySearchPosting> rows = new ArrayList<>();
            for (int i = firstId; i < firstId + count; i++)
                rows.add(new WorkdaySearchPosting(title == null ? "Role " + i : title,
                        "/job/Role-" + i, location, null, null, List.of()));
            WorkdayPageRequest request = new WorkdayPageRequest(offset, 20);
            pages.put(offset, new WorkdayFetchResult.SearchSuccess(SOURCE, request, total, rows,
                    returnedRowCount, Duration.ZERO));
            return this;
        }

        @Override public WorkdayFetchResult searchPage(WorkdaySource source, WorkdayPageRequest page) {
            searchOffsets.add(page.offset());
            if (searchFailureOffset != null && searchFailureOffset == page.offset())
                return failure(WorkdayFetchResult.Operation.SEARCH, page, null,
                        WorkdayFetchResult.FailureKind.TIMEOUT);
            return pages.get(page.offset());
        }

        @Override public WorkdayFetchResult fetchDetail(WorkdaySource source, WorkdayDetailRequest request) {
            detailCalls.incrementAndGet();
            if (detailFailure != null)
                return failure(WorkdayFetchResult.Operation.DETAIL, null, request, detailFailure);
            int number = Integer.parseInt(request.externalPath().substring("/job/Role-".length()));
            WorkdayJobDetail detail = new WorkdayJobDetail(ids.getOrDefault(number, "id-" + number),
                    detailVariant == 1 ? "Wrong role" : title == null ? "Role " + number : title,
                    detailVariant == 2 ? "" : description, location, null, "2026-09-16", null,
                    null, null, null, SOURCE.siteKey(),
                    URI.create("https://" + SOURCE.endpointHost() + "/" + SOURCE.siteKey()
                            + (detailVariant == 3 ? "/job/Other" : request.externalPath()) + urlQuery),
                    detailVariant != 4, detailVariant != 5);
            if (detailVariant == 6)
                detail = new WorkdayJobDetail("", detail.title(), detail.descriptionHtml(), detail.location(),
                        detail.postedOn(), detail.startDate(), detail.timeType(), detail.remoteType(),
                        detail.jobReqId(), detail.jobPostingId(), detail.jobPostingSiteId(),
                        detail.externalUrl(), detail.posted(), detail.canApply());
            return new WorkdayFetchResult.DetailSuccess(SOURCE, request, detail, Duration.ZERO);
        }

        private WorkdayFetchResult.Failure failure(WorkdayFetchResult.Operation operation,
                WorkdayPageRequest page, WorkdayDetailRequest detail,
                WorkdayFetchResult.FailureKind kind) {
            return new WorkdayFetchResult.Failure(SOURCE, operation, page, detail, kind, null,
                    Duration.ZERO);
        }
    }
}
