# JC-010 Phase 5 terminal QA

Date: 2026-09-21

Verdict: **COMPLETE AND FROZEN for V1 Beta.**

## Salesforce root cause and resolution

The production-size request to
`salesforce.wd12.myworkdayjobs.com / salesforce / External_Career_Site / en-US` returned HTTP 200 with 20
`jobPostings` array elements. Element index 4 was not a posting: its exact shape was one `bulletFields` property
containing one nonblank string, with no `title` or `externalPath`. The strict decoder correctly rejected it as a
posting, but incorrectly assumed every provider pagination slot was a posting.

Bounded read-only probes established that this marker consumes a provider pagination slot. Page 0 contained 19
fetchable postings plus the marker; the last fetchable page-0 path equalled the first path at offset 19, and offset
20 began the next provider slot. The marker appeared for first-page limits 5, 10, 19, and 20, but not at offsets 20
or 40. No response body, job identifier, description, path, or full URL was logged.

The production fix is deliberately narrow:

- recognize only a nonempty `bulletFields` array on an object with exactly that one property as a non-posting
  marker;
- continue rejecting empty markers, extra marker fields, invalid bullet elements, and every other partial row;
- carry the raw returned-row count through the gateway so traversal advances and proves completion using provider
  slots while fetching, mapping, and persisting only real postings.

No URL, source-confinement, detail, identity, duplicate, or general required-field validation was weakened.
Regression tests cover exact marker recognition/rejection and multi-page traversal without premature completion.

After the fix, the exact Salesforce live acceptance passed at page size 20: the observed run reported total 1480,
19 real first-page postings, 20 real second-page postings, a valid page-2 detail, same-host HTTPS confinement, and
stable repeated first-detail identity.

## Live configurations and acceptance

The earlier 2026-09-21 boundary matrix remains valid for:

| Host / tenant / site / locale | Result |
| --- | --- |
| `wd3.myworkdaysite.com` / `telesure` / `Wday` / `en-US` | PASS |
| `nvidia.wd5.myworkdayjobs.com` / `nvidia` / `NVIDIAExternalCareerSite` / `en-US` | PASS |
| `workday.wd5.myworkdayjobs.com` / `workday` / `Workday` / `en-US` | PASS |
| `salesforce.wd12.myworkdayjobs.com` / `salesforce` / `External_Career_Site` / `en-US` | PASS after marker fix |
| `salesforce.wd12.myworkdayjobs.com` / `salesforce` / `Futureforce_NewGradRoles` / `en-US` | PASS |

The matrix spans `myworkdayjobs.com` and `myworkdaysite.com`, data-center prefixes `wd3`, `wd5`, and `wd12`, two
sites under one tenant, production page size 20, multi-page behavior, detail decoding, and same-source URL
confinement. It does not establish a second locale, custom domain, or controlled provider lifecycle.

### Complete live synchronization and resynchronization

An explicitly gated acceptance test used the public Telesure board and a disposable Testcontainers PostgreSQL
database. It exercised the production gateway, all search pages and details, traversal, mapping, persistence,
complete-only reconciliation, and a second complete synchronization:

- first run: `SUCCEEDED`; discovered/created 43; closed 0;
- second run: `SUCCEEDED`; unchanged 43; created 0; updated 0; closed 0; reopened 0;
- all 43 `jobPostingInfo.id` values mapped to the same persisted listing IDs on both runs;
- `lastSuccessfulSyncAt` was populated; multi-page traversal and same-source URL confinement were enforced.

This is sanctioned complete live synchronization and immediate idempotent resynchronization. It is not lifecycle
identity evidence because no posting changed between the two runs.

## Identity and lifecycle conclusion

`jobPostingInfo.id -> external_job_id` is stable across repeated detail fetches on five live configurations and
across two complete 43-posting Telesure traversals/synchronizations. This is stronger whole-board repeatability,
but the two complete observations were immediate and the second run had zero provider-content changes.

No sanctioned controllable Workday environment or public posting under project control was available for a title,
location, description, or requisition edit; controlled close/reopen; repost; site transfer; or locale change.
Therefore identity reuse through a meaningful provider change/lifecycle remains unproven. Offline tests prove the
application preserves a supplied opaque ID through presentation edits and close/reopen, but cannot prove that
Workday itself reuses `jobPostingInfo.id`. No fallback identity was added.

### V1 Beta acceptance amendment

For V1 Beta, provider-controlled lifecycle identity validation is no longer a pre-freeze acceptance gate. It is a
documented post-launch external limitation because the missing experiment requires a sanctioned mutable Workday
tenant or posting that the project does not control and cannot safely simulate against a public employer board.
The amendment does not reinterpret unavailable evidence as a pass and does not weaken runtime validation.

The risk is acceptable for the Beta boundary because:

- five public configurations passed repeated search/detail identity observations across two Workday domains and
  three data-center prefixes;
- a complete 43-posting board was traversed and synchronized twice with the same 43 opaque external IDs and the
  same 43 persisted listing IDs, with zero creates, updates, closes, or reopens on the second run;
- offline lifecycle tests prove that, when Workday supplies the same opaque ID, presentation edits, disappearance,
  closure, and reappearance preserve application identity and reconcile only after complete traversal;
- incomplete traversal, partial persistence, provider failure, source isolation, lease fencing, and
  `lastSuccessfulSyncAt` behavior fail safely;
- no sanctioned observation contradicted `jobPostingInfo.id`, while `jobPostingId` and `jobReqId` remain less
  suitable presentation/human-facing alternatives.

The residual risk is explicit: Workday may issue a new opaque ID for an edit, repost, locale/site transfer, or
close/reopen lifecycle. In that case V1 Beta can represent the item as a new external listing rather than correlate
it with its earlier lifecycle. Provider-controlled lifecycle validation remains post-launch evidence to gather when
a sanctioned mutable tenant or naturally observable, auditable transition becomes available.

## Commands and results

All Maven commands used the repository wrapper and
`JAVA_HOME=C:\Program Files\JetBrains\IntelliJ IDEA 2026.2.1\jbr`.

```text
.\mvnw.cmd "-Djobcopilot.workday.live=true" "-Djobcopilot.workday.live.host=salesforce.wd12.myworkdayjobs.com" "-Djobcopilot.workday.live.tenant=salesforce" "-Djobcopilot.workday.live.site=External_Career_Site" "-Djobcopilot.workday.live.locale=en-US" "-Dtest=WorkdayPostingGatewayLiveAcceptanceTest" test
Before fix: FAIL — 1 test, HTTP 200 classified INVALID_RESPONSE
After fix:  PASS — 1 test, 0 failures, 0 errors, 0 skips

.\mvnw.cmd "-Djobcopilot.workday.live.sync=true" "-Djobcopilot.workday.live.host=wd3.myworkdaysite.com" "-Djobcopilot.workday.live.tenant=telesure" "-Djobcopilot.workday.live.site=Wday" "-Djobcopilot.workday.live.locale=en-US" "-Dtest=WorkdayJobSourceLiveSynchronizationAcceptanceTest" test
PASS — 1 test, 0 failures, 0 errors, 0 skips

.\mvnw.cmd -o test "-Dtest=WorkdayResponseDecoderTest,HttpWorkdayPostingGatewayTest,WorkdayTraversalTest,WorkdaySourceTest,WorkdayClientConfigurationTest"
PASS — 39 tests, 0 failures, 0 errors, 0 skips

.\mvnw.cmd -o test "-Dtest=WorkdayJobSourceSynchronizationIntegrationTest,LeverJobSourceSynchronizerTest,LeverJobSourceSynchronizationIntegrationTest,DiscoveryLeaseIntegrationTest,DiscoverySchedulingTest"
PASS — 63 tests, 0 failures, 0 errors, 0 skips

.\mvnw.cmd -o test "-Dtest=SchemaMigrationIntegrationTest,JobSourceApiIntegrationTest,WorkdayJc009ParityIntegrationTest,ApplicationReadinessIntegrationTest,Jc009DecisionReadinessEvaluationTest,ResumeUploadIntegrationTest"
PASS — 22 tests, 0 failures, 0 errors, 0 skips

.\mvnw.cmd verify
PASS — 1023 tests, 0 failures, 0 errors, 5 expected opt-in live-test skips; BUILD SUCCESS
```

The focused suites cover Workday HTTP/decoder/source boundaries, traversal/mapping, marker pagination,
synchronization, partial persistence, complete-only reconciliation, closure/reopen, failed-run
`lastSuccessfulSyncAt`, leases/heartbeat/fencing, source/provider isolation, Lever synchronization, schema/API
behavior, and JC-009 Lever/Workday parity.

Exactly one final `mvn verify` was run after the V1 Beta acceptance amendment. It passed and built the repackaged
application JAR. The five skips were explicitly gated live tests; their required Workday boundary and complete
synchronization variants had already passed in the separate sanctioned live commands above.

## Known non-blocking limitations

- The CXS interface is an observed public browser contract, not an officially versioned Workday API.
- Custom domains and a second locale were not accepted in this run.
- The Salesforce marker support is intentionally limited to the exact observed one-field, nonempty text-array shape.
- Live board counts can change independently while acceptance is running.
- Provider-controlled opaque-ID behavior through edit, close/reopen, repost, site transfer, and locale transition
  is unverified. For V1 Beta this is a documented external limitation, not a claim of proven stability.

## Final freeze decision

Salesforce compatibility is resolved; the live boundary matrix passes; complete live multi-page synchronization
and idempotent resynchronization pass; the accepted Beta identity evidence is satisfied; focused regressions are
green; and the single full `mvn verify` passes. Provider-controlled lifecycle identity validation is retained as
the explicit post-launch limitation above rather than being represented as evidence that was obtained.

Release/freeze decision: **JC-010: COMPLETE AND FROZEN for V1 Beta.**
