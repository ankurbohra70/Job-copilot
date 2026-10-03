package com.jobcopilot.discovery.workday;

import java.net.URI;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Persistence-free CXS traversal. Only Complete is evidence for absence reconciliation. */
public final class WorkdayTraversal {
    public static final int PAGE_SIZE = 20;
    public static final int DEFAULT_MAX_PAGES = 500;
    public static final int DEFAULT_MAX_POSTINGS = 10_000;
    public static final int MAX_BUFFERED_DESCRIPTION_CHARS = 8_000_000;

    private final WorkdayPostingGateway gateway;
    private final int maxPages;
    private final int maxPostings;

    public WorkdayTraversal(WorkdayPostingGateway gateway) {
        this(gateway, DEFAULT_MAX_PAGES, DEFAULT_MAX_POSTINGS);
    }

    public WorkdayTraversal(WorkdayPostingGateway gateway, int maxPages, int maxPostings) {
        this.gateway = Objects.requireNonNull(gateway);
        if (maxPages < 1 || maxPages > WorkdayPageRequest.MAX_OFFSET / PAGE_SIZE
                || maxPostings < 1 || maxPostings > WorkdayPageRequest.MAX_OFFSET)
            throw new IllegalArgumentException("traversal bounds are invalid");
        this.maxPages = maxPages;
        this.maxPostings = maxPostings;
    }

    public Result traverse(WorkdaySource source) {
        Cursor cursor = cursor(source);
        List<Observation> observations = new ArrayList<>();
        while (true) {
            Step step = cursor.next();
            if (step instanceof Incomplete incomplete) return incomplete;
            Page page = (Page) step;
            observations.addAll(page.observations());
            if (page.complete()) return new Complete(observations);
        }
    }

    public Cursor cursor(WorkdaySource source) {
        return new Cursor(Objects.requireNonNull(source));
    }

    public final class Cursor {
        private final WorkdaySource source;
        private final Set<String> paths = new HashSet<>();
        private final Set<String> opaqueIds = new HashSet<>();
        private Integer firstTotal;
        private int offset;
        private int pageIndex;
        private int detailedCount;
        private int consumedRowCount;
        private long bufferedDescriptionChars;
        private Incomplete pendingFailure;
        private boolean terminal;

        private Cursor(WorkdaySource source) {
            this.source = source;
        }

        public Step next() {
            if (terminal) throw new IllegalStateException("Workday traversal was already consumed");
            if (pendingFailure != null) {
                terminal = true;
                return pendingFailure;
            }
            List<Observation> pageObservations = new ArrayList<>();
            WorkdayPageRequest request = new WorkdayPageRequest(offset, PAGE_SIZE);
            WorkdayFetchResult fetched = gateway.searchPage(source, request);
            if (fetched instanceof WorkdayFetchResult.Failure failure)
                return failOrPage(failure.kind().name(), pageObservations);
            if (!(fetched instanceof WorkdayFetchResult.SearchSuccess page)
                    || !source.equals(page.source()) || !request.equals(page.page()))
                return failOrPage("INVALID_RESPONSE", pageObservations);

            Integer reported = page.reportedTotal();
            if (pageIndex == 0) {
                if (reported == null) return failOrPage("INVALID_RESPONSE", pageObservations);
                firstTotal = reported;
                if (firstTotal > maxPostings)
                    return failOrPage("TRAVERSAL_LIMIT_EXCEEDED", pageObservations);
            } else if (reported != null && reported > 0 && !reported.equals(firstTotal)) {
                return failOrPage("INCONSISTENT_PAGINATION", pageObservations);
            }

            List<WorkdaySearchPosting> rows = page.postings();
            int returnedRowCount = page.returnedRowCount();
            if (rows.size() > returnedRowCount || returnedRowCount > PAGE_SIZE
                    || (returnedRowCount == 0 && consumedRowCount < firstTotal)
                    || consumedRowCount + returnedRowCount > firstTotal
                    || detailedCount + rows.size() > firstTotal)
                return failOrPage("INCONSISTENT_PAGINATION", pageObservations);
            if (detailedCount + rows.size() > maxPostings)
                return failOrPage("TRAVERSAL_LIMIT_EXCEEDED", pageObservations);

            for (WorkdaySearchPosting row : rows) {
                if (row == null || row.externalPath() == null || row.title() == null
                        || row.title().isBlank())
                    return failOrPage("INVALID_RESPONSE", pageObservations);
                WorkdayDetailRequest detailRequest;
                try { detailRequest = new WorkdayDetailRequest(row.externalPath()); }
                catch (IllegalArgumentException invalid) {
                    return failOrPage("INVALID_RESPONSE", pageObservations);
                }
                if (!paths.add(row.externalPath()))
                    return failOrPage("DUPLICATE_EXTERNAL_ID_DURING_TRAVERSAL", pageObservations);
                WorkdayFetchResult detailFetched = gateway.fetchDetail(source, detailRequest);
                if (detailFetched instanceof WorkdayFetchResult.Failure failure)
                    return failOrPage(failure.kind().name(), pageObservations);
                if (!(detailFetched instanceof WorkdayFetchResult.DetailSuccess success)
                        || !source.equals(success.source()) || !detailRequest.equals(success.request())
                        || !consistent(source, row, success.detail()))
                    return failOrPage("POSTING_MAPPING_FAILED", pageObservations);
                if (!opaqueIds.add(success.detail().opaqueId()))
                    return failOrPage("DUPLICATE_EXTERNAL_ID_DURING_TRAVERSAL", pageObservations);
                bufferedDescriptionChars += success.detail().descriptionHtml().length();
                if (bufferedDescriptionChars > MAX_BUFFERED_DESCRIPTION_CHARS)
                    return failOrPage("TRAVERSAL_LIMIT_EXCEEDED", pageObservations);
                pageObservations.add(new Observation(row, success.detail()));
                detailedCount++;
            }

            consumedRowCount += returnedRowCount;
            if (returnedRowCount < PAGE_SIZE) {
                if (consumedRowCount != firstTotal)
                    return failOrPage("INCONSISTENT_PAGINATION", pageObservations);
                terminal = true;
                return new Page(pageObservations, true);
            }
            pageIndex++;
            if (pageIndex == maxPages || offset > WorkdayPageRequest.MAX_OFFSET - PAGE_SIZE)
                return failOrPage("TRAVERSAL_LIMIT_EXCEEDED", pageObservations);
            offset += PAGE_SIZE;
            return new Page(pageObservations, false);
        }

        private Step failOrPage(String code, List<Observation> completed) {
            pendingFailure = new Incomplete(code, offset, detailedCount);
            if (!completed.isEmpty()) return new Page(completed, false);
            terminal = true;
            return pendingFailure;
        }
    }

    private static boolean consistent(WorkdaySource source, WorkdaySearchPosting row,
            WorkdayJobDetail detail) {
        if (detail == null || !detail.posted() || !detail.canApply()
                || detail.opaqueId() == null || detail.opaqueId().isBlank()
                || detail.title() == null || !row.title().strip().equals(detail.title().strip())
                || detail.descriptionHtml() == null || detail.descriptionHtml().isBlank()) return false;
        URI url = detail.externalUrl();
        if (url == null || !"https".equalsIgnoreCase(url.getScheme())
                || !source.endpointHost().equalsIgnoreCase(url.getHost())
                || url.getRawUserInfo() != null || url.getPort() != -1
                || url.getRawFragment() != null) return false;
        String prefix = source.endpointHost().endsWith(".myworkdaysite.com")
                ? "/recruiting/" + source.tenantKey() + "/" + source.siteKey()
                : "/" + source.siteKey();
        return (prefix + row.externalPath()).equals(url.getRawPath());
    }

    public sealed interface Step permits Page, Incomplete {}
    public record Page(List<Observation> observations, boolean complete) implements Step {
        public Page { observations = List.copyOf(Objects.requireNonNull(observations)); }
    }
    public sealed interface Result permits Complete, Incomplete {}
    public record Observation(WorkdaySearchPosting search, WorkdayJobDetail detail) {
        public Observation { Objects.requireNonNull(search); Objects.requireNonNull(detail); }
        @Override public String toString() { return "Observation[redacted]"; }
    }
    public record Complete(List<Observation> observations) implements Result {
        public Complete { observations = List.copyOf(Objects.requireNonNull(observations)); }
    }
    public record Incomplete(String failureCode, int offset, int detailedCount) implements Result, Step {
        public Incomplete { Objects.requireNonNull(failureCode); }
    }
}
