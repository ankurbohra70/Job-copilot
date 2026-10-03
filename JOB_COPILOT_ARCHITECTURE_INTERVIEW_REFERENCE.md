# Job Copilot — Architecture Interview Reference

**Source of truth:** current working tree production code (`src/main/java`, `src/main/resources`, Flyway migrations).
**Not authoritative:** README sections that predate Workday/JC-010 sync wiring, plan files (`JC-010-PLAN.md`), ticket comments, or test names alone.

**Verified:** 2026-09-22 against the completed JC-010 working tree. No Spring Actuator or `/health` endpoint exists in `src/`.

---

## How to use this document

Each pipeline section follows the same 20-part checklist. Class and method names are exact navigation targets. When README and code disagree, **code wins** — conflicts are called out explicitly.

---

## System overview

Job Copilot is a Spring Boot 3 / Java 21 / PostgreSQL backend for job lifecycle, deterministic resume and requirement extraction, deterministic matching/ranking, Lever and Workday job discovery, and application decision/readiness over live listings.

```
HTTP → Controllers → Services → Repositories/JPA → PostgreSQL (Flyway V1–V9)
                     ↘ Provider HTTP (Lever/Workday) outside DB transactions
```

**Schema:** Hibernate `ddl-auto: validate`. Flyway owns all DDL.

| Migration | File | Purpose |
|-----------|------|---------|
| V1 | `V1__create_jobs.sql` | `jobs` table, status enum |
| V2 | `V2__add_job_requirements.sql` | `min_years_experience`, `job_skills` |
| V3 | `V3__add_resume_profiles.sql` | `resumes`, `candidate_profiles` (JSONB) |
| V4 | `V4__add_job_discovery_domain.sql` | `job_sources`, `external_job_listings`, `job_source_sync_runs` |
| V5 | `V5__add_listing_extraction_fingerprint.sql` | `external_job_listings.extraction_fingerprint` |
| V6 | `V6__add_sync_run_lease.sql` | `lease_expires_at`, running-run index |
| V7 | `V7__add_application_readiness_foundations.sql` | Profile facts, `job_search_preferences`, `resume_routes` |
| V8 | `V8__add_candidate_profile_authority.sql` | DRAFT/CONFIRMED, revision, preference authority move |
| V9 | `V9__add_workday_job_sources.sql` | Provider-neutral routing, Workday identity columns |

---

# Pipeline A — Job Requirement Extraction

## 1. Purpose and user-visible behavior

Deterministically extracts matching requirements (required/preferred skills, minimum years experience) from a **stored** job description. Manual PUT remains available. Successful extraction **fully replaces** requirements; failed extraction returns **422** and leaves prior requirements unchanged. Semantic no-op (same result) skips DB write and preserves `updatedAt`.

During discovery sync, the same extractor runs with a softer gate (`process()` vs `extract()`).

## 2. Entry point / API

| Method | Path | Controller method |
|--------|------|-------------------|
| GET | `/api/jobs/{id}/requirements` | `JobController.getRequirements` |
| PUT | `/api/jobs/{id}/requirements` | `JobController.replaceRequirements` |
| POST | `/api/jobs/{id}/requirements/extract` | `JobController.extractRequirements` |

Discovery-side (internal): `DiscoverySyncTransactions.extract` during sync — no dedicated HTTP endpoint.

## 3. Request DTOs and validation

| DTO | File | Validation |
|-----|------|------------|
| `JobRequirementsRequest` | `job/dto/JobRequirementsRequest.java` | Lists + optional `minYearsExperience` |
| POST extract | *(no body)* | Description read from persisted `Job` |

Service-layer validation: `JobService.normalizedSkills`, `Job.validateExperience` (0–80, max 2 decimal places), disjoint required/preferred lists.

## 4. Controllers

- `com.jobcopilot.job.JobController`

## 5. Services / orchestrators

| Class | Methods | Role |
|-------|---------|------|
| `JobService` | `getRequirements`, `replaceRequirements`, `extractRequirements`, `matchingSnapshot` | HTTP authority; `@Transactional` |
| `DiscoveryJobWriter` | `processRequirements`, `create`, `update`, `rankingReady` | Discovery persistence; no `@Transactional` |
| `DiscoverySyncTransactions` | `extract`, `persistPage`, `succeed`, `fail` | Short REQUIRES_NEW transactions |
| `JobVerificationService` | `find` | Fingerprint staleness for listings |
| `JobSourceService` | `extractionState`, `rankingReady` | API projection of extraction currency |

## 6. Important interfaces and abstractions

| Type | Location | Notes |
|------|----------|-------|
| `JobRequirementExtractor.Extraction` | `JobRequirementExtractor.java` | Internal result record |
| `MatchingVocabulary` | `common/text/MatchingVocabulary.java` | Canonical skill resolution |
| `ExtractionFingerprint` | `discovery/ExtractionFingerprint.java` | `jc005-v1:sha256:…` |
| `JobExtractionBaseline` | `job/JobExtractionBaseline.java` | JC-007 comparison only — **not production path** |
| `JobIntelligenceRunner` | `intelligence/JobIntelligenceRunner.java` | JC-007 shadow — **no production callers** |

## 7. Concrete implementations

| Class | Key methods |
|-------|-------------|
| `JobRequirementExtractor` | `version()` → `"jc005-v1"`, `extract(String)`, `process(String)`, `collectSkills`, `collectExperience`, `isRankingReady` |
| `MatchingVocabulary` | `standard()`, `canonical()`, `normalize()`, `skillSpans()`, `roles()` |
| `JobRepository` | JPA access to `Job` + `@ElementCollection` skills |
| `JobExtractionBaseline` | `capture(String)` — wraps `extractor.extract`, maps failures to `UNAVAILABLE` |

**JC-007 (shadow infrastructure, beans disabled by default):**

| Class | Role |
|-------|------|
| `JobIntelligenceRunner` | `run(Request)` — single model attempt → decode → validate → derive |
| `JobIntelligencePrompt` | `assemble(StrategyInput, ExecutionSettings)` — `LLM_FIRST`, `HYBRID_ENRICHMENT` |
| `JobIntelligenceDecoder` | `decode(String)` — strict JSON |
| `JobIntelligenceValidator` | `validate(Output, title, description)` — grounding policy `job-intelligence-grounding-v1-source-context-1` |
| `MinimumExperienceDeriver` | `derive(...)` — Java-only minimum |
| `OpenAiJobIntelligenceModel` | OpenAI Responses adapter (`JOB_INTELLIGENCE_OPENAI_ENABLED=false` default) |
| `GeminiJobIntelligenceModel` | Gemini adapter (`JOB_INTELLIGENCE_GEMINI_ENABLED=false` default) |

## 8. Domain entities / value objects

| Type | File | Fields of note |
|------|------|----------------|
| `Job` | `job/Job.java` | `replaceRequirements`, `requirements()`, `minYearsExperience` |
| `JobSkill` | `job/JobSkill.java` | `Importance.REQUIRED \| PREFERRED` |
| `JobRequirementsResponse` | `job/dto/JobRequirementsResponse.java` | API read model |
| `JobMatchingSnapshot` | `job/JobMatchingSnapshot.java` | Matching input projection |
| `ExternalJobListing` | `discovery/ExternalJobListing.java` | `extractionFingerprint`, `recordSuccessfulExtraction` |

## 9. Repository / database interactions

- `JobRepository.saveAndFlush` on manual PUT/extract
- `DiscoveryJobWriter.processRequirements` → `JobRepository` inside discovery transaction
- `ExternalJobListingRepository` updates fingerprint on successful sync extraction

## 10. Tables, constraints, indexes

**`jobs`** (V1): title, company, description, status, timestamps
**`job_skills`** (V2): PK `(job_id, skill)`, importance check
**`jobs.min_years_experience`** (V2): CHECK 0–80
**`external_job_listings.extraction_fingerprint`** (V5): nullable until first successful extraction

## 11. Transaction boundaries

| Operation | Propagation | Behavior |
|-----------|-------------|----------|
| `JobService.extractRequirements` | `@Transactional` | Rollback on 422 — prior requirements preserved |
| `JobService.replaceRequirements` | `@Transactional` | Full replace |
| `DiscoverySyncTransactions.extract` | `REQUIRES_NEW` | Per-listing extraction under lease |
| `DiscoveryJobWriter.*` | *(caller TX)* | Comment: callers own transaction |

## 12. Concurrency / idempotency

- Semantic no-op on repeat extract skips `saveAndFlush` (`JobService.semanticallySame`)
- Discovery fingerprint unchanged on extraction failure
- No optimistic locking on `Job` — last-write-wins on concurrent PUT

## 13. External provider boundaries

**None for Pipeline A HTTP path.** JC-007 optional OpenAI/Gemini adapters are isolated; disabled by default.

## 14. Failure behavior

| Failure | HTTP | DB effect |
|---------|------|-----------|
| Blank description | 422 `JobRequirementExtractionException` | No change |
| No recognizable skills/experience | 422 | No change |
| Invalid extracted experience | 422 | No change |
| Discovery extraction exception | Sync continues; listing `unready` | Prior job requirements + fingerprint preserved |

## 15. Recovery behavior

Re-run POST extract or sync. No automatic repair. JC-007 runner failures are terminal per attempt (no retry).

## 16. Important invariants

1. **Production authority = JC-005 `JobRequirementExtractor` only.**
2. `extract()` requires ranking-ready output; `process()` allows partial/zero for discovery.
3. Required wins over preferred for same canonical skill; lists are sorted, unique, disjoint at persistence.
4. Unmarked recognized skills default to **preferred** (conservative required classification).
5. JC-007 baseline (`JobExtractionBaseline`) never feeds prompts or gates readiness.
6. `JobIntelligenceBoundaryQaTest` enforces no `com.jobcopilot.intelligence` imports outside allowed packages in `src/main`.

## 17. Relevant tests

| Test | Proves |
|------|--------|
| `JobRequirementExtractorTest` | Heading/marker/experience parsing |
| `JobRequirementExtractorPhase3SeamTest` | `extract` vs `process` gate |
| `JobRequirementReleaseTest` | Release regressions |
| `JobServiceTest` | 422 preservation, semantic no-op, disjoint lists |
| `JobControllerTest` | HTTP contract |
| `JobPersistenceIntegrationTest` | DB round-trip, idempotent extract |
| `Jc009JobRequirementExtractionEvaluationTest` | Golden parity vs `jc005-v1` |
| `JobExtractionBaselineTest` | Baseline isolation |
| `JobIntelligenceRunnerTest`, `JobIntelligenceIsolationTest`, `JobIntelligenceBoundaryQaTest` | JC-007 shadow isolation |
| `JobIntelligenceEvaluationTest` | 24-case offline harness (test-only) |
| `LeverJobSourceSynchronizationIntegrationTest` | Extraction failure preserves requirements/fingerprint |

## 18. Current limitations

- Rule/vocabulary matching only — no semantic skill inference
- No provenance flag on requirements (manual edits overwritten by later successful extract/sync)
- JC-007 never wired to production APIs or discovery
- No persisted assessment/extraction history beyond current job/listing state

## 19. Why designed this way

Conservative deterministic extraction protects matching scores from false required skills. Separating `process()` from `extract()` lets discovery persist partial requirements while HTTP API maintains strict user expectations. JC-007 is fully built but isolated so released matching/ranking authority stays frozen (`deterministic-v1`, `jc005-v1`) while AI strategies are evaluated offline.

## 20. Alternative designs and trade-offs

| Alternative | Trade-off |
|-------------|-----------|
| LLM-first production extraction | Higher recall, lower auditability; conflicts with JC-009 frozen gates |
| Merge (not replace) on extract | Safer for manual edits, harder to reason about authority |
| Persist JC-007 outputs | Enables audit trail, adds schema/versioning burden |
| Optimistic locking on requirements | Safer concurrency, more client complexity |

### Production authority vs shadow/evaluation (JC-007)

| Lane | Authority | Callers | Persistence |
|------|-----------|---------|-------------|
| JC-005 deterministic | **Production** | `JobService`, `DiscoveryJobWriter` | `jobs`, `job_skills` |
| JC-007 LLM_FIRST / HYBRID | **Shadow / test** | `EvaluationCoordinator`, unit tests | None |
| JC-007 OpenAI/Gemini beans | **Experimental** (opt-in beans) | Tests, manual bean wiring | None |
| `JobExtractionBaseline` | **Evaluation comparator** | JC-007 harness | None |

---

# Pipeline B — Job Discovery and Synchronization

## 1. Purpose and user-visible behavior

Configure release-ready Lever and Workday external job sources, manually or scheduled-sync provider listings into canonical `jobs` + `external_job_listings`, extract requirements, reconcile closures after **complete** traversals, and expose listing/ranking-readiness metadata. User `JobStatus` is independent of listing `LIVE`/`CLOSED`.

## 2. Entry point / API

Base: `/api/job-sources` — `JobSourceController`

| Method | Path | Method |
|--------|------|--------|
| POST | `/api/job-sources` | `create` |
| GET | `/api/job-sources`, `/{sourceId}` | `list`, `get` |
| PATCH | `/api/job-sources/{sourceId}/enabled` | `setEnabled` |
| POST | `/api/job-sources/{sourceId}/sync` | `synchronize` |
| GET | `/api/job-sources/{sourceId}/sync-runs` | `syncRuns` |
| GET | `/api/job-sources/{sourceId}/sync-runs/{runId}` | `syncRun` |
| GET | `/api/job-sources/{sourceId}/listings` | `listings` |
| GET | `/api/job-sources/{sourceId}/listings/{listingId}` | `listing` |

Canonical job details remain on `/api/jobs/{jobId}`.

## 3. Request DTOs and validation

| DTO | File | Notes |
|-----|------|-------|
| `CreateJobSourceRequest` | `discovery/dto/CreateJobSourceRequest.java` | `provider`, optional Lever `region`/`sourceKey`, Workday host/tenant/site/locale |
| `UpdateJobSourceEnabledRequest` | `discovery/dto/UpdateJobSourceEnabledRequest.java` | `enabled` boolean |

Runtime validation: `LeverSource`, `WorkdaySource` constructors + DB CHECK constraints (V9).

## 4. Controllers

- `com.jobcopilot.discovery.JobSourceController`

## 5. Services / orchestrators

| Class | Key methods |
|-------|-------------|
| `JobSourceService` | `create`, `synchronize`, `listings`, `extractionState` |
| `JobSourceSyncDispatcher` | `synchronize`, `synchronizeScheduled` — routes by `JobSourceProvider` |
| `JobSourceSynchronizer` *(abstract)* | `synchronize`, `synchronizeScheduled`, `execute` |
| `LeverJobSourceSynchronizer` | `provider()`, `cursor()` |
| `WorkdayJobSourceSynchronizer` | `provider()`, `cursor()` — buffers full traversal |
| `DiscoverySyncTransactions` | `startManual`, `startScheduled`, `persistPage`, `extract`, `succeed`, `fail`, `authority`, `heartbeat` |
| `DiscoveryJobWriter` | Canonical job create/update + `processRequirements` |
| `ScheduledJobSourceSyncCoordinator` | `poll`, `execute` |
| `JobSourceSyncScheduler` | `tick` |
| `JobSourceSyncHeartbeat` | `register`, `unregister`, scheduled `renewActiveRuns` |

## 6. Important interfaces and abstractions

| Type | Location |
|------|----------|
| `JobSourceSynchronizer` | Shared kernel — `Cursor`, `Step`, `Page`, `Failure` |
| `MappedExternalJobListing` | Provider-neutral mapped listing + `jobProjection()` |
| `LeverPostingGateway` | One page fetch |
| `WorkdayPostingGateway` | `searchPage`, `fetchDetail` |
| `CompletedTraversal` | Seen external IDs for reconciliation |
| `ProviderContentDigest` | Content change detection |
| `JobSourceProvider` | `LEVER`, `WORKDAY` |

## 7. Concrete implementations

**Lever**

| Class | Methods |
|-------|---------|
| `HttpLeverPostingGateway` | `fetchPage` |
| `LeverEndpointResolver` | `resolve` |
| `LeverResponseDecoder` | decode/validate |
| `LeverPostingMapper` | `map` |
| `LeverDescriptionAssembler` | `assemble` |
| `LeverJobSourceSynchronizer` | `PAGE_LIMIT=100`, `MAX_PAGES=100`; one HTTP page per kernel step |

**Workday (JC-010 — see status matrix)**

| Class | Methods |
|-------|---------|
| `HttpWorkdayPostingGateway` | `searchPage`, `fetchDetail` |
| `WorkdayEndpointResolver` | CXS URL construction |
| `WorkdayResponseDecoder` | Strict JSON validation |
| `WorkdayTraversal` | `traverse()` — search pagination + detail fetches |
| `WorkdayPostingMapper` | `map` — uses `WorkdayJobDetail.opaqueId()` as `external_job_id` |
| `DescriptionTextNormalizer` | `htmlText` |
| `WorkdayJobSourceSynchronizer` | Full traversal on first `cursor.next()`, replays buffered pages |

## 8. Domain entities

| Entity | Table | Key fields |
|--------|-------|------------|
| `JobSource` | `job_sources` | provider, routing_region, source_key OR Workday identity columns |
| `ExternalJobListing` | `external_job_listings` | availability, digest, URLs, fingerprint |
| `JobSourceSyncRun` | `job_source_sync_runs` | status, counters, lease, failure_code |

## 9. Repository interactions

- `JobSourceRepository.findLockedById`, `findScheduledCandidates` (includes WORKDAY)
- `ExternalJobListingRepository` — listing CRUD, read projections
- `JobSourceSyncRunRepository` — `findRunningForUpdate`, terminal history

## 10. Tables, constraints, indexes

**V4:** `uq_external_job_listings_source_external_id`, `uq_external_job_listings_job`, `ux_job_source_sync_runs_one_running_per_source`
**V6:** `lease_expires_at` NOT NULL when RUNNING; `ix_job_source_sync_runs_running_lease`
**V9:** Partial unique indexes `uq_job_sources_lever_identity`, `uq_job_sources_workday_identity`; provider configuration CHECK

## 11. Transaction boundaries

**Provider I/O is outside transactions** (`JobSourceService` comment; integration tests assert `!TransactionSynchronizationManager.isActualTransactionActive()` during gateway calls).

Independent `REQUIRES_NEW` transactions: run start, each page persist, each extract, succeed/fail terminalization, heartbeat renewals.

## 12. Concurrency / idempotency / leases

| Mechanism | Implementation |
|-----------|----------------|
| One RUNNING run per source | DB unique partial index + `JobSourceSyncAlreadyRunningException` → HTTP 409 |
| Lease | `lease_expires_at` from PostgreSQL time; renewed before provider calls and mutations |
| Heartbeat | `JobSourceSyncHeartbeat` — runs even when scheduling disabled |
| Fencing | `DiscoverySyncTransactions.authority` — stale run gets `JobSourceSyncLeaseLostException` |
| Expired recovery | `abandonExpired` → `LEASE_EXPIRED`; reclaimed on next manual/scheduled claim — **no startup sweep** |
| Duplicate detection | In-run `seen` set + cross-page checks; Workday path/opaqueId sets; DB unique constraint |
| Idempotent re-sync | Same external ID updates listing/job in place; identity preserved across close/reopen |

## 13. External provider boundaries

| Provider | Endpoint pattern | Limits |
|----------|------------------|--------|
| Lever | Postings API | 20s timeout, 10MB max, no redirects/retries |
| Workday | `POST /wday/cxs/{tenant}/{site}/jobs`, `GET` detail path | Page size 20, `total` not trusted for termination |

## 14. Failure behavior

| Scenario | Run status | Canonical effect |
|----------|------------|------------------|
| Mid-traversal provider failure | `FAILED` | Pages already written remain; **no closure reconciliation** |
| Complete traversal | `SUCCEEDED` | Closes unseen listings; updates `lastSuccessfulSyncAt` |
| Extraction failure per listing | Sync may still succeed | Prior requirements + fingerprint preserved |
| Concurrent sync | 409 Conflict | No second run |
| Disabled source sync | 409 Conflict | — |

Expected provider failures on sync return **HTTP 200** with terminal `FAILED` run (audited failure code).

## 15. Recovery behavior

- Expired lease → next claimant abandons as `LEASE_EXPIRED` and starts new run
- V6 migration sets immediately-expired lease on pre-existing RUNNING rows
- Scheduled cadence provides next attempt — no immediate provider retry
- `APPLICATION_RESTARTED` in allowlist is **legacy** — production emits `LEASE_EXPIRED`

## 16. Important invariants

1. Reconciliation and `lastSuccessfulSyncAt` advance **only after complete, duplicate-free traversal**.
2. Listing closure does **not** change user `JobStatus`.
3. Job/listing identity stable across refresh, close, reopen.
4. Healthy unexpired run cannot be abandoned by another instance starting.
5. Workday: search `total` is **not** traversal authority (observed to return 0 on later offsets).

## 17. Relevant tests

| Test | Proves |
|------|--------|
| `LeverJobSourceSynchronizationIntegrationTest` | Full lifecycle, partial failure, leases, concurrency |
| `WorkdayJobSourceSynchronizationIntegrationTest` | Workday sync parity, paging, scheduled path |
| `DiscoveryLeaseIntegrationTest` | Lease not abandoned by healthy peer |
| `DiscoverySchedulingTest` | Cadence, capacity rejection |
| `DiscoveryPersistenceIntegrationTest` | Schema persistence |
| `JobSourceApiIntegrationTest` | HTTP contract, Workday create/sync, 409 conflicts |
| `DigestAndFingerprintTest` | Fingerprint format |
| `HttpLeverPostingGatewayTest`, `HttpWorkdayPostingGatewayTest` | HTTP boundary |
| `WorkdayTraversalTest` | Pagination termination |
| `SchemaMigrationIntegrationTest` | V9 constraints |

## 18. Current limitations

- No authentication/authorization on discovery APIs
- Workday posting identity (`opaqueId`) has repeated multi-site and whole-board evidence; a sanctioned controlled edit/closure/reopen lifecycle was not available
- No fleet-wide scheduling cap — per-process concurrency only
- Incomplete traversal leaves stale LIVE listings open by design

## 19. Why designed this way

Short transactions + out-of-band HTTP prevent holding DB locks during slow provider calls. Complete-only reconciliation avoids mass-closing listings on transient failures. Shared `JobSourceSynchronizer` kernel avoids duplicating lease/reconcile logic per provider. Lazy lease expiry recovery avoids dangerous startup sweeps.

## 20. Alternative designs and trade-offs

| Alternative | Trade-off |
|-------------|-----------|
| Reconcile on partial success | Faster consistency, risk of false closures |
| Startup sweep of RUNNING runs | Simpler ops story, can kill healthy slow runs without leases |
| Queue-based async sync | Better throughput, loses synchronous audit API |
| Single generic provider adapter | Less duplication, harder to encode Lever vs Workday semantics |

### Historical README vs code conflicts (Pipeline B)

| Earlier README claim | Final JC-010 reality |
|--------------|--------------|
| JC-010 Phase 1: Workday excluded from sync/scheduling; manual sync returns conflict | `WorkdayJobSourceSynchronizer`, `JobSourceSyncDispatcher`, `findScheduledCandidates` include WORKDAY; `JobSourceApiIntegrationTest` expects 200 on Workday sync |
| Scope line: "Lever discovery" only | V9 + Workday synchronizer fully wired in working tree |
| JC-010: identity not frozen | Phase 5 accepted `opaqueId` as `external_job_id` after repeated multi-site and whole-board evidence |

---

# Pipeline C — Candidate Resume / Profile

## 1. Purpose and user-visible behavior

Upload text-based PDF resume → deterministic extraction → `DRAFT` profile at revision 1. User confirms/corrects via confirmation endpoint → `CONFIRMED` authoritative profile for matching. Search intent (locations, compensation, strategy) lives in separate `job_search_preferences`. Resume routes link approved immutable resume records for readiness.

## 2. Entry points / API

| Method | Path | Controller |
|--------|------|------------|
| POST | `/api/resumes` (multipart `file`) | `ResumeController.upload` |
| GET | `/api/resumes/{id}` | `ResumeController.get` |
| GET | `/api/candidate-profiles/{id}` | `CandidateProfileController.get` |
| POST | `/api/candidate-profiles` | `CandidateProfileController.create` |
| PUT | `/api/candidate-profiles/{id}` | `CandidateProfileController.update` |
| PUT | `/api/candidate-profiles/{id}/confirmation` | `CandidateProfileController.confirm` |
| GET/PUT | `/api/candidate-profiles/{id}/job-search-preference` | `JobSearchPreferenceController` |
| GET/POST | `/api/candidate-profiles/{id}/resume-routes` | `ResumeRouteController` |

## 3. Request DTOs and validation

| DTO | File |
|-----|------|
| *(multipart)* | Single part named `file`; exactly one file |
| `CandidateProfileRequest` | Identity/contact facts |
| `CandidateProfileConfirmationRequest` | `expectedRevision`, `profile`, `facts` |
| `CandidateProfileData` | Structured JSONB payload |
| `JobSearchPreferenceRequest` | Full replacement search policy |

`CandidateProfileValidator` bounds collections (5000 items, 500K chars aggregate).

## 4. Controllers

- `ResumeController`, `CandidateProfileController`, `JobSearchPreferenceController`, `ResumeRouteController`

## 5. Services

| Class | Methods |
|-------|---------|
| `ResumeService` | `upload` — **no @Transactional** (parse outside TX) |
| `ResumePersistenceService` | `saveDraft`, `confirm`, `matchingSnapshot`, `facts`, `getProfile` |
| `JobSearchPreferenceService` | `get`, `replace` |
| `ResumeRouteService` | `create`, `list`, `resolve` |
| `PdfBoxResumeTextExtractor` | PDF text extraction |
| `DeterministicCandidateProfileExtractor` | `extract` — sole production `CandidateProfileExtractor` |
| `DeterministicProfileParser` | `parse` — `VERSION = "rules-v1"` |

## 6. Interfaces

| Interface | Impl |
|-----------|------|
| `CandidateProfileExtractor` | `DeterministicCandidateProfileExtractor` |
| `ResumeTextExtractor` | `PdfBoxResumeTextExtractor` |

## 7. Concrete implementations

- `DeterministicProfileParser` — section/skill/experience rules
- `MatchingVocabulary` — skill canonicalization in parser
- `CandidateProfile` entity — `confirmOrReplace`, `@Version revision`

## 8. Domain entities

| Entity | Table |
|--------|-------|
| `Resume` | `resumes` — stores extracted text, not raw PDF bytes |
| `CandidateProfile` | `candidate_profiles` — JSONB + flattened facts + status/revision |
| `JobSearchPreference` | `job_search_preferences` + child role/location/work-arrangement tables |
| `ResumeRoute` | `resume_routes` |

**Authority split (V8):**

| Data | Authority |
|------|-----------|
| Skills, experience months, work history (structured) | `CandidateProfileData` after confirmation |
| Name, email, work auth, sponsorship | `CandidateProfileFacts` columns |
| Relocation, compensation, target roles, freshness | `JobSearchPreference` |
| Raw resume text | Stored on `Resume` but **not** used for matching |

## 9. Repositories

- `ResumeRepository`, `CandidateProfileRepository`, `JobSearchPreferenceRepository`, `ResumeRouteRepository`

## 10. Tables (V3, V7, V8)

V3: `resumes`, `candidate_profiles` (1:1 resume link initially)
V7: nullable `resume_id`, fact columns, preferences, routes
V8: `status`, `revision`, `confirmed_at`; compensation/relocation moved to preferences

## 11. Transactions

| Operation | TX |
|-----------|-----|
| PDF parse | Outside TX |
| `saveDraft`, `confirm`, preference replace | `@Transactional` |
| `matchingSnapshot` | `@Transactional(readOnly=true)` — throws if not CONFIRMED |

## 12. Concurrency

- Optimistic revision via JPA `@Version` + `expectedRevision` on confirmation → 409 `CandidateProfileRevisionConflictException`
- No lock on resume upload itself

## 13. External boundaries

PDF parsing via Apache PDFBox only. No OCR, no LLM extraction in production.

## 14. Failure behavior

| Case | Result |
|------|--------|
| Image-only PDF | Rejected |
| Unconfirmed profile for match/rank/opportunities | 409 |
| Stale revision on confirm | 409 |
| OCR/encrypted PDF | Parse errors |

## 15. Recovery

Re-upload creates new resume + new profile. Confirmation is idempotent at same revision with same normalized payload.

## 16. Invariants

1. Upload → `DRAFT`; manual `POST /api/candidate-profiles` → `CONFIRMED`.
2. Matching reads **only** `CandidateMatchingSnapshot` from confirmed structured data — not raw text or evidence.
3. `totalExperienceMonths` in profile is sole scoring experience (no relevant-experience overlay).
4. Sensitive facts default `UNKNOWN`; parser never silently fills work auth/sponsorship.
5. PUT preference/profile are **full replacements** — omitted collections cleared.

## 17. Tests

| Test | Proves |
|------|--------|
| `ResumeUploadIntegrationTest` | Full upload→confirm→match→opportunities E2E |
| `ResumePersistenceIntegrationTest` | Revision conflicts |
| `CandidateProfileTruthTest` | UNKNOWN sensitive facts |
| `StructuredCandidateAuthorityTest` | No evidence fallback; frozen golden score |
| `Jc009CandidateExtractionEvaluationTest` | Golden extraction parity |
| `DeterministicCandidateProfileExtractorTest` | Parser unit behavior |

## 18. Limitations

- Text-based PDFs only
- No raw PDF retention for later auto-upload
- Identity not extracted from resume — user must supply at confirmation
- README "candidate corrections deferred" is **stale** — confirmation exists

## 19. Why designed this way

Parse-outside-TX avoids holding connections on malicious PDFs. DRAFT→CONFIRMED gate prevents unreviewed parser output from affecting scores. Separating search preferences keeps matching facts distinct from job-search intent.

## 20. Alternatives

| Alternative | Trade-off |
|-------------|-----------|
| LLM resume parsing | Better unstructured recall, breaks JC-009 frozen extractor gate |
| Auto-confirm on upload | Faster UX, unsafe for matching authority |
| Merge PATCH semantics | Easier partial edits, ambiguous authority |

---

# Pipeline D — Assessment and Ranking

## 1. Purpose and user-visible behavior

On-demand deterministic scoring of one job (`POST assessment`) or many jobs (`GET job-rankings`) against an explicit confirmed candidate profile. Results include score, recommendation, skill lists, experience comparison, caps, explanations. **Nothing persisted.**

## 2. Entry points

| Method | Path | Controller |
|--------|------|------------|
| POST | `/api/jobs/{jobId}/assessment` | `JobAssessmentController.assess` |
| POST | `/api/matches` | `MatchController.match` (compat alias) |
| GET | `/api/candidate-profiles/{id}/job-rankings` | `JobRankingController.rank` |

## 3. DTOs

| DTO | Fields |
|-----|--------|
| `JobAssessmentRequest` / `MatchRequest` | `candidateProfileId` (+ `jobId` for match) |
| `MatchResponse` | Full assessment + algorithm/vocabulary/revision metadata |
| `JobRankingQuery` | status[], minScore, recommendation, page, size |
| `JobRankingResponse` | rankedJobs, unassessedJobs, counts |

## 4. Controllers

- `JobAssessmentController`, `MatchController`, `JobRankingController`

## 5. Services

| Class | Methods |
|-------|---------|
| `JobAssessmentService` | `assess` |
| `MatchService` | `match` → delegates to assessment |
| `JobRankingService` | `rank` |
| `JobService` | `matchingSnapshot`, `rankingSnapshots`, `rankingSnapshotsByIds` |
| `ResumePersistenceService` | `matchingSnapshot` |

## 6. Core engine

| Class | Method |
|-------|--------|
| `DeterministicMatchingEngine` | `match(JobMatchingSnapshot, CandidateMatchingSnapshot)` |
| `MatchingPolicy` | `v1()` → `"deterministic-v1"`, weights, thresholds, caps |

## 7. Scoring summary

- Categories: SKILLS 60%, EXPERIENCE 30%, ROLE 5%, KEYWORDS 5%
- Missing required skills cap: max 79 (some match) or 49 (none)
- Recommendations: STRONG ≥80, GOOD ≥65, WEAK ≥45, else NOT_RECOMMENDED
- `MatchCannotBeComputedException` if job has no skills and no positive min experience → unassessed in ranking

## 8. Inputs (not owned by this pipeline)

- Job requirements from `jobs` / `job_skills`
- Candidate from confirmed `CandidateProfileData` snapshot

## 9–10. Persistence

**None for assessment/ranking outputs.** Reads only.

## 11. Transactions

- `JobAssessmentService`: `@Transactional(readOnly=true)`
- `JobRankingService`: no class-level TX; read-only downstream calls

## 12. Concurrency

Read-only; no caching. Repeated calls recompute.

## 13. External boundaries

None.

## 14–15. Failure / recovery

| Case | HTTP |
|------|------|
| Unconfirmed profile | 409 |
| Unassessable job (ranking) | Listed in `unassessedJobs`, not ranked |
| Unassessable job (assessment) | 422 |
| Missing job/profile | 404 |
| Unexpected engine error (ranking) | 500 whole request |

## 16. Invariants

1. `DeterministicMatchingEngine` is sole scoring authority.
2. Assessment does **not** extract requirements.
3. Default ranking statuses: DISCOVERED, SHORTLISTED, APPLIED, INTERVIEWING.
4. Ranking sort: score desc → createdAt desc → jobId desc.
5. Golden score 58.33 / WEAK_MATCH frozen in `StructuredCandidateAuthorityTest`.

## 17. Tests

| Test | Proves |
|------|--------|
| `DeterministicMatchingEngineTest` | Unit scoring |
| `JobAssessmentServiceTest` | Service ≡ engine parity |
| `JobAssessmentIntegrationTest` | HTTP oracle, read-only, `/api/matches` alias |
| `JobRankingIntegrationTest` | Pagination, filters, terminal status exclusion |
| `Jc009MatchingRankingEvaluationTest` | Golden P@K, nDCG, score parity |
| `StructuredCandidateAuthorityTest` | Structured-only authority |

## 18. Limitations

- Vocabulary matching only
- No persisted ranking history
- Category breakdown omitted from ranking summary DTO

## 19. Why designed this way

Explicit `candidateProfileId` avoids implicit "current user" assumptions. Compute-on-read keeps schema simple and matches JC-009 frozen evaluation model.

## 20. Alternatives

| Alternative | Trade-off |
|-------------|-----------|
| Persist assessments | Historical analytics vs storage/version drift |
| LLM scoring | Semantic match vs auditability |
| Extract-on-assess | Convenience vs requirement authority confusion |

---

# Pipeline E — Live Opportunities / Application Decision

## 1. Purpose and user-visible behavior

Over JC-008 LIVE, extraction-current listings in DISCOVERED/SHORTLISTED job states, rank opportunities and project deterministic application **decision** and **readiness** without persisting computed output.

## 2. Entry points

| Method | Path | Params |
|--------|------|--------|
| GET | `/api/jobs/{jobId}/application-decision` | `candidateProfileId` |
| GET | `/api/jobs/{jobId}/application-readiness` | `candidateProfileId` |
| GET | `/api/candidate-profiles/{id}/opportunities` | ranking filters + optional `readiness` |

## 3. DTOs / query

- `OpportunityQuery.from(parameterMap)` — rejects `status` param (fixed eligibility)
- `ApplicationDecisionResult`, `ApplicationReadinessResult`, `LiveOpportunityPageResponse`

## 4. Controllers

- `ApplicationController`, `LiveOpportunityController`

## 5. Services

| Class | Methods |
|-------|---------|
| `ApplicationDecisionService` | `decide` |
| `ApplicationReadinessService` | `evaluate` |
| `LiveOpportunityService` | `find` |
| `LiveOpportunityReadService` | LIVE listing query |
| `JobVerificationService` | Listing freshness / live state |

## 6. Policies

| Class | Version | Role |
|-------|---------|------|
| `ApplicationDecisionPolicy` | `application-decision-v1` | Maps recommendation → SKIP/SAVE/APPLY_* |
| `ApplicationDecisionService` | — | Excluded roles, NEEDS_USER for missing facts on apply paths |
| `ApplicationReadinessService` | — | Structured checks → READY/NEEDS_USER/NOT_READY |

Decision mapping: NOT_RECOMMENDED→SKIP, WEAK→SAVE, GOOD→APPLY_VOLUME, STRONG→APPLY_PRECISION (with strategy override to APPLY_PRECISION).

## 7. Readiness checks (sample codes)

`JOB_VERIFIED_LIVE`, `VERIFICATION_FRESHNESS`, `REQUIREMENTS_CURRENT`, `APPLICATION_URL`, `CANDIDATE_IDENTITY`, `WORK_AUTHORIZATION`, `RESUME_ROUTE`, `APPLICATION_DECISION`, …

## 8–10. Persistence

Reads jobs, listings, profiles, preferences, routes. **No writes** of decision/readiness/opportunity results.

## 11. Transactions

- `ApplicationDecisionService`: read-only
- `ApplicationReadinessService`, `LiveOpportunityService`: `readOnly=true`, `isolation=REPEATABLE_READ` for consistent multi-read snapshots

## 12–15. Concurrency / failure

Repeated GETs observational (tested). Unconfirmed profile → 409. Hard readiness failures → NOT_READY.

## 16. Invariants

1. Reuses `DeterministicMatchingEngine` — does not re-score differently.
2. Live opportunities fix job status filter (unlike ranking).
3. Compensation check non-blocking while job compensation unmodeled.
4. Application URL must be hierarchical HTTP(S) with host.

## 17. Tests

| Test | Proves |
|------|--------|
| `Jc009PolicyGoldenParityTest` | Frozen decision map |
| `Jc009DecisionReadinessEvaluationTest` | Golden decision/readiness metrics |
| `ApplicationReadinessIntegrationTest` | Repeatable GET, V7/V8 integration |
| `LiveOpportunityServiceTest`, `LiveOpportunityControllerTest` | Orchestration + HTTP |
| `ResumeUploadIntegrationTest` | E2E through opportunities |

## 18. Limitations

- No application submission, browser automation, or attempt tracking
- No durable PDF artifact for auto-upload
- Location/work-auth jurisdiction matching conservative

## 19. Why designed this way

Decision layer translates existing match scores into actions without duplicating scoring logic. REPEATABLE_READ gives consistent readiness bundle per request.

## 20. Alternatives

| Alternative | Trade-off |
|-------------|-----------|
| Persist decisions | Audit trail vs staleness on re-read |
| Single combined endpoint | Fewer calls vs separation of concerns |

---

# Pipeline F — Evaluation / Testing Architecture

## 1. Purpose

Quality gates and regression protection **without** affecting production behavior. Two offline golden harnesses (JC-007 job intelligence, JC-009 full stack policies) plus layered unit/integration tests.

## 2. JC-007 harness (`src/test/java/com/jobcopilot/intelligence/evaluation/`)

**Test-only package** — no `src/main/.../evaluation`.

| Class | Role |
|-------|------|
| `EvaluationCoordinator` | Runs JC-005 baseline + HYBRID + LLM_FIRST per case |
| `FixtureBackedModelSource` | Scripted responses through real `JobIntelligenceRunner` |
| `SemanticComparator`, `MetricsAggregator`, `EvaluationReportRenderer` | Metrics + reports to `target/job-intelligence-evaluation/` |

Dataset: `src/test/resources/job-intelligence/evaluation/v1/cases.json` (24 cases)

Run: `./mvnw.cmd "-Dtest=com.jobcopilot.intelligence.evaluation.*Test" test`

## 3. JC-009 harness (`src/test/java/com/jobcopilot/evaluation/`)

| Test | Stage |
|------|-------|
| `Jc009CandidateExtractionEvaluationTest` | `rules-v1` extractor |
| `Jc009JobRequirementExtractionEvaluationTest` | `jc005-v1` |
| `Jc009MatchingRankingEvaluationTest` | `deterministic-v1` |
| `Jc009DecisionReadinessEvaluationTest` | `application-decision-v1` |
| `Jc009EvaluationFoundationTest` | Dataset/version guards |
| `Jc009PolicyGoldenParityTest` | Frozen policy enums/maps |

Dataset: `src/test/resources/jc009/evaluation/v1/golden-cases.json`
Pinned: `jc009-offline-evaluation-v1`, `HUMAN_REVIEWED`, `REVIEWED` cases only.

Run: `./mvnw.cmd "-Dtest=Jc009*EvaluationTest,Jc009EvaluationFoundationTest" test`

## 4. Integration test pattern

12 `@SpringBootTest` + `@Testcontainers` classes using `postgres:17.11-alpine`, `@DynamicPropertySource` — never connects to dev DB on 5432.

## 5. Provider simulations

- Mockito gateways in discovery integration tests
- Local `HttpServer` in `HttpLeverPostingGatewayTest`, `HttpWorkdayPostingGatewayTest`, `OpenAiJobIntelligenceModelTest`
- Workday fixtures under `src/test/resources/workday/fixtures/`

## 6. Opt-in live tests

| Flag | Test |
|------|------|
| `JOB_INTELLIGENCE_LIVE_TEST=true` + `OPENAI_API_KEY` | `OpenAiJobIntelligenceLiveSmokeTest` |
| `-Djobcopilot.gemini.live=true` | `GeminiJobIntelligenceLiveAcceptanceTest` |
| `-Djobcopilot.lever.live=true` | `LeverPostingGatewayLiveAcceptanceTest` |
| `-Djobcopilot.workday.live=true` + host/tenant/site/locale | `WorkdayPostingGatewayLiveAcceptanceTest` |

## 7. Default CI

`./mvnw.cmd clean verify` — all non-opt-in tests; requires Docker for Testcontainers.

---

# IMPLEMENTATION STATUS MATRIX

| Functionality | Status | Evidence |
|---------------|--------|----------|
| Job CRUD + requirements PUT/extract (JC-005) | **IMPLEMENTED / RELEASE-READY** | `JobController`, `JobRequirementExtractor.version()=jc005-v1`, `Jc009JobRequirementExtractionEvaluationTest` |
| Deterministic matching/ranking (JC-004/006) | **IMPLEMENTED / RELEASE-READY** | `DeterministicMatchingEngine`, `Jc009MatchingRankingEvaluationTest` |
| Resume upload + deterministic extraction | **IMPLEMENTED / RELEASE-READY** | `ResumeService`, `DeterministicCandidateProfileExtractor`, `Jc009CandidateExtractionEvaluationTest` |
| DRAFT/CONFIRMED + optimistic revision (V8) | **IMPLEMENTED / RELEASE-READY** | V8 migration, `ResumePersistenceService.confirm`, `ResumeUploadIntegrationTest` |
| Job search preferences + resume routes (V7) | **IMPLEMENTED / RELEASE-READY** | V7 migration, controllers, `Jc009DecisionReadinessEvaluationTest` |
| Application decision/readiness (JC-009 policies) | **IMPLEMENTED / RELEASE-READY** | `ApplicationDecisionPolicy.VERSION`, golden tests |
| Live opportunities | **IMPLEMENTED / RELEASE-READY** | `LiveOpportunityService`, integration tests |
| Lever discovery sync (manual + scheduled) | **IMPLEMENTED / RELEASE-READY** | `LeverJobSourceSynchronizer`, `DiscoveryLeaseIntegrationTest`, JC-008 QA |
| Sync leases, heartbeats, fencing | **IMPLEMENTED / RELEASE-READY** | V6, `DiscoverySyncTransactions`, `JobSourceSyncHeartbeat` |
| JC-007 contracts + runner + grounding | **IMPLEMENTED / EXPERIMENTAL OR SHADOW** | Beans exist; `enabled=false`; no production callers; `JobIntelligenceBoundaryQaTest` |
| JC-007 OpenAI/Gemini adapters | **IMPLEMENTED / EXPERIMENTAL OR SHADOW** | Opt-in beans + live smoke tests only |
| JC-007 offline evaluation harness | **IMPLEMENTED / EXPERIMENTAL OR SHADOW** | Test-only; reports in `target/` |
| JC-009 offline golden evaluation | **IMPLEMENTED / RELEASE-READY (frozen gate)** | Version-pinned dataset; `Jc009EvaluationFoundationTest` passes |
| Workday HTTP boundary (Phase 1) | **IMPLEMENTED / RELEASE-READY** | `HttpWorkdayPostingGateway`, unit tests |
| Workday sync kernel wiring (Phase 2–3) | **IN PROGRESS** | Code + `WorkdayJobSourceSynchronizationIntegrationTest`; identity not validated live |
| Workday scheduled production rollout | **IN PROGRESS** | Included in `findScheduledCandidates`; no JC-009 e2e for Workday path |
| Workday live multi-tenant acceptance | **IN PROGRESS** | `WorkdayPostingGatewayLiveAcceptanceTest` gated; wd5 failures documented in README |
| JC-007 production API/persistence | **PLANNED / NOT IMPLEMENTED** | No REST endpoint, no migration |
| Authentication/authorization | **PLANNED / NOT IMPLEMENTED** | README explicit |
| Application submission/automation | **PLANNED / NOT IMPLEMENTED** | README explicit |
| OCR / LLM resume parsing | **PLANNED / NOT IMPLEMENTED** | — |
| Persisted assessments/rankings | **PLANNED / NOT IMPLEMENTED** | — |
| Health/actuator endpoint | **PLANNED / NOT IMPLEMENTED** | No dependency in codebase |
| Job dedup by source+externalJobId | **PLANNED / NOT IMPLEMENTED** | README deferred |

### JC-009 freeze status

Frozen via **test gates**, not a runtime flag:
- Dataset version `jc009-offline-evaluation-v1` enforced in `Jc009GoldenDataset.validate()`
- Extractor/algorithm/policy versions pinned
- `Jc009PolicyGoldenParityTest` locks decision map and enum names
- `StructuredCandidateAuthorityTest` locks golden score 58.33

Production code for JC-009 scope (matching, decision, readiness, confirmation) is **release-ready** and guarded by these tests.

### JC-010 / Workday status

| Phase | Final status |
|-------|--------------|
| 1 Network boundary | Complete |
| 2 Traversal/mapping | Complete |
| 3 Sync/reconcile | Complete |
| 4 API manual sync | Complete |
| 5 Live acceptance + identity freeze | Complete for V1 Beta; controlled lifecycle evidence remains an explicit limitation |

**Conclusion:** JC-010 Workday sync is **COMPLETE AND FROZEN for V1 Beta**. See `JC-010-PHASE5-QA.md` for terminal evidence and limitations.

---

# MANUAL TECHNICAL DEMO RUNBOOK

All commands verified from `README.md`, `compose.yaml`, and controller mappings. Platform: Windows PowerShell examples; macOS/Linux use `./mvnw` and `docker compose`.

### 1. Start required infrastructure

```powershell
docker compose up -d
docker compose ps
```

File: `compose.yaml` (not `docker-compose.yml`). Postgres `localhost:5432`, db/user/password: `job_copilot`.

### 2. Start Job Copilot locally

```powershell
.\mvnw.cmd spring-boot:run
```

API base: `http://localhost:8080`

### 3. Verify health

**No `/health` or Actuator endpoint exists.**

Use infrastructure + API smoke checks:

```powershell
docker compose ps   # postgres should be healthy (pg_isready)
Invoke-RestMethod -Method Get -Uri "http://localhost:8080/api/jobs"
```

Expect `200` with pagination JSON (possibly empty `content`).

### 4. Upload / ingest a resume

**No sample PDF is committed in the repository.** README references `$env:TEMP\job-copilot-sample-resume.pdf` which you must create yourself (text-based PDF).

```powershell
Invoke-RestMethod -Method Post -Uri "http://localhost:8080/api/resumes" -Form @{
    file = Get-Item -Path "C:\path\to\your\text-resume.pdf"
}
```

Expect `201 Created`, `Location: /api/resumes/{id}`, embedded `candidateProfile` with `status: "DRAFT"`.

**Cannot demo without a valid text-based PDF.**

### 5. Inspect candidate profile

```powershell
Invoke-RestMethod -Method Get -Uri "http://localhost:8080/api/candidate-profiles/{id}"
Invoke-RestMethod -Method Get -Uri "http://localhost:8080/api/resumes/{id}"
```

Responses omit full extracted text.

### 6. Correct / confirm profile

```powershell
$confirmation = @{
    expectedRevision = 1
    profile = (Invoke-RestMethod "http://localhost:8080/api/candidate-profiles/1").profile
    facts = @{
        fullName = "Your Name"
        email = "you@example.com"
        workAuthorization = "YES"
        sponsorshipRequired = "NO"
    }
} | ConvertTo-Json -Depth 10

Invoke-RestMethod -Method Put -Uri "http://localhost:8080/api/candidate-profiles/1/confirmation" `
    -ContentType "application/json" -Body $confirmation
```

Optional search policy:

```powershell
Invoke-RestMethod -Method Put -Uri "http://localhost:8080/api/candidate-profiles/1/job-search-preference" `
    -ContentType "application/json" -Body '{"defaultResumeStrategy":"VOLUME","freshnessDays":14}'
```

### 7. Configure / inspect job source

Lever example:

```powershell
$source = @{
    provider = "LEVER"
    region = "GLOBAL"
    sourceKey = "leverdemo"
    companyName = "Lever Demo"
    enabled = $true
} | ConvertTo-Json

Invoke-RestMethod -Method Post -Uri "http://localhost:8080/api/job-sources" `
    -ContentType "application/json" -Body $source

Invoke-RestMethod -Method Get -Uri "http://localhost:8080/api/job-sources"
```

Workday example (requires real host/tenant/site/locale):

```powershell
$workday = @{
    provider = "WORKDAY"
    companyName = "Example Co"
    enabled = $true
    endpointHost = "example.wd5.myworkdayjobs.com"
    tenantKey = "example"
    siteKey = "External_Career_Site"
    locale = "en-US"
} | ConvertTo-Json

Invoke-RestMethod -Method Post -Uri "http://localhost:8080/api/job-sources" `
    -ContentType "application/json" -Body $workday
```

### 8. Trigger discovery synchronization

```powershell
Invoke-RestMethod -Method Post -Uri "http://localhost:8080/api/job-sources/{sourceId}/sync"
```

- **Lever `leverdemo`:** Requires outbound network to Lever; may succeed with real provider data.
- **Workday:** Requires valid tenant coordinates and network; live acceptance historically flaky on wd5 (README).
- **Local-safe alternative:** Run integration tests with mocked gateways (`./mvnw.cmd "-Dtest=LeverJobSourceSynchronizationIntegrationTest" test`) — not the running app's HTTP API.

Concurrent sync on active run → `409 Conflict`.

### 9. Inspect synchronization results

```powershell
Invoke-RestMethod -Method Get -Uri "http://localhost:8080/api/job-sources/{sourceId}/sync-runs"
Invoke-RestMethod -Method Get -Uri "http://localhost:8080/api/job-sources/{sourceId}/sync-runs/{runId}"
```

### 10. Inspect persisted jobs / listings

```powershell
Invoke-RestMethod -Method Get -Uri "http://localhost:8080/api/job-sources/{sourceId}/listings"
Invoke-RestMethod -Method Get -Uri "http://localhost:8080/api/jobs/{jobId}"
Invoke-RestMethod -Method Get -Uri "http://localhost:8080/api/jobs/{jobId}/requirements"
```

PostgreSQL direct:

```powershell
docker compose exec postgres psql -U job_copilot -d job_copilot -c "
SELECT id, title, company, status FROM jobs ORDER BY id DESC LIMIT 10;"
docker compose exec postgres psql -U job_copilot -d job_copilot -c "
SELECT id, job_source_id, external_job_id, availability, extraction_fingerprint
FROM external_job_listings ORDER BY id DESC LIMIT 10;"
```

### 11. Assessment / ranking

Create/manual job if not from discovery:

```powershell
# Create job + requirements (see README), then:
$assessment = @{ candidateProfileId = 1 } | ConvertTo-Json
Invoke-RestMethod -Method Post -Uri "http://localhost:8080/api/jobs/1/assessment" `
    -ContentType "application/json" -Body $assessment

Invoke-RestMethod -Method Get -Uri "http://localhost:8080/api/candidate-profiles/1/job-rankings"
```

Unconfirmed profile → `409`.

### 12. Live opportunities / readiness / decision

```powershell
Invoke-RestMethod -Method Get -Uri "http://localhost:8080/api/jobs/1/application-decision?candidateProfileId=1"
Invoke-RestMethod -Method Get -Uri "http://localhost:8080/api/jobs/1/application-readiness?candidateProfileId=1"
Invoke-RestMethod -Method Get -Uri "http://localhost:8080/api/candidate-profiles/1/opportunities"
```

Requires LIVE listing linked to job, confirmed profile, extracted requirements, and readiness prerequisites (route, freshness, etc.) for `READY`.

### 13. Useful PostgreSQL tables

| Table | Purpose |
|-------|---------|
| `flyway_schema_history` | Migration state |
| `jobs`, `job_skills` | Canonical jobs + requirements |
| `resumes`, `candidate_profiles` | Upload + structured profile |
| `job_search_preferences`, `job_search_preference_*` | Search intent |
| `resume_routes` | Approved resume routing |
| `job_sources` | Lever/Workday config |
| `external_job_listings` | Provider listings + fingerprint |
| `job_source_sync_runs` | Sync audit + leases |

```powershell
docker compose exec postgres psql -U job_copilot -d job_copilot -c "
SELECT installed_rank, version, description, success
FROM flyway_schema_history ORDER BY installed_rank;"
```

### 14. Application logs

No custom `logback.xml` in repo — Spring Boot default logging to console.

Useful loggers (SLF4J):

| Logger | Class | Events |
|--------|-------|--------|
| Discovery sync lifecycle | `JobSourceSynchronizer` | `Discovery sync completed …` |
| Scheduled admission | `ScheduledJobSourceSyncCoordinator` | `Scheduled discovery admission rejected …` |
| Heartbeat | `JobSourceSyncHeartbeat` | `Discovery heartbeat …` |
| Expired run recovery | `JobSourceSynchronizer` | `Discovery recovered expired run …` |

Filter console output for `Discovery` while running sync.

### 15. Stop / reset environment

```powershell
# Stop Spring Boot: Ctrl+C in run terminal

docker compose down              # preserve volume
docker compose down --volumes    # destroy local DB (explicit reset)
```

---

# INTERVIEW QUESTION MAP

Questions only — no answers.

## Pipeline A — Job requirement extraction

**Foundational:** What is the difference between `JobRequirementExtractor.extract()` and `process()`? When does POST extract return 422 vs 200 without updating `updatedAt`?

**Java/Spring:** Why is `DiscoveryJobWriter` not `@Transactional`? How does `@Transactional` on `JobService.extractRequirements` preserve prior requirements on failure?

**Database:** Describe `job_skills` PK and cascade behavior. What migration added `min_years_experience` constraints?

**Concurrency:** What happens if two clients PUT requirements concurrently?

**Distributed/failure:** If discovery extraction throws mid-sync, what happens to fingerprint and existing requirements?

**Design:** Why default unrecognized skills to preferred? Why is JC-007 isolated from production packages?

**Testing:** Which test proves extraction failure during sync does not mutate requirements?

**Staff follow-up:** How would you add provenance without breaking JC-009 golden tests? Where would LLM_FIRST plug in if product approved it?

## Pipeline B — Discovery

**Foundational:** When are listings closed? What is the difference between listing `CLOSED` and job `WITHDRAWN`?

**Java/Spring:** Walk through `JobSourceSynchronizer.execute` from `POST /sync` to terminal run.

**Database:** Explain `ux_job_source_sync_runs_one_running_per_source` and V6 lease column semantics.

**Concurrency:** How do heartbeats interact with scheduled polling disabled? What is fencing via `authority()`?

**Distributed/failure:** Two instances, one stale lease — can both write canonical jobs? Why intentional duplicate provider reads?

**Design:** Why Workday buffers full traversal before paging to kernel? Trade-offs vs Lever streaming?

**Testing:** Which test simulates expired lease recovery without startup sweep?

**Staff follow-up:** README says Workday manual sync conflicts — defend what code actually does. What evidence would you need before freezing Workday `external_job_id`?

## Pipeline C — Resume / profile

**Foundational:** What is authoritative for matching — resume text, evidence, or confirmed JSONB?

**Java/Spring:** Why parse PDF outside a transaction? Trace DRAFT→CONFIRMED.

**Database:** What did V8 move from `candidate_profiles` to `job_search_preferences`?

**Concurrency:** How does `expectedRevision` interact with JPA `@Version`?

**Distributed/failure:** What happens if confirmation succeeds but client retries same payload?

**Design:** Why separate facts columns from JSONB profile data?

**Testing:** Which test proves deleted structured facts are not restored from evidence?

**Staff follow-up:** How would you add PATCH semantics without breaking full-replace invariants?

## Pipeline D — Assessment / ranking

**Foundational:** What inputs are required before a job is assessable vs unassessed in ranking?

**Java/Spring:** Why does ranking load all eligible jobs before pagination?

**Database:** Are assessment results stored anywhere?

**Concurrency:** Is ranking safe under concurrent job updates?

**Distributed/failure:** Why does ranking return 500 on unexpected engine failure instead of partial results?

**Design:** Explain score caps for missing required skills. Why explicit `candidateProfileId`?

**Testing:** Which JC-009 test freezes recommendation boundaries?

**Staff follow-up:** How would persisted rankings interact with re-extraction?

## Pipeline E — Opportunities / decision / readiness

**Foundational:** Difference between `ApplicationDecision` and `ApplicationReadiness` overall state?

**Java/Spring:** Why `REPEATABLE_READ` on readiness but not decision service?

**Database:** Which tables does readiness consult for freshness and routes?

**Concurrency:** Are opportunity results idempotent on repeat GET?

**Distributed/failure:** Job listing goes CLOSED between decision and readiness read — what check fails?

**Design:** Why doesn't compensation block readiness today?

**Testing:** Which golden test covers false-skip/false-apply rates?

**Staff follow-up:** What's missing before claiming automated application submission?

## Pipeline F — Evaluation

**Foundational:** Difference between JC-007 and JC-009 harness scope?

**Java/Spring:** Why is JC-007 evaluation package test-only enforced by test?

**Database:** Do evaluation harnesses require PostgreSQL?

**Concurrency:** Does JC-007 runner share a thread pool?

**Distributed/failure:** What does fixture-backed evaluation prove vs live smoke tests?

**Design:** Why zero-denominator metrics render null in JC-007 reports?

**Testing:** How do Testcontainers tests avoid dev DB on 5432?

**Staff follow-up:** Would you promote JC-007 Hybrid to production based on fixture harness results? Why not?

---

# TOP 20 IMPLEMENTATION DETAILS I MUST KNOW WITHOUT LOOKING AT THE CODE

1. **Production job requirement authority is JC-005** (`JobRequirementExtractor`, version string `jc005-v1`) — not JC-007, not LLM.

2. **`extract()` throws if not ranking-ready; `process()` does not** — discovery uses the softer seam.

3. **Successful requirement extraction fully replaces** prior skills and min experience; failed HTTP extract returns 422 and leaves DB unchanged.

4. **Semantic no-op extract** skips save when normalized output equals current requirements (preserves `updatedAt`).

5. **Unmarked recognized skills in job descriptions default to preferred**, not required — deliberate conservative choice.

6. **Matching algorithm version is `deterministic-v1`** with fixed category weights 60/30/5/5 and caps at 79/49 for missing required skills.

7. **Assessments and rankings are never persisted** — recomputed on every request from stored snapshots.

8. **Upload creates DRAFT profile revision 1; matching requires CONFIRMED** via `PUT .../confirmation` with `expectedRevision`.

9. **`CandidateMatchingSnapshot` excludes raw resume text** — structured confirmed profile only.

10. **Compensation and relocation authority live in `job_search_preferences` (V8)**, not on the profile row.

11. **Discovery provider HTTP runs outside DB transactions**; short `REQUIRES_NEW` transactions wrap persist/extract/terminalize.

12. **Listing reconciliation (closure) runs only after complete successful traversal** — partial failures leave prior LIVE listings open.

13. **At most one RUNNING sync per source** enforced by partial unique index; concurrent manual sync → HTTP 409.

14. **Leases use PostgreSQL time; expired RUNNING runs become `LEASE_EXPIRED` and are reclaimable** — no application startup sweep.

15. **Heartbeats renew active runs even when scheduled discovery is disabled.**

16. **`JobSourceSyncDispatcher` requires exactly one synchronizer bean per `JobSourceProvider` enum value** (LEVER + WORKDAY).

17. **JC-007 `JobIntelligenceRunner` has zero production callers**; OpenAI/Gemini beans default `enabled: false`.

18. **`ApplicationDecisionPolicy.VERSION` is `application-decision-v1`** mapping recommendations to SKIP/SAVE/APPLY_VOLUME/APPLY_PRECISION.

19. **Flyway owns schema V1–V9; Hibernate validates only** — `ddl-auto: validate`.

20. **No Spring Actuator / `/health` endpoint** — verify via Postgres healthcheck + API smoke GET.

---

# TOP 10 THINGS I MUST NOT CLAIM ARE IMPLEMENTED

1. **JC-007 LLM_FIRST or HYBRID_ENRICHMENT in production job extraction** — shadow/test infrastructure only.

2. **LLM or embedding-based matching/scoring** — deterministic vocabulary engine only.

3. **Persisted assessment, ranking, decision, or readiness history** — all computed on read.

4. **Authentication, authorization, or multi-tenant user isolation** on any API.

5. **Automated job application submission, browser automation, or application attempt tracking.**

6. **OCR for image-only PDFs** or storage of raw PDF bytes for later upload.

7. **Health/actuator endpoint** for load balancer probes.

8. **Sanctioned controlled Workday edit/closure/reopen lifecycle evidence** — not available; Phase 5 records this as an explicit limitation rather than a V1 Beta blocker.

9. **README's claim that Workday manual sync returns conflict without creating a run** — code performs real sync via `WorkdayJobSourceSynchronizer`.

10. **Global deduplication of jobs by `(source, externalJobId)`** — explicitly deferred in README; not enforced in schema.

---

## Historical document conflicts log (earlier README vs final code)

| Topic | Earlier README | Final code/status |
|-------|--------|----------------------|
| Workday sync availability | JC-010 Phase 1: no sync, 409 on manual | Full sync path + integration tests |
| Candidate corrections | "deferred; read-only" (JC-003 section) | `PUT .../confirmation` with revision |
| Discovery scope header | "Lever discovery" | LEVER + WORKDAY providers |
| Sample resume path | `$env:TEMP\job-copilot-sample-resume.pdf` | No committed sample PDF in repo |
| Compose filename | README says `docker compose` (works) | File is `compose.yaml` |
| Workday posting identity | Not frozen pending evidence | `opaqueId` accepted for V1 Beta after repeated multi-site and whole-board evidence |

---

*Generated from working tree inspection. Re-verify before interviews if the branch changes.*
