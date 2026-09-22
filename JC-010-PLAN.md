# JC-010: Workday ATS Job Discovery, Verification & Synchronization

Status: implementation-ready plan, based on repository inspection and live/public Workday surface validation on 2026-09-15. This document proposes no implementation.

## Executive verdict

**READY WITH AMENDMENTS.** The product goal is sound, and the JC-008 persistence, lease, reconciliation, extraction, verification, and JC-009 downstream workflow are strong foundations. Implementation must first remove three Lever leaks from that shared path: `JobSource.region`/API DTOs use `LeverRegion`, `DiscoverySyncTransactions.persistPage` accepts `LeverMappedListing`, and manual/scheduled orchestration injects `LeverJobSourceSynchronizer` directly. Those are required refactors, not permission to redesign discovery.

Workday is also not equivalent to Lever's documented postings feed. The observed browser-facing CXS surface is public but not represented as a supported public integration contract in Workday's REST API catalog. JC-010 must therefore ship against an explicitly versioned, fixture-backed observed contract, with multi-tenant live acceptance and a kill switch. A single successful tenant is not adequate launch evidence.

## Evidence and current-state findings

### Repository authority

- Flyway V4 establishes source identity, external listing identity, independent listing availability, sync runs/counters, database uniqueness, and one `RUNNING` run per source. V5 adds extraction fingerprints; V6 adds database-time leases and crash recovery. V7/V8 establish JC-009 readiness and candidate authority.
- `LeverJobSourceSynchronizer` owns complete traversal, duplicate detection, short page termination, page-at-a-time persistence, extraction, and terminal reconciliation. Provider calls occur outside transactions. `DiscoverySyncTransactions` fences every mutation by exact `(sourceId, runId)` authority.
- Reconciliation closes unseen listings only in `succeed`, after a complete duplicate-free traversal. Failed/limited/partial traversals leave unseen listings unchanged and do not advance `lastSuccessfulSyncAt`.
- `external_job_listings` enforces `(job_source_id, external_job_id)` uniqueness and a one-to-one listing-to-job relationship. Refresh/reopen preserves both listing and canonical `jobs.id`.
- Manual synchronization is synchronous and returns an audited failed run as HTTP 200 for expected provider/traversal failures. Conflicts and invalid sources remain 4xx. Scheduling is opt-in, bounded, non-queueing, lease-fenced, and has no immediate retry.
- Requirement extraction is driven from normalized canonical job content and its versioned fingerprint. Assessment/ranking are deterministic, on-demand, and not persisted.
- JC-009 live opportunities select provider-neutral `LIVE`, extraction-current listings joined to jobs in `DISCOVERED`/`SHORTLISTED`, then reuse the same matcher, decision, readiness, candidate revision, preference, and resume-route authority. Readiness requires a live/fresh verified listing and valid application URL; it does not inspect provider.
- Existing tests cover malformed boundaries, paging, partial traversal, duplicate IDs, closure/reopen, idempotency, extraction failure, leases, recovery, same-source races, independent-source progress, API behavior, and provider-neutral opportunity flow. `JC-008-PHASE5-QA.md` records a clean offline release but explicitly says real Lever acceptance was not run.

### Workday surface: facts versus observations

Authoritative Workday documentation says a tenant can have multiple external career sites (including sites for different locations/populations), multiple languages, an optional custom report instead of Find Jobs, custom/embedded domains, and an option excluding the site from third-party indexing. It also distinguishes authenticated Workday REST APIs from external career sites. Sources: [Create External Career Sites](https://doc.workday.com/admin-guide/en-us/human-capital-management/recruiting/career-sites/san1394588983205.html), [Career Sites](https://doc.workday.com/workday-education/en-us/course-manuals/recruiting-for-administrators/career-sites.html), and [Workday REST API Explorer](https://developer.workday.com/rest-api-explorer).

Observed, not guaranteed, on two public tenants:

- Search: `POST https://{host}/wday/cxs/{tenant}/{site}/jobs` with JSON containing `appliedFacets`, `limit`, `offset`, and `searchText` returned `total`, `jobPostings`, tenant-specific `facets`, and `userAuthenticated`.
- Search rows exposed `title`, `externalPath`, `locationsText`, `postedOn`, optional remote-type-like data, and `bulletFields`; they did not contain the full description or a clearly contractual immutable ID.
- Detail: `GET https://{host}/wday/cxs/{tenant}/{site}{externalPath}` returned `jobPostingInfo` with an opaque `id`, title, HTML description, location, requisition ID, posting ID/site ID, flags, and external URL. Optional fields and vocabulary varied.
- Workday's tenant had 375 jobs and WFP had 98 at observation time. Facet names differed (`remoteType` versus `isRemote`; different location and job-family keys), proving tenant configuration must not drive the canonical decoder.
- Public HTML examples also show multiple locations, relative posted labels, optional end dates, and differing host families such as `*.myworkdayjobs.com` and `*.myworkdaysite.com`.

These observations define the discovery hypothesis only. They are not a promise from Workday and must be revalidated before and during implementation.

## Architectural decisions

1. **One synchronization kernel, provider adapters at the edge.** Extract the existing orchestration into a provider-neutral `JobSourceSynchronizer`/traversal kernel. Lever and Workday supply page traversal plus canonical mapped listings. Do not copy leases, page persistence, extraction, reconciliation, counters, scheduler, or controllers.
2. **One canonical mapped-listing contract.** Rename/move `LeverMappedListing` to a provider-neutral `MappedExternalJobListing` without semantic change. It continues to carry external identity, digest, URLs, canonical `DiscoveryJobWriter.Projection`, and extraction fingerprint.
3. **Explicit provider dispatch.** Add a registry/dispatcher keyed by `JobSourceProvider`; reject missing/duplicate adapters at startup. `JobSourceService` and scheduled coordination depend on the dispatcher, never on Lever or Workday directly. Provider failure is caught and terminalized per source/run.
4. **Configuration is provider-specific; persistence and workflow are not.** A Workday source identity is at least normalized public host + tenant + external site + locale. It is not merely a company slug. Keep a discriminated API contract and database checks rather than a free-form URL or opaque JSON configuration.
5. **Allowlisted endpoint construction.** Never accept a caller-supplied path, scheme, port, credentials, query, or arbitrary base URL. Accept/canonicalize fields, allow only HTTPS and approved Workday public host suffixes/configured test override, then construct paths with segment encoding. This is an SSRF and tenant-confusion boundary.
6. **Offset traversal with total as a consistency signal, not closure authority.** Continue until an empty/short page under a frozen termination contract; validate monotonic offset, bounded pages/jobs, and sane `total`. Any total drift, duplicate, repeated page, missing detail, or inconsistent path fails the run and prohibits reconciliation. Do not silently trust `total` or use it alone to declare completion.
7. **Two-stage Workday fetch.** Search rows enumerate detail paths; each detail is fetched and decoded before its listing can be observed. No detail failure may be skipped. Bounded sequential fetch is the V1 default; parallel detail fetching is out of scope until rate/ordering behavior is proven.
8. **No hidden retries.** One HTTP attempt per search page and one per detail. Redirects disabled. A later scheduled run is recovery, not a retry. No retry-after sleep inside a run.
9. **Opaque posting identity is preferred, but gated by evidence.** Tentatively use detail `jobPostingInfo.id` as `external_job_id`, because requisition IDs and URL slugs may be reused/changed and one requisition may have postings per site. Do not freeze this until the live acceptance matrix validates stability across refresh, repost, locale, site, and path changes. Source identity already scopes the ID.
10. **Canonical URLs come from validated provider data.** Prefer a validated `externalUrl` for hosted/apply URL when it resolves to the configured source identity. If the public detail page is itself the apply entry, both existing URL fields may intentionally hold that canonical HTTPS page. Never synthesize an apply endpoint that was not observed and tested.
11. **HTML normalization is deterministic and shared where correct.** Decode entities, sanitize/convert allowed structural HTML to stable plain text using jsoup, preserve section/list boundaries, normalize whitespace, cap sizes, and reject unusable descriptions. Do not interpret tenant-defined facets as skills or requirements.
12. **JC-009 remains untouched in policy.** Workday jobs enter the same `jobs`/listing tables and therefore the same extraction, assessment, ranking, decision, readiness, and opportunity reads. Provider metadata may be projected for observability, but it must not influence scores, ranks, or readiness.

## Migration strategy

Create a single additive Flyway `V9__add_workday_job_sources.sql`; do not edit V4-V8.

Recommended source configuration shape:

- Add `WORKDAY` to `job_sources_provider_check`.
- Replace the Lever-only meaning of `region` in Java/API with a provider-neutral nullable `routing_region` value while preserving stored `GLOBAL`/`EU` values and response compatibility for Lever. Prefer a new discovery enum (`GLOBAL`, `EU`) rather than importing `LeverRegion` into the entity. Workday leaves this null.
- Add nullable `endpoint_host VARCHAR(255)`, `tenant_key VARCHAR(100)`, `site_key VARCHAR(100)`, and `locale VARCHAR(35)` (BCP-47-style canonical form; default chosen explicitly, not inferred per request).
- Keep `source_key` for compatibility. For Workday either set it to a canonical display/lookup key derived from tenant + site, or deprecate it in the request in favor of explicit fields. Do not ask clients to encode host/tenant/site into an undocumented delimiter string.
- Replace the global identity constraint with provider-scoped unique indexes: Lever on `(provider, routing_region, source_key)` and Workday on `(provider, endpoint_host, tenant_key, site_key, locale)`. Add provider-shape checks requiring exactly the appropriate fields, canonical lowercase ASCII host/tenant/site, supported locale syntax, no controls, no scheme/path/query/port, and approved host suffix enforcement in application validation. PostgreSQL constraints should enforce shape; application code owns the evolvable suffix allowlist.
- Preserve all existing IDs, listings, jobs, runs, timestamps, and Lever source responses. Backfill/rename in one migration without rewriting business timestamps.
- Do not add Workday listing tables, provider-payload blobs, a second run table, or assessment/ranking columns.

If API backward compatibility forbids changing the current `region` field type, introduce a new provider-neutral source configuration object while continuing to serialize Lever's legacy `region`; do not make Workday invent `GLOBAL` or `EU`.

Migration verification must cover clean V1→V9, V8→V9 with real-shaped Lever rows/runs/listings, constraint rejection for cross-provider/mixed shapes, duplicate Workday identities, case/whitespace collisions, rollback-by-restore documentation, and Hibernate `ddl-auto=validate`.

## Phase 1 — Workday network boundary and source contracts

### Scope and objective

Establish safe Workday source configuration and a persistence-free connector for exactly one search page or one job detail. Freeze only fields demonstrated by fixtures/live tenants; tolerate unknown optional fields while rejecting ambiguous required structure.

### Exact components/files likely affected

- Modify: `JobSourceProvider.java`, `JobSource.java`, `JobSourceRepository.java`, `JobSourceService.java`, `JobSourceApiExceptions.java`, `CreateJobSourceRequest.java`, `JobSourceResponse.java`, `application.yml`, `README.md`, schema migration tests.
- Add under `discovery/workday`: `WorkdaySource`, `WorkdayEndpointResolver`, `WorkdayPageRequest`, `WorkdaySearchPosting`, `WorkdayJobDetail`, `WorkdayFetchResult`, `WorkdayPostingGateway`, `HttpWorkdayPostingGateway`, `WorkdayResponseDecoder`, `WorkdayClientProperties`, `WorkdayClientConfiguration`.
- Add `V9__add_workday_job_sources.sql` and Workday boundary fixtures/tests mirroring the useful Lever boundary tests.

### Data/schema changes

Apply the V9 source configuration and uniqueness strategy above. No listing/job/run schema change.

### Key invariants

- Network classes never load or mutate entities and never start transactions.
- Search/detail calls each perform exactly one bounded request; no redirects, retries, cookies, auth, or browser automation.
- Connect/request timeouts, response-byte limits, JSON depth/string/collection limits, and maximum postings are explicit and separately configurable from Lever.
- Exact content type policy is tolerant of valid JSON media types/charset but never decodes HTML challenge pages as JSON.
- Strict duplicate JSON keys and trailing-token rejection; unknown optional fields tolerated; required node types validated.
- Endpoint resolver encodes path segments and prevents traversal, alternate hosts, userinfo, ports, fragments, and query injection. Logs omit full URLs/query/payloads and provider bodies.
- Failure taxonomy maps 404, 408, 429, 5xx, timeout, connection, interruption, oversized, malformed, and invalid responses to existing public-safe codes where semantics match. Add a code only when operators can act differently on it.

### Implementation sequence

1. Implement V9 and the provider-neutral source configuration type/API validation.
2. Freeze captured search/detail fixtures from at least three structurally different tenants, sanitized only for irrelevant volatile content.
3. Implement source and endpoint value objects, then request/response DTOs and strict decoders.
4. Implement separately bounded search POST and detail GET gateway calls using an OkHttp client with retry and redirect disabled.
5. Add configuration validation and redacted structured logs.

### Tests

- Source canonicalization, Unicode/control/length boundaries, locale handling, host allowlist, encoded slashes/dot segments, mixed provider config, duplicate DB identities, and concurrent duplicate creation.
- Exact search request method/body/headers; exact detail path; one request only; redirects fail; 404/408/429/5xx classification; timeout/interruption; truncated/chunked/zero-read/empty/null bodies; wrong content type; oversized body.
- Strict duplicate keys/trailing JSON; wrong root/types; missing/null/blank required fields; huge/negative/non-integral `total`; malformed/duplicate paths; unknown fields; tenant-specific facets ignored; malicious HTML/URLs; optional-field permutations.
- Assert no repository interaction or transaction is active during gateway calls.

### Exit criteria

- V9 passes clean and upgrade migration tests without changing Lever behavior.
- Offline contract suite passes for multiple tenant fixtures.
- Every network path is one-shot, bounded, SSRF-safe, redacted, and persistence-free.
- The identity decision remains explicitly marked provisional until Phase 5 live validation.

### Risks and unresolved decisions

- CXS is an observed web-app endpoint, not a documented supported external API; Workday may change it or block access.
- Confirm supported host families/custom domains and whether customers must opt in/authorize Job Copilot.
- Confirm locale placement in URL and whether locale changes identity or only representation.
- Confirm maximum accepted `limit`, total semantics, rate limits, bot/WAF behavior, and required headers.

## Phase 2 — Workday discovery, mapping, and normalization

### Scope and objective

Traverse search pages and details into the existing canonical job/listing contract with stable identity, deterministic content, and no provider vocabulary leaking into scoring.

### Exact components/files likely affected

- Rename/move `LeverMappedListing.java` to `MappedExternalJobListing.java`; update `LeverPostingMapper.java`, `DiscoverySyncTransactions.java`, and their tests without semantic drift.
- Add `WorkdayPostingMapper`, `WorkdayDescriptionAssembler`, and a Workday traversal adapter under `discovery/workday`.
- Reuse `ProviderContentDigest`, `ExtractionFingerprint`, `DiscoveryJobWriter`, and the released requirement extractor.

### Data/schema changes

None beyond V9. Do not persist raw payloads or facet taxonomies in V1.

### Key invariants

- `external_job_id` is nonblank, canonical, <=255 characters, source-scoped, and stable by the accepted Phase 5 rule.
- Company comes from configured authoritative `companyName`, not mutable payload branding.
- Title, location, description, hosted URL, apply URL, source label, and ID map into the same `DiscoveryJobWriter.Projection` used by Lever.
- Description assembly is deterministic, safe plain text, semantically complete enough for the existing extractor, and capped before persistence.
- Digests include every provider field whose change should update canonical job/listing content; fingerprints continue to include extractor version + normalized description.
- A search row is not "seen" until its corresponding detail is successfully fetched, decoded, identity-checked, and mapped. One bad row fails the traversal; none are silently dropped.
- Search/detail title/path/ID mismatches, detail redirects, `posted=false`, `canApply=false`, or invalid external URL are handled by an explicitly tested rule. Conservative default: fail inconsistent traversal; treat an explicitly non-posted detail as absent only after live evidence proves this race is normal and safe.

### Implementation sequence

1. Make the mapped-listing type provider-neutral with characterization tests proving byte-for-byte equivalent Lever mapping/digests.
2. Decide and document Workday identity from live evidence; build search-detail correlation checks.
3. Implement HTML normalization and canonical field mapping.
4. Implement bounded offset traversal that collects mapped listings and duplicate identity/path evidence.
5. Feed mapped values through the existing page persistence/extraction boundary.

### Tests

- Golden mappings for at least: multiple locations, `locationsText` count requiring detail location, remote/hybrid/onsite vocabularies, missing remote type, translated content, entity-heavy/nested/malformed HTML, lists/headings, very long descriptions, optional dates/end dates, missing requisition ID, distinct opaque ID, changed slug, and same requisition across sites.
- Digest stability under irrelevant facet/order changes; digest changes under canonical changes; extraction fingerprint stability; XSS/script/style removal and whitespace determinism.
- Search-detail mismatch, duplicate opaque IDs within/across pages, repeated paths, detail 404/race, inconsistent totals, empty middle page, full final page followed by empty page, and traversal caps.
- Regression: all Lever mapper/assembler/synchronizer fixtures retain existing canonical values, counter meanings, and extraction behavior.

### Exit criteria

- Workday fixture traversals create the same canonical domain shape as Lever.
- No Workday type appears in transaction, job, matching, ranking, application, or readiness packages.
- Identity, URL, HTML, and termination policies are documented and covered by adversarial tests.

### Risks and unresolved decisions

- Opaque `id` versus `jobPostingId`/`jobReqId` stability and repost behavior.
- Whether `externalUrl` is always the valid apply entry, whether source-tracking query parameters must be preserved, and whether URLs vary by locale.
- Multi-location detail may expose only a primary location while UI/search says “N Locations”; V1 must not invent locations or treat facets as canonical detail.

## Phase 3 — Shared synchronization, reconciliation, lifecycle, and recovery

### Scope and objective

Generalize the released Lever orchestration minimally so either provider runs through the same lease-fenced transaction kernel, with Workday failures isolated and incomplete traversals unable to close jobs.

### Exact components/files likely affected

- Refactor: `LeverJobSourceSynchronizer.java` into a provider-neutral orchestration/kernel plus thin Lever adapter; add `JobSourceSynchronizer`, `JobSourceSyncAdapter`/registry or equivalent.
- Modify: `DiscoverySyncTransactions.java`, `JobSourceSyncHeartbeat.java` only as needed for generic mapped type; `JobSourceService.java`, `ScheduledJobSourceSyncCoordinator.java`, `JobSourceSyncScheduler.java`, `DiscoveryConfiguration.java`, public failure allowlist/logging.
- Extend existing synchronization, integration, lease, scheduling, and persistence tests; add Workday synchronization integration tests.

### Data/schema changes

No new tables. The V4 partial unique run index, source/listing identities, and V6 lease remain authoritative.

### Key invariants

- Exactly one `RUNNING` run per source is database-enforced; different sources/providers progress independently.
- Provider I/O occurs outside long transactions. `start`, lease renewal, page persistence, extraction, success/reconciliation, and failure terminalization remain short `REQUIRES_NEW` units.
- Every canonical mutation locks and verifies exact run authority and unexpired database-time lease. A recovered/abandoned worker cannot persist, extract, reconcile, fail, or succeed.
- Only a complete, bounded, duplicate-free, internally consistent traversal calls `succeed`; only that transaction closes unseen listings and advances source success time.
- Failed/partial/detail-incomplete/limit-exceeded/interrupted/lease-lost runs never close unseen listings or advance success time. Already persisted observed rows may remain updated, matching JC-008 semantics.
- Reopen preserves job/listing IDs and user-owned `JobStatus`. Provider closure only changes listing availability; it never overwrites applied/interview states.
- No immediate or hidden retry. One source's Workday failure does not cancel, delay beyond bounded executor occupancy, or mutate another source/provider's run.
- Counters retain JC-008 definitions; `discovered` counts fully mapped observations, not search summaries. Document detail-call metrics separately in logs rather than changing persisted counter semantics unless operational evidence requires it.

### Implementation sequence

1. Add characterization tests around current Lever orchestration and transaction method behavior.
2. Generalize mapped input and provider validation; make transaction messages provider-neutral.
3. Extract shared run/lease/page/extraction/reconciliation control flow. Plug Lever back in first and pass every JC-008 test unchanged.
4. Add explicit provider adapter registry/dispatch for manual and scheduled runs.
5. Add Workday adapter and failure isolation, then multi-provider integration tests.

### Tests

- All current JC-008 tests unchanged or mechanically renamed with identical assertions.
- Workday: first sync, unchanged sync, content update, closure, reopen, empty complete source, multi-page, changed order, detail failure after earlier pages, search failure, malformed final page, total growth/shrink, duplicate/repeated page, max pages/jobs, interruption, extraction failure, and terminalization failure.
- Concurrency: two manual callers; manual versus scheduled; two schedulers; same Workday source; two Workday sources; Workday and Lever simultaneously; expired recovery during provider I/O; heartbeat wins/recovery wins; stale worker fenced from all mutation paths; duplicate listing insertion race.
- Failure isolation: Workday 429/timeout/oversize does not change Lever rows/runs or prevent an eligible Lever task from completing; executor saturation remains bounded and non-queueing.
- Transaction spy/integration proof that sockets are never called in an active DB transaction and locks are not held during search/detail calls.

### Exit criteria

- Full JC-008 regression suite passes with unchanged Lever external behavior.
- Shared kernel contains no provider-specific decoding/mapping/endpoint logic.
- Every incomplete Workday scenario proves zero false closures and unchanged `lastSuccessfulSyncAt`.
- Multi-provider race/isolation tests pass against PostgreSQL/Testcontainers.

### Risks and unresolved decisions

- Sequential detail calls make Workday runs much longer than Lever runs; lease timeout/heartbeat must cover bounded worst-case traversal without increasing transaction duration.
- Scheduler fairness with long Workday runs may require per-provider operational tuning, but not a second scheduler. Increase bounded capacity only from measured acceptance data.

## Phase 4 — API and JC-009 workflow integration

### Scope and objective

Expose Workday through the existing `/api/job-sources` surface and prove that discovered jobs flow through candidate confirmation, assessment, ranking, decisions, readiness, and opportunities without provider branches.

### Exact components/files likely affected

- Modify discovery request/response validation and API docs: `JobSourceController.java`, `JobSourceService.java`, DTOs, `ApiErrorHandler` only if a new validation error needs mapping.
- Test, but avoid production changes unless evidence finds a bug: `JobAssessmentService`, `JobRankingService`, `LiveOpportunityReadService`, `LiveOpportunityService`, `JobVerificationService`, `ApplicationDecisionService`, `ApplicationReadinessService`, candidate/preference/route services and controllers.
- Update `README.md` with Workday configuration, examples, failure semantics, gating, and operational limitations.

### Data/schema changes

None beyond V9. API source configuration becomes provider-discriminated while existing Lever payload/response remains compatible.

### Key invariants

- Existing source/listing/run endpoints, pagination, sort, enable/disable, manual sync, and safe failure-code behavior remain stable.
- Provider is visible as metadata, never a scoring/ranking/readiness input.
- Workday `LIVE` + extraction-current jobs enter opportunity eligibility exactly like Lever; closed/stale/unready jobs do not.
- Candidate profile ID/revision, confirmed authority, preference freshness, resume strategy/routes, matcher version, decision policy, and readiness checks remain unchanged.
- A job linked to a Workday listing has exactly one verification snapshot under the current one-listing-per-job model. Cross-provider deduplication is explicitly out of scope.

### Implementation sequence

1. Add Workday create/list/get/enable/sync/run/listing API contracts and validation.
2. Run an end-to-end fixture flow: Workday source → sync → extraction → confirmed candidate → assessment → ranking → decision/readiness → opportunities.
3. Add negative lifecycle/freshness flows and cross-provider combined opportunity ordering.
4. Update README/migration table/configuration/troubleshooting and preserve Lever examples.

### Tests

- API: valid/invalid provider-discriminated requests, unknown provider, mixed fields, duplicate identity, disabled source, invalid stored source, already-running, failed run HTTP semantics, safe unknown failure code, pagination/sorting/filtering.
- JC-009: same job/candidate snapshots from Lever and Workday yield identical match, recommendation, decision, and readiness (aside from listing/source metadata); deterministic global ranking tie-break remains job-based; candidate DRAFT/revision conflicts; missing facts/preferences/routes; stale verification; closed listing; invalid apply URL; extraction pending/stale/unavailable.
- Combined sources: one failing Workday sync leaves existing Lever opportunities intact; closing Workday affects only those listings; no N+1/provider lookup is introduced into ranking/opportunity reads.
- Golden evaluation: add provider-origin permutations only where they prove neutrality; do not alter released expected scoring metrics.

### Exit criteria

- Public API contract is documented with backward-compatible Lever behavior.
- Full JC-009 evaluation and integration suites pass unchanged, plus cross-provider parity cases.
- Production candidate/matching/ranking/decision/readiness code has no Workday branch.

### Risks and unresolved decisions

- A discriminated request is cleaner but may change JSON shape. Decide compatibility policy before coding; a transitional flat Lever shape plus explicit Workday fields is acceptable, an ambiguous universal `sourceKey` is not.
- Current one-listing-per-job uniqueness means the same real-world opening discovered from Lever and Workday becomes two jobs. Solving cross-source entity resolution is risky and outside launch scope.

## Phase 5 — Real Workday acceptance, hardening, regression, and terminal QA

### Scope and objective

Validate observed contracts across representative real tenants, prove production safety under drift/failure, and make Workday a V1 launch gate without making normal tests network-dependent.

### Exact components/files likely affected

- Add `WorkdayPostingGatewayLiveAcceptanceTest` and optionally a separately named live synchronization acceptance test that targets a disposable database/source.
- Extend `README.md`, CI profile documentation, and create `JC-010-PHASE5-QA.md` with commands, environment, tenant matrix, outcomes, limitations, and freeze recommendation.
- Adjust only evidence-driven decoder/config/limits; no opportunistic refactors.

### Data/schema changes

None expected. Any new migration discovered here requires returning to migration review; do not patch production schema ad hoc.

### Key invariants

- Normal `mvn test`/`verify` never calls Workday. Live tests require both an explicit boolean gate and explicit tenant configuration; absent gates skip, never fail or call defaults.
- Live logs/artifacts contain no response bodies, credentials, candidate data, query secrets, or source-tracking tokens.
- Acceptance is read-only to Workday. Any DB-backed live sync uses disposable/local test data and cannot target production implicitly.
- No live test asserts volatile counts, titles, or job IDs. It asserts structural validity, bounded traversal/sample details, URL/source consistency, and at least one usable mapping where the tenant is nonempty.
- Workday launch is blocked if the multi-tenant matrix is not executed successfully; a skipped live suite is not acceptance.

### Live acceptance matrix

At minimum, test 5–8 approved public sites spanning:

- `myworkdayjobs.com` and `myworkdaysite.com`, at least two data-center host prefixes, and any explicitly supported custom-domain form.
- Small, multi-page, and >100-job sites.
- Multiple external sites under one tenant and at least one multi-language site/locale.
- Multi-location jobs, remote/hybrid/onsite variants, HTML-rich descriptions, optional/missing fields, and tenant-defined custom facets/reports.
- One empty site if available and one controlled invalid/closed job path.

For each site record: configured host/tenant/site/locale; search request acceptance; maximum working page size; total/offset behavior; duplicate/path behavior; detail availability; identity candidates; URL behavior; optional-field set; latency/rate-limit/WAF observations; and authorization/robots/customer permission status. Obtain owner/legal/product confirmation that consumption of browser-facing endpoints is acceptable for V1.

Identity validation must observe the same postings across at least two runs and, where feasible, a controlled posting edit/closure/reopen/repost. If opaque `id` stability cannot be established, JC-010 is not ready to launch; do not guess a composite ID.

### Tests and terminal QA

1. Focused Workday unit/boundary/mapping suites.
2. Shared discovery persistence/lease/scheduler/API suites.
3. Full `mvn verify` from a clean database with Testcontainers.
4. JC-009 golden/evaluation suite and explicit Lever-vs-Workday parity dataset.
5. Live matrix, manually gated in a dedicated CI job/environment, with zero automatic retry.
6. Soak repeated scheduled runs across multiple sources; inject 404, 429, timeout, disconnect, malformed/oversized search/detail, slow detail, process death, lease expiry, DB deadlock/constraint failure, and executor saturation.
7. Query DB invariants after failure: no duplicate identities, at most one running source run, terminal lease null, monotonic timestamps, no unseen closure on failed run, no success timestamp advance, stable job/listing IDs, independent provider/source state.
8. Review structured logs for source/run/provider attribution, bounded failure code, duration/counts, redaction, and absence of bodies/full URLs.

### Exit criteria

- All offline and full regressions pass from clean and V8-upgraded databases.
- Live matrix passes on the exact launch configuration with recorded evidence; no skips count as passes.
- At least one complete two-page Workday sync and a subsequent idempotent sync succeed; controlled failure proves no false closure; controlled disappearance/reappearance proves close/reopen if a sanctioned test tenant is available.
- Lever live acceptance is also run before V1 because JC-008 never completed that external gate; otherwise "preserve Lever" lacks terminal production evidence.
- Operational kill switch (disable source and global scheduling), timeouts, caps, dashboards/log queries, and runbook are reviewed.
- `JC-010-PHASE5-QA.md` recommends release/freeze with all limitations explicit.

### Risks and unresolved decisions

- Workday can change or restrict CXS without versioning; launch needs monitoring and a rapid disable path.
- Robots terms, customer authorization, indexing exclusions, WAF/cookie requirements, and regional/privacy obligations need non-code approval.
- Reliable controlled close/reopen testing may require a partner sandbox tenant. Without it, lifecycle evidence remains incomplete and must be accepted explicitly or launch blocked.

## Necessary technical-debt refactors exposed by the second ATS

Required for JC-010 only:

- Replace `LeverRegion` in `JobSource`, repository, and public DTOs with provider-neutral configuration while preserving Lever wire behavior.
- Rename `LeverMappedListing` to the shared mapped-listing contract.
- Generalize `DiscoverySyncTransactions.validateProvider` and error text; transactions should validate run/source authority, not choose a provider.
- Extract the orchestration kernel and provider registry so manual and scheduled paths dispatch identically.
- Make log messages provider-valued rather than hard-coded `Lever`/`LEVER`.
- Move the public failure-code allowlist to a shared taxonomy or exhaustive mapping so a new adapter cannot accidentally leak internal codes.

Not justified now:

- Rewriting repositories, replacing JPA/JDBC, changing lease design, persisting raw provider payloads, event sourcing, async manual API, distributed queues, per-provider schedulers/run tables, assessment persistence, cross-provider job deduplication, or a universal plugin framework.

## Proposed final JC-010 scope

JC-010 delivers configurable public Workday external-career-site sources; bounded search/detail discovery; canonical mapping and extraction through the existing job domain; shared lease-fenced manual/scheduled synchronization; complete-only reconciliation; existing source/listing/run APIs; provider-neutral JC-009 assessment/ranking/decision/readiness/opportunity behavior; multi-tenant gated live acceptance; README/runbook/terminal QA. It includes only the minimal Lever-to-shared refactors listed above.

## Final phase breakdown

1. V9 source identity/configuration plus persistence-free Workday search/detail boundary.
2. Shared mapped contract, Workday traversal, HTML normalization, identity/digest mapping.
3. Provider-neutral synchronization kernel and dispatch, preserving JC-008 lease/reconciliation semantics.
4. Existing API and JC-009 end-to-end integration/parity.
5. Multi-tenant live acceptance, fault/soak/regression testing, operational hardening, and freeze QA.

Phase 2 must not freeze identity before Phase 1 fixtures and early live probes; Phase 3 must re-land Lever on the shared kernel before Workday is plugged in; Phase 5 may send identity/contract issues back to Phase 1/2.

## Test/QA strategy summary

Use four lanes: deterministic fixture boundary tests, PostgreSQL concurrency/reconciliation integration tests, provider-neutral JC-009 parity/golden tests, and explicitly gated live tests. Every failure lane asserts database aftermath, not only returned status. Full regression includes JC-006/007/008/009; Workday tests may not weaken existing assertions or replace real provider evidence with mocks.

## Risks and unknowns that block unconditional readiness

1. No documented supported public Workday job-feed contract was found; CXS compatibility and permitted use need confirmation.
2. Stable Workday posting identity across edit/repost/site/locale is not yet proven.
3. Detail/apply URL and closure-race semantics are not proven across tenants.
4. Supported host/custom-domain/locale matrix and tenant-specific access controls are unresolved.
5. Performance/rate-limit behavior of N+1 detail calls is unmeasured.
6. Lever itself lacks recorded real-provider terminal acceptance from JC-008.

## Explicit out of scope

- Authenticated Workday Recruiting REST/SOAP, RaaS, tenant admin setup, internal career sites, candidate sign-in, application submission, Quick Apply, questionnaires, application tracking, or candidate-data ingestion.
- Browser automation, CAPTCHA/WAF bypass, credential/cookie storage, hidden retries, proxy rotation, or scraping sites that disallow access.
- Cross-source/company deduplication, merging Lever and Workday jobs, historical payload storage, change event streams, persisted assessments/rankings, new scoring/AI logic, facet-based skill inference, compensation parsing, or richer location modeling.
- Per-provider databases, schedulers, controllers, run models, listing models, or candidate workflows.

## V1 launch impact

Workday is a mandatory V1 dependency, so V1 release requires Phase 5's live matrix, stable identity evidence, legal/product authorization, and a production kill switch. Workday failure must degrade only its source runs; existing Lever discovery and stored opportunities remain available. If CXS access is not supportable or stable, the correct outcome is to block V1 or secure a supported Workday/customer feed—not to relax decoding, skip failed details, infer closures, or ship against one convenient tenant.

## Verdict

**READY WITH AMENDMENTS**: begin implementation only after accepting the V9 configuration model, the shared-kernel refactor boundary, and the fact that stable identity plus permitted multi-tenant CXS access are launch gates. If stakeholders require “READY TO IMPLEMENT” to mean zero external-contract uncertainty, the present state is **NOT READY** until a sanctioned tenant matrix and identity experiment are completed. The repository itself is ready for the proposed implementation; the external Workday contract is the remaining conditional dependency.

## Implementation handoff (requested final form)

1. **Proposed final JC-010 scope:** Public external Workday career-site discovery through the existing source/listing/job domain, shared synchronization and reconciliation, current APIs and JC-009 workflow, gated multi-tenant live acceptance, and only the necessary provider-neutral refactors.
2. **Phase breakdown:** (1) source schema/configuration and network boundary; (2) traversal, identity, mapping, normalization; (3) shared synchronization, lifecycle, recovery; (4) API and JC-009 parity; (5) live acceptance, hardening, full regression, terminal QA.
3. **Architectural decisions:** One synchronization kernel, explicit adapter registry, one mapped-listing contract, provider-specific validated configuration, two-stage one-shot Workday fetch, complete-only reconciliation, no provider logic downstream.
4. **Migration strategy:** Add V9 only; preserve V1-V8 and all data; add WORKDAY plus checked Workday locator columns; replace the Lever-only region type leak; enforce provider-shaped configuration and provider-scoped uniqueness in PostgreSQL.
5. **Test/QA strategy:** Fixture contract/adversarial tests, Testcontainers persistence/concurrency/failure aftermath, unchanged JC-006/007/008/009 regression and parity, then gated live and soak/fault testing.
6. **Live Workday acceptance strategy:** Explicit opt-in, 5–8 sanctioned diverse sites, repeat observations, identity/edit/close/reopen evidence where possible, structural rather than volatile assertions, recorded QA artifact, no retries or writes to Workday.
7. **Risks/unknowns:** Undocumented CXS compatibility/permitted use, identity stability, URL/closure races, host/locale/custom-site variation, N+1 detail cost/rate limits, and missing historical Lever live acceptance.
8. **Explicit out-of-scope items:** Authenticated Workday integrations and applications, bypass techniques, cross-source dedupe, raw payload/history, new scoring, richer facet inference, and parallel provider infrastructure.
9. **V1 launch impact:** Workday is a hard gate. Failure remains source-isolated, but V1 must not launch without the live matrix, stable identity evidence, authorization, observability, and kill switch.
10. **Verdict:** **READY WITH AMENDMENTS**. Repository architecture supports implementation after the named minimal refactors; external Workday contract validation remains a mandatory launch gate.
