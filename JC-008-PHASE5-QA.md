# JC-008 Phase 5 Terminal Adversarial QA

Date: 2026-09-13

## Verdict

**PASS WITH FIXES**

JC-008 is suitable for release and freeze after the fix recorded below. No open blocking correctness defect remains.

## Findings

### P2 — Legacy running rows could remain unrecoverable after V5 → V6 under clock skew

- **Root cause:** V6 initialized `lease_expires_at` from the legacy row's `started_at`. A future-dated legacy start timestamp could therefore produce a lease that PostgreSQL considered unexpired after migration, contrary to the promise that all pre-lease `RUNNING` rows become immediately recoverable.
- **Affected invariant:** V6 migration; multi-instance/crash recovery; database-time lease ownership.
- **Fix:** V6 now initializes every legacy `RUNNING` lease with PostgreSQL `clock_timestamp()` instead of application-originated historical time.
- **Regression test:** `SchemaMigrationIntegrationTest.populatedV5SchemaAddsLeaseAndMakesExistingRunningRowsImmediatelyRecoverable` now includes both an ordinary old run and a future-dated run and proves both leases are expired by PostgreSQL time after V6. Terminal lease nullability, data preservation, the lease-state constraint, and the one-running partial unique index remain covered.

### Coverage strengthening — renewal/recovery ordering and user status preservation

- Added real PostgreSQL tests for heartbeat-first recovery and recovery-first heartbeat ordering. These complement the existing expired-heartbeat, `SKIP LOCKED`, mutation-lock/recovery, and stale-fencing cases.
- Added an integration test proving every current user-owned `JobStatus` (`DISCOVERED`, `SHORTLISTED`, `APPLIED`, `INTERVIEWING`, `OFFER`, `REJECTED`, and `WITHDRAWN`) survives provider refresh, closure, and reopening.

## Architecture Validation

- **Exact `runId` fencing — confirmed.** Page persistence, listing/job create and refresh, reopening, extraction and fingerprint updates, reconciliation/closure, counters, source success time, and success/failure terminalization first lock and validate the exact run. The check re-reads after lock acquisition, validates source ownership, `RUNNING`, non-null lease, and PostgreSQL-time non-expiry, and retains the lock through transaction completion. An abandoned/replaced run is rejected on all canonical mutation paths.
- **Lease and heartbeat correctness — confirmed.** Creation and renewal use PostgreSQL time. Heartbeats contain only locally registered exact run IDs, use `FOR UPDATE SKIP LOCKED`, cannot revive expired or terminal rows, and do not let an unrelated locked run block other renewals. Registration surrounds both manual and scheduled traversal and is removed in `finally`. Heartbeat infrastructure exists when candidate polling is disabled.
- **Renewal versus recovery — confirmed.** Real PostgreSQL cases cover renewal before recovery, renewal after expiry refusal, recovery first, and canonical mutation holding the run lock while recovery waits and re-evaluates fresh state.
- **Stale recovery and multi-instance safety — confirmed.** There is no startup blanket cleanup. A second Spring application context leaves a healthy run unchanged. Expired recovery locks source then exact run, atomically abandons as `LEASE_EXPIRED`, clears the lease, and creates a replacement; the stale worker can neither renew nor mutate afterward.
- **Lock ordering — confirmed.** Paths requiring both locks use source row then run row. Run-only heartbeat/page/extraction paths do not acquire a source lock, so they do not introduce an inverse edge.
- **Scheduling eligibility — confirmed.** Disabled, never-run, active healthy, active expired, recent terminal, and due terminal cases are covered. Terminal history is ordered `completed_at DESC, id DESC`; manual terminal attempts reset cadence; exact boundary comparisons make equality due/expired; expired recovery bypasses cadence. Polling is speculative and claim-time checks are authoritative.
- **Executor admission — confirmed.** The per-process executor has equal fixed core/max sizes and a `SynchronousQueue` (`queueCapacity=0`). Submission precedes run creation; rejection creates no run; traversal never runs on the scheduler thread. Candidate submission is bounded to twice configured concurrency and cannot accumulate queued work.
- **Manual/scheduled interaction — confirmed.** Same-source claims serialize to one authority, scheduled losers skip, manual conflict remains HTTP 409, losing claims make no provider call, and distinct sources reach the provider concurrently.
- **No hidden retries — confirmed.** Lever transport and traversal make one request per page attempt and do not follow retry/redirect behavior. Failures are terminalized and cadence controls the next attempt.
- **Transaction boundaries — confirmed.** `DiscoverySyncTransactions` is a Spring AOP proxy and its mutation methods use `REQUIRES_NEW`. Provider requests are outside transactions. Provider traversal remains solely in `LeverJobSourceSynchronizer`; transactional authority remains in `DiscoverySyncTransactions`.
- **Provider/reconciliation safety — confirmed.** Zero-result complete traversal closes absent listings; 404, 408/timeouts, 429, 5xx, connection failure, interruption, malformed/invalid/oversized responses, mapping failure, duplicates, traversal limit, and later-page failure suppress absence inference. Earlier committed observations remain valid without advancing last success.
- **Identity and extraction — confirmed.** Closure/reopening reuse listing and canonical-job identity. Deterministic JC-005 extraction handles requirements, zero requirements, changes, unchanged fingerprints, and failures preserving prior accepted state. Stale extraction is fenced. Discovery has no JC-007 invocation.
- **REST compatibility — confirmed.** Manual synchronization remains synchronous, active conflicts remain 409, audited provider failures retain the existing response contract, `LEASE_EXPIRED` and legacy `APPLICATION_RESTARTED` are safely allowlisted, unknown internal codes sanitize to `SYNC_FAILED`, and lease timestamps are absent from DTOs.
- **Configuration — confirmed.** Defaults are disabled, 1-minute tick, 30-second initial delay, 1-hour cadence, concurrency 2, 5-minute lease, and 30-second heartbeat. Positive values, concurrency 1–16, cadence/tick ordering, heartbeat-before-lease, and at least ten heartbeat opportunities are validated.
- **Logging/sensitive data — confirmed.** Operational logs include source/run/trigger/outcome/failure code and counters without provider bodies, descriptions, secrets, or URL query strings. Expected worker exceptions are logged by safe type rather than sensitive message/body.
- **JC-005/006/007 and ranking isolation — confirmed.** The complete suite passed extraction, assessment, intelligence/evaluation, matching/ranking, job CRUD, migrations, and API error handling.

## Migration Validation

PostgreSQL 17.11/Testcontainers executed and validated:

- fresh empty schema V1 → V6, followed by repeat validation/migration;
- explicitly baselined legacy V1 → V6;
- populated V3 → V6;
- populated V4 → V6;
- populated V5 → V6, including terminal data and ordinary/future-dated legacy `RUNNING` rows.

The tests verify terminal leases are null, legacy running leases are immediately expired by database time, invalid status/lease combinations fail, the existing one-`RUNNING` partial unique index remains authoritative, and jobs, statuses, listings, availability, digests, fingerprints, counters, requirements-related data, and source timestamps are preserved.

## Verification

- Required command: `./mvnw.cmd -o clean test`
- Result: **BUILD SUCCESS**
- Tests: **885**
- Failures: **0**
- Errors: **0**
- Skipped: **3**
- Java: Eclipse Temurin **21.0.12.1+1-LTS** (`release 21` compilation)
- Testcontainers: **2.0.5**
- PostgreSQL image/server: **postgres:17.11-alpine / PostgreSQL 17.11**
- Focused post-fix migration/lease group: 16 tests, 0 failures, 0 errors, 0 skips.
- Focused status/identity group: 11 tests, 0 failures, 0 errors, 0 skips.
- Lease/concurrency integration group was rerun post-fix and remained deterministic: 11 tests, 0 failures, 0 errors, 0 skips on each run.
- `git diff --check`: clean (only Git's informational LF→CRLF working-copy warnings).

The three skips are intentional live-provider gates:

1. Lever live acceptance requires system property `jobcopilot.lever.live=true`.
2. Gemini live acceptance requires system property `jobcopilot.gemini.live=true`.
3. OpenAI live smoke requires environment variable `JOB_INTELLIGENCE_LIVE_TEST=true` and credentials.

Local HTTP/provider-boundary and fixture-backed tests ran normally; no unexpected skip was found.

## Remaining Limitations

- Real external-provider acceptance was not enabled because the explicit live-test flags and credentials were absent. This is an intentional, pre-existing non-blocking gate; all offline provider boundary and fixture-backed behavior passed.
- Crash safety is exercised through transaction boundaries, lease expiry/replacement, a second application context, and application-like concurrent claimants rather than forcibly terminating an operating-system JVM mid-request. Exact-run fencing and PostgreSQL recovery tests cover the resulting persisted states.

## Release Recommendation

**YES**

## JC-008 Freeze Recommendation

**YES**

Recommended terminal state: **JC-008 COMPLETE / RELEASED / FROZEN**.
