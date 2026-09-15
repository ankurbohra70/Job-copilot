# JC-009 — Authoritative Candidate Profiles & Live Job Recommendations

## 1. Executive Summary

JC-009 completes the candidate-to-live-job product flow on top of the repository as it exists after the ApplyPilot-inspired application-readiness work.

The repository already has bounded PDF ingestion, deterministic resume parsing, candidate and search-preference persistence, deterministic matching/ranking, canonical job requirements, live listing synchronization, application decisions, readiness checks, and approved resume routes. JC-009 connects and hardens those pieces rather than replacing them.

The ticket will:

- make a reviewed, confirmed `CandidateProfile` the authority for who the candidate is;
- keep `JobSearchPreference` as the separate authority for what jobs the candidate wants;
- introduce `CandidateProfileExtractor`, backed in production only by deterministic extraction;
- make matching consume confirmed structured fields directly, with no raw-resume or synthetic-text scoring fallback;
- return ranked live opportunities with the existing `ApplicationDecision` and `ApplicationReadiness` results;
- add a small, offline/test-scoped evaluation foundation for the deterministic pipeline.

JC-005/JC-006 scoring semantics, JC-007, and JC-008 remain frozen. Workday remains a separate mandatory pre-V1 ticket.

## 2. Current Repository Findings

### 2.1 Candidate identity, resume data, and preferences

The original plan predates commit `64084f4` (`ApplyPilot integrations`). The current repository now contains:

- `CandidateProfile`, which may be resume-backed or manually created. It stores resume-derived `CandidateProfileData` JSON plus relational `CandidateProfileFacts`, and has `createdAt`/`updatedAt` but no confirmation status or revision.
- `CandidateProfileData`: skills, experience assessment/history, education, projects, keywords, observed roles, evidence, and warnings.
- `CandidateProfileFacts`: name/contact/current-state fields, explicit relevant experience, authorization/sponsorship facts, relocation, notice, and compensation.
- a separate `JobSearchPreference` aggregate: target/excluded roles, preferred locations, acceptable work arrangements, experience tolerances, freshness policy, and default resume strategy.
- full-replacement APIs for manual candidate facts and search preferences, immutable resume routes, and V7 persistence for these concepts.

The separation is directionally correct, but compensation expectations and relocation willingness are job-search intent currently placed in `CandidateProfileFacts`. JC-009 should move their authority to `JobSearchPreference`, preserving existing values during migration. Notice period is current candidate state and remains on `CandidateProfile`.

Resume extraction may populate observed facts such as skills, work history, current title, or current location when supported by text. It must never populate target/excluded roles, preferred locations, acceptable work arrangements, compensation targets, relocation willingness, freshness, or resume strategy.

### 2.2 Current matching authority

`ResumePersistenceService.matchingSnapshot(...)` currently supplies `CandidateProfileData`, the linked resume's full extracted text, and an ad hoc experience override from `CandidateProfileFacts.totalRelevantExperienceMonths`. `DeterministicMatchingEngine` uses structured skills, experience, and roles, but also scans raw text for known/unknown skills, derives keywords from it, and uses it for evidence.

A corrected profile is therefore not authoritative: removed facts can reappear from the immutable resume.

The existing scoring policy remains authoritative: skills 60, experience 30, role 5, keywords 5, existing required-skill caps, recommendation thresholds, explanations, and unassessed-factor behavior. Assessments and rankings remain on-demand and unpersisted.

JC-009 changes only the candidate input boundary needed for structured authority. It must not create `matchingEvidenceText` or another synthetic prose representation. Evidence may explain a match already established from an authoritative structured field; it may not independently create a match.

### 2.3 Current decision and readiness semantics

The application layer already exists and must be reused without renaming or replacing its states.

`ApplicationDecision` is `SKIP`, `SAVE`, `APPLY_VOLUME`, `APPLY_PRECISION`, or `NEEDS_USER`. Policy `application-decision-v1` maps `NOT_RECOMMENDED -> SKIP`, `WEAK_MATCH -> SAVE`, `GOOD_MATCH -> APPLY_VOLUME`, and `STRONG_MATCH -> APPLY_PRECISION`. An excluded role forces `SKIP`; a configured precision default can promote `APPLY_VOLUME`; missing preference, authorization, or sponsorship facts changes only an otherwise-apply result to `NEEDS_USER`. Sponsorship `YES` is conservatively deferred to readiness.

`ApplicationReadiness` is `READY`, `NEEDS_USER`, or `NOT_READY`. Its checks are `PASS`, `FAIL`, `NEEDS_USER`, or `NOT_APPLICABLE`. The current service checks pursuit state, verified liveness/freshness, current requirements, application URL, assessment/ranking input, identity/email, location preference, authorization, sponsorship, compensation when configured, experience, required skills, approved resume route, and decision. Any hard failure yields `NOT_READY`; otherwise a candidate-owned unknown yields `NEEDS_USER`; only all applicable passes yields `READY`. Reads are observational in a read-only repeatable-read transaction.

JC-009 must reuse these services/policies or extract pure snapshot policies with exact parity. It must not create competing decision/readiness vocabularies.

### 2.4 Frozen foundations

- JC-005 canonical requirements remain `job_skills` plus `jobs.min_years_experience`, populated by `JobRequirementExtractor`.
- JC-006 matching/assessment and ranking behavior remain frozen except for the narrow structured-candidate input seam.
- JC-007 remains isolated and frozen. Reuse its evaluation lessons, not its production subsystem.
- JC-008 remains authoritative for listing identity, `LIVE`/`CLOSED`, extraction currency, verification time, and application URLs. Synchronization, leases, fencing, and transactions remain unchanged.
- Lever is the current provider. Workday requires separate provider/schema work and remains mandatory before V1.

### 2.5 Gaps

1. Profiles have no draft/confirmed lifecycle or optimistic revision.
2. Resume-derived matching data cannot be fully corrected through the API.
3. Raw resume text is a second scoring authority.
4. Candidate input uses an experience-only overlay instead of one explicit authoritative snapshot.
5. `CandidateProfileExtractor` does not exist; `ResumeService` depends on `DeterministicProfileParser`.
6. Ranking is status-based, not a provider-neutral live-opportunity flow.
7. Decision/readiness are job-by-job rather than part of ranked live opportunities.
8. No offline evaluation foundation covers the full deterministic pipeline.

## 3. Scope

JC-009 will retain the current PDF boundary; introduce deterministic candidate extraction behind an abstraction; create reviewable resume-backed drafts; add validated full profile confirmation/correction with revisioning; preserve manual/existing profile compatibility; enforce the profile/preference boundary; switch matching to structured authority with scoring parity; add provider-neutral live opportunities; include existing decision/readiness results; and add offline golden evaluation.

No production evaluation service, API, persistence, scheduler, background job, or model dependency is in scope.

## 4. Intended Architecture

```text
PDF upload
  -> existing limits -> PDFBox ResumeTextExtractor
  -> CandidateProfileExtractor
       production: deterministic implementation only
  -> short transaction: Resume + CandidateProfile(DRAFT, revision=1)

review/correction -> validate -> confirm/update CandidateProfile (revision++)

CandidateProfile (who the candidate is) -----------+
                                                    +-> structured CandidateMatchingInput
JobSearchPreference (what jobs are wanted) --------+        |
                                                             v
JC-008 LIVE/current listing -> canonical JC-005 Job -> JC-006 match/rank
                                                             |
                                  existing ApplicationDecision policy
                                                             |
                                  existing ApplicationReadiness policy
                                                             v
                         live opportunities + match + decision + readiness

test scope only:
golden cases -> extraction/matching/decision evaluators -> deterministic metrics
```

Raw resume text remains provenance/reprocessing input only and does not cross the confirmed matching boundary.

## 5. Domain and Authority Model

### 5.1 CandidateProfile: who the candidate is

Add `CandidateProfileStatus { DRAFT, CONFIRMED }`, an optimistic `revision`, and confirmation/update timestamps.

Candidate authority includes structured skills, experience, work history, education, projects, keywords, observed role categories, evidence/warnings; identity/contact/current location/title; relevant experience; work authorization; sponsorship requirement; and notice period. Extraction/schema/vocabulary versions and assessed date remain provenance metadata.

Observed `roleCategories` describe capability/history, not desired roles. Evidence and warnings describe provenance/uncertainty and never override authoritative fact lists.

New resume-backed profiles are `DRAFT`. Existing profiles are backfilled `CONFIRMED`. Manual profiles from the explicit user-input API may remain immediately `CONFIRMED`, but must not pretend their empty matching data was extracted. A complete correction/confirmation updates structured matching data and candidate facts. Every scoring- or readiness-relevant update advances the revision; stale writes fail atomically.

Assessment, ranking, decision, readiness, and opportunities reject `DRAFT`; retrieval/correction remain available.

### 5.2 JobSearchPreference: what jobs are wanted

`JobSearchPreference` independently owns target/excluded roles, preferred locations, acceptable work arrangements, experience tolerances, freshness, default resume strategy, compensation targets, and relocation willingness.

Preferences are user-authored and never written by resume extraction. Missing preferences remain missing. Preference changes can affect eligibility, decisions, readiness, or presentation, but never mutate candidate facts or the JC-006 match score. Responses include enough profile revision and preference metadata to identify evaluated inputs.

### 5.3 CandidateProfileExtractor

Add a small production abstraction such as:

```java
interface CandidateProfileExtractor {
    CandidateProfileExtraction extract(String text, LocalDate assessedOn);
}
```

The result contains `CandidateProfileData`, extractor/parser version, vocabulary version, and warnings. The only JC-009 production bean is an adapter around, or evolution of, `DeterministicProfileParser`.

Do not add a provider registry, attempts, retries, prompts, model client, or fallback chain. Offline tests may compare future deterministic/hybrid/LLM implementations, but JC-009 adds no production LLM extraction or mandatory model calls.

### 5.4 Structured matching input

Replace `CandidateMatchingSnapshot(..., extractedText, ...)` with an immutable input containing confirmed structured skills, total experience, work-experience explanation evidence, observed roles, keywords, warnings, and version/revision metadata.

The engine matches only from structured skills/keywords/experience/roles. Evidence explains only facts matched from those fields. It never receives `Resume.extractedText` or synthetic matching text.

Weights, normalization, caps, thresholds, missing/unmatched behavior, ordering, and unassessed factors remain unchanged. Parity means identical outputs when confirmed structured facts represent what the old engine recognized. Correction tests explicitly prove raw-only or removed facts no longer match; that authority correction is not a scoring-policy change.

## 6. Product Flow

### 6.1 Ingestion and confirmation

1. Validate/extract the PDF outside a transaction.
2. Run deterministic `CandidateProfileExtractor` outside a transaction.
3. Validate/canonicalize its result.
4. Persist resume provenance and a revision-1 draft atomically.
5. Return the draft without raw text.
6. Retrieve and fully review/correct candidate fields.
7. Confirm with the expected revision; later corrections use the same complete update.
8. Manage search preferences independently through their existing resource.

### 6.2 Live opportunities

For a confirmed profile:

1. Load one consistent profile/preference snapshot.
2. Load provider-neutral canonical jobs attached to JC-008 listings that are `LIVE`, extraction-`CURRENT`, ranking-ready, and in permissible pursuit states.
3. Run the existing engine and preserve ranking order, tie-breaks, filters, pagination, zero-score, and unassessed semantics.
4. Derive `ApplicationDecision` through `application-decision-v1`, never independently from the numeric score.
5. Derive `ApplicationReadiness` with existing checks/states. Extract snapshot policies only if needed for batch efficiency, with job-scoped parity.
6. Return match explanations, decision, readiness, listing/application metadata, profile revision, and preference metadata. Persist nothing.

Decision/readiness do not change score or ranking. By default, results may show all three readiness states so blockers are visible; optional filters use the existing enums.

## 7. API and Persistence

Preserve current resume, candidate-profile, preference, resume-route, assessment/matching/ranking, decision, and readiness endpoints.

Add the smallest explicit contracts:

- `PUT /api/candidate-profiles/{id}/confirmation`: complete structured candidate data plus candidate facts and `expectedRevision`; no preferences or server-owned provenance. Returns status/revision metadata. Invalid input is `400`, missing is `404`, stale/invalid lifecycle state is `409`.
- `GET /api/candidate-profiles/{id}/opportunities`: bounded ranking-compatible filters/pages. Each row includes canonical job/listing metadata, match output, `ApplicationDecisionResult`, and `ApplicationReadinessResult`, plus counts distinguishing no live, excluded, unassessed, and filtered jobs.

A separate confirmation endpoint avoids silently breaking the current facts-only `PUT`. Evolving that endpoint instead is acceptable only with demonstrated compatibility and the same authority separation. No API accepts raw resume text, synthetic matching text, evaluation settings, prompts, or model selection.

Use the next migration after current V7. Add lifecycle/revision fields, backfill existing profiles confirmed, enforce constraints, and move/backfill preference-owned compensation/relocation without data loss. Compatibility columns may remain temporarily, but only one read authority may exist.

Do not add tables for evaluation, assessment, ranking, decision, or readiness. PDF/extraction stays transaction-free; draft persistence and confirmation are short transactions; opportunity inputs use a consistent read-only snapshot; matching/sorting/metrics do no writes or network/model calls; JC-008 transaction boundaries are unchanged.

## 8. Offline Evaluation Foundation

Keep evaluation under `src/test` with versioned human-labelled cases in `src/test/resources`. Reuse JC-007 lessons about fixtures, semantic comparison, provenance, failure taxonomy, and reproducibility without importing or modifying its production subsystem.

| Stage | Golden labels | Minimum deterministic metrics |
|---|---|---|
| Candidate extraction | skills, keywords, observed roles, experience/date/unknown state, selected identity facts, warnings | exact field accuracy; precision/recall/F1 for sets; experience/date and unknown-state accuracy |
| Job-requirement extraction | required/preferred skills, minimum experience, unextractable cases | precision/recall/F1; exact/within-tolerance experience accuracy; false-positive/abstention rates |
| Matching/ranking | matched/missing facts, policy-golden score/recommendation, graded relevance/order | score/recommendation parity; boundary errors; Precision@K and nDCG@K |
| Decision/readiness | decision, readiness, material check statuses | decision/readiness accuracy; per-state confusion counts; false-skip and false-apply rates |

False-skip means system `SKIP` against a human apply-worthy label. False-apply means `APPLY_VOLUME`/`APPLY_PRECISION` or `READY` against a human do-not-apply label; report decision and readiness variants separately.

Golden scores are the guard for frozen scoring. Ranking metrics evaluate quality but do not tune production policy in JC-009. Evaluation failures affect tests/reports only.

LLM-as-judge may be documented later as optional qualitative analysis. It is never canonical, required by the default build, production authority, or allowed to change profiles, requirements, scores, decisions, or readiness.

## 9. Testing Plan

### Unit/contract

- lifecycle, revision conflicts, validation, and full replacement;
- profile/preference ownership and proof extraction never writes preferences;
- extractor contract and deterministic-only production wiring;
- structured input has no raw/synthetic text field;
- removed facts cannot reappear; confirmed additions can match;
- exact JC-006 parity for semantically equivalent inputs;
- unchanged decision mapping/precedence and readiness aggregation/check semantics;
- opportunity ordering/filter/pagination/unassessed behavior and decision/readiness projection;
- offline metric and dataset-validation tests.

### Integration/API

- fresh schema and V7 upgrade/backfill without data loss;
- atomic draft upload with parsing outside the transaction;
- documented manual/existing compatibility;
- confirmation and concurrent revision updates;
- independent preference replacement and absence from resume-derived JSON;
- confirmed changes immediately affect assessment, ranking, decision, readiness, and opportunities;
- live/current/ready inclusion and closed/stale/unready/manual/terminal exclusion;
- opportunity parity with job-scoped assessment/decision/readiness;
- observational reads and no persisted computed/eval results;
- bounded query/performance coverage.

### Frozen regressions and E2E

Retain JC-005 extraction fixtures; JC-006 formula/caps/thresholds/parity/order/read-only tests; all JC-007 isolation/evaluation/provider tests; all JC-008 sync/fingerprint/scheduling/lease/API tests; and current application/readiness/resume-route tests.

E2E: upload a real text PDF, receive a draft, correct/confirm it, set preferences independently, request opportunities, verify live eligibility and direct-engine ordering, verify job-scoped decision/readiness parity, prove removed resume facts stay removed, and verify application metadata comes from the same listing.

Run the full suite on required Java/PostgreSQL versions plus adversarial QA. Offline evaluation must need no credentials or network.

## 10. Implementation Order

1. Add golden/parity baselines for extraction, JC-006 scoring, decisions, and readiness.
2. Add `CandidateProfileExtractor`; wire only deterministic extraction.
3. Add lifecycle/revision, preference ownership corrections, validation, and migration compatibility.
4. Add complete structured confirmation/correction.
5. Remove raw text and the experience-only overlay from matching input; satisfy parity/correction tests.
6. Add the provider-neutral live listing/job read seam.
7. Add opportunity orchestration reusing ranking, decision, and readiness.
8. Add small offline datasets, runners, and deterministic metrics for all four stages.
9. Run migration, cross-ticket, privacy, performance, E2E, and adversarial verification.

## 11. Non-Goals

JC-009 does not include:

- JC-005/JC-006 scoring changes;
- reuse/modification of JC-007 production;
- JC-008 synchronization/availability/currency/lease changes;
- Workday implementation (still mandatory pre-V1);
- frontend, auth, users/multi-tenancy, or deployment;
- auto-apply, browser automation, submission, answer banks, resume tailoring/generation, cover letters, outreach, or notifications;
- embeddings, vector search, chat, generalized agents, or production LLM candidate extraction;
- OCR/non-PDF ingestion or raw resume storage;
- persisted/cached assessments, rankings, decisions, readiness, or eval results;
- production eval APIs/tables/schedulers/queues/jobs or mandatory model calls.

## 12. Risks and Resolved Decisions

- **Structured parity versus corrected authority:** raw-only matches disappear intentionally after confirmation. Keep policy math frozen and distinguish parity fixtures from correction fixtures; never hide raw text in synthetic evidence.
- **Existing V7 placement:** preserve compensation/relocation values while assigning `JobSearchPreference` as their sole authority.
- **Batch consistency:** avoid N+1/inconsistent reads by extracting snapshot policies behind existing services if necessary, with exact endpoint parity.
- **Freshness:** reuse current `freshnessDays` readiness behavior; do not invent another expiry rule.
- **Synchronous volume:** retain bounded in-memory ranking and measure it; do not add background/persisted ranking.
- **Evaluation size:** start small and human-labelled. Version fixtures/metrics; optional judges cannot rewrite labels.

## 13. Definition of Done

JC-009 is complete only when:

- profile and preference have one enforced authority boundary and extraction cannot write search intent;
- upload uses `CandidateProfileExtractor` with only deterministic production implementation;
- uploads create drafts and confirmed/manual/backfilled lifecycle plus optimistic revision is tested;
- complete structured confirmation/correction excludes preferences;
- assessment, ranking, decision, readiness, and opportunities consume the same confirmed structured authority;
- raw resume text and synthetic evidence cannot affect score, terms, recommendation, or order;
- JC-005/JC-006 semantics have golden parity plus explicit correction coverage;
- opportunities use provider-neutral JC-008 live/currency/verification/application metadata and JC-005 requirements;
- every opportunity exposes existing decision/readiness states/checks with job-scoped parity;
- offline tests evaluate all four stages with the stated deterministic metrics;
- no production eval surface/model call or persisted computed recommendation exists;
- JC-007/JC-008 regressions remain green;
- Workday remains separate and mandatory pre-V1;
- migration, full suite, E2E, privacy, bounded-volume, and adversarial QA pass.

## 14. Final Planning Verdict

**READY TO IMPLEMENT**

JC-009 is now a focused authority-and-orchestration ticket: confirm structured candidate truth, keep search intent separate, preserve deterministic policies, expose existing decision/readiness in live recommendations, and measure quality offline without creating a production evaluation subsystem.
