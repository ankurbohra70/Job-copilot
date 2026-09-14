# JC-009 — Authoritative Candidate Profiles & Live Job Recommendations

## 1. Executive Summary

JC-009 should turn the existing resume-derived candidate snapshot into a reviewed, editable, authoritative candidate profile and add the first product-level operation that returns ranked, currently live job opportunities for that profile.

This is the correct next ticket because the repository already has most of the mechanical pipeline:

- PDF upload, bounded PDFBox text extraction, deterministic resume parsing, `resumes` persistence, and `candidate_profiles` JSONB persistence exist from V3/JC-003.
- deterministic candidate-to-job scoring, single-job assessment, and in-memory multi-job ranking exist from JC-004/JC-006;
- canonical jobs and requirements exist from JC-005;
- JC-008 supplies durable source/listing identity, `LIVE`/`CLOSED` authority, version-aware extraction currency, application URLs, and live synchronization.

What is missing is product authority and orchestration. A parser-created profile cannot be corrected; matching can still consult the immutable raw extracted resume text; the current ranking query selects jobs only by `JobStatus` and therefore can include manually created jobs or closed/stale discovered jobs; and ranked responses do not expose JC-008 verification/application metadata.

The recommended ticket is therefore narrower and more accurate than the working title:

**JC-009 — Authoritative Candidate Profiles & Live Job Recommendations**

Resume ingestion is evolved, not rebuilt. JC-005, JC-006 scoring, JC-007 shadow intelligence, and JC-008 synchronization remain frozen except for narrow read/adaptation seams that are unavoidable to consume their existing authorities. Workday remains a separate pre-launch provider ticket.

## 2. Repository Findings

### 2.1 Candidate and resume model that exists today

The candidate representation is not absent or transient:

- `src/main/java/com/jobcopilot/resume/CandidateProfile.java` is a package-private JPA entity mapped to `candidate_profiles`. It owns a one-to-one, non-null, unique `resume_id`, stores `profileData` as JSONB, and records `schemaVersion`, `parserVersion`, `vocabularyVersion`, `assessedOn`, and `createdAt`. It has no update method, status, revision, or `updatedAt`.
- `src/main/java/com/jobcopilot/resume/CandidateProfileData.java` is the structured value object. It contains canonical skills, total and observed experience months, `KNOWN`/`UNKNOWN` experience assessment, work experience, education, projects, keywords, role categories, evidence, and warnings. Nested records retain source text. Collections are defensively copied.
- `src/main/java/com/jobcopilot/resume/Resume.java` persists filename, size, fixed media type, complete extracted text, page count, extractor version, and creation time. It does not store raw PDF bytes.
- `src/main/resources/db/migration/V3__add_resume_profiles.sql` creates `resumes` and `candidate_profiles`. The profile-to-resume relationship is one-to-one and cascades from resume deletion. `profile_data` is JSONB but only its non-nullness is enforced in PostgreSQL.
- `ResumeRepository` and `CandidateProfileRepository` are Spring Data repositories. The latter supports `findByResumeId` only; neither models a current/default candidate.

The current lifecycle is implemented by:

- `ResumeController.upload(...)` at `POST /api/resumes`, which requires exactly one multipart file;
- `ResumeService.upload(...)`, which validates extension, declared content type, actual `%PDF-` signature, configured byte limits, sanitizes the display filename, extracts text, parses it, and then persists;
- `PdfBoxResumeTextExtractor.extract(...)`, which rejects encrypted, malformed, image-only/unreadable, over-page-limit, and over-character-limit PDFs;
- `DeterministicProfileParser.parse(...)`, version `rules-v1`, which recognizes sections, vocabulary-backed skills/keywords/roles, work blocks, month-precise employment intervals, education source blocks, projects, evidence, and warnings;
- `ResumePersistenceService.save(...)`, whose short transaction atomically inserts the resume and profile;
- `GET /api/resumes/{id}` and `GET /api/candidate-profiles/{id}`, which return metadata/structured data but not extracted text.

The README explicitly says extracted text is retained for reprocessing, raw bytes are not stored, corrections are deferred, each upload produces an independent explicit profile, and no latest/current profile pointer exists (`README.md`, “Resume upload and candidate profiles” and “Matching”).

### 2.2 What exactly feeds matching

`ResumePersistenceService.matchingSnapshot(Long)` loads a `CandidateProfile` and creates `CandidateMatchingSnapshot(id, profile, extractedText, parserVersion, vocabularyVersion, assessedOn)`.

`JobAssessmentService.assess(...)` loads `JobMatchingSnapshot` through `JobService.matchingSnapshot(...)`, loads the candidate snapshot, invokes `DeterministicMatchingEngine.match(...)`, and projects the result. `MatchService` and `POST /api/matches` are a compatibility path to the same service. `POST /api/jobs/{jobId}/assessment` is the JC-006 job-scoped path.

The candidate input is therefore:

- persisted, not request-scoped;
- a JPA aggregate projected into a dedicated immutable matching snapshot;
- selected by explicit `candidateProfileId`;
- recomputed on demand, with no persisted assessment or ranking.

The engine actually uses these candidate facts:

| Candidate fact | Current scoring use |
|---|---|
| `profile.skills` | Primary recognized-skill set. |
| raw `resume.extractedText` | A second skill-evidence authority, including unknown vocabulary requirements; also the source for candidate keywords. |
| `totalExperienceMonths` | Experience score and comparison. |
| `workExperience[].sourceText` | Experience explanation evidence. |
| `roleCategories` | Role overlap. |
| `evidence` | Fallback skill explanation evidence. |
| `warnings` | Copied into result warnings. |
| `keywords` | Persisted but not used; the engine re-extracts keywords from raw resume text. |
| `observedExperienceMonths` | Persisted but not used by scoring. |
| `education` and `projects` | Not directly scored. Project technologies can indirectly enter `skills` because the parser scans the complete text. |

This raw-text fallback is the most important candidate-side integration finding. Once the user removes or corrects a parsed skill or keyword, matching must not silently reintroduce it from the original resume. The authoritative input boundary must therefore stop treating `Resume.extractedText` as scoring truth.

### 2.3 Matching, assessment, ranking, and explainability

- `DeterministicMatchingEngine` and `MatchingPolicy.v1()` are the only score/recommendation authorities. Weights are skills 60, experience 30, role 5, keywords 5, with documented required-skill caps.
- `MatchResult` already contains matched/missing required skills, matched/unmatched preferred skills, experience comparison, role and keyword relevance, weighted breakdown, caps, strengths, gaps, warnings, and unassessed factors.
- `MatchResponse` exposes the complete result and version/time metadata.
- `RankingMatchSummary` deliberately copies the engine result rather than recalculating it; it omits only category breakdown and role/keyword relevance.
- `JobRankingService.rank(...)` loads one candidate, calls `JobService.rankingSnapshots(statuses)`, assesses every returned job in memory, separates `MatchCannotBeComputedException` as unassessed, applies score/recommendation filters, globally sorts, and then slices the page.
- `JobRankingQuery` defaults to `DISCOVERED`, `SHORTLISTED`, `APPLIED`, and `INTERVIEWING`; explicit statuses override the default. Size is capped at 100.
- `JobRepository.findAllWithSkillsForRanking(...)` filters only on `jobs.status`. It has no join to listings or sources.
- `JobRankingSnapshot`/`RankingJobSummary` contain canonical job data and `jobUrl`, but no listing ID, source provider, `availability`, extraction state, `lastVerifiedAt`, hosted URL, or apply URL.

Assessments/rankings are deliberately non-persisted. Tests such as `JobAssessmentIntegrationTest.repeatedAssessmentIsReadOnly`, `candidateProfileSelectionStaysExplicitAfterAnotherUpload`, `changedRequirementsAreObserved`, and `JobRankingIntegrationTest.ranksStatusRestrictedJobsWithConstantReadQueryCountAndLeavesListingIntact` freeze those semantics.

### 2.4 JC-005, JC-007, and JC-008 integration facts

- Canonical job requirements are `job_skills` plus `jobs.min_years_experience` (V2). `JobRequirementExtractor`, `Job.replaceRequirements(...)`, and `JobRequirementsResponse` are JC-005 authorities.
- `DiscoveryJobWriter.processRequirements(...)` invokes the same deterministic job extractor during discovery. `rankingReady(...)` means at least one skill or a positive minimum-experience requirement.
- JC-007 production code is isolated under `intelligence`; it accepts job-side model input, has no candidate profile integration, and does not alter canonical requirements or matching. README and `JC-007-PHASE4-QA.md` explicitly freeze that isolation.
- V4 creates `job_sources`, `external_job_listings`, and sync runs. An external listing points one-to-one to a canonical `jobs.id`; source+external ID is unique.
- `ExternalJobListing` owns `availability`, hosted/apply URLs, verification timestamps, provider content digest, and the V5 extraction fingerprint.
- `JobSourceService.extractionState(...)` compares the persisted fingerprint with the current JC-005 extractor/version+description fingerprint. Its response calls a listing ranking-ready only when extraction is `CURRENT` and canonical requirements are non-empty/positive.
- `ListingAvailability` is the existing liveness authority (`LIVE`, `CLOSED`). `JobSource.enabled` controls whether future synchronization is allowed; README does not define it as invalidating already verified live listings.
- `JobSourceProvider` currently contains only `LEVER`, and `JobSource.region` currently uses `LeverRegion`. Those are real discovery-side obstacles to adding Workday, but JC-009 need not consume either field to remain provider-neutral.
- `JC-008-PHASE5-QA.md` confirms provider calls stay outside transactions, mutation phases use fenced short transactions, canonical job/listing identity survives close/reopen, prior requirements survive extraction failure, and matching/ranking remained isolated.

### 2.5 Tests and current verification baseline

Existing coverage includes:

- parser dates, overlapping employment, unknown chronology, canonical skills, source preservation (`DeterministicProfileParserTest`);
- real PDF extraction, image-only, encrypted, malformed, page/text/byte limits (`PdfBoxResumeTextExtractorTest`);
- upload validation, transaction-free extraction, sanitized filenames (`ResumeServiceTest`, `ResumeControllerTest`, `ResumeUploadIntegrationTest`);
- JSONB round-trip, atomic insert rollback, and cascade behavior (`ResumePersistenceIntegrationTest`);
- matching authority/parity/read-only behavior and explicit profile selection (`JobAssessment*`, `DeterministicMatchingEngineTest`);
- ranking ordering, filters, pagination, empty/unassessed behavior, and constant read-query behavior (`JobRanking*`);
- JC-008 liveness, fingerprint/readiness, reconciliation, and lease behavior (`discovery` tests);
- upgrade-path migrations in `SchemaMigrationIntegrationTest`.

The repository’s released baseline states 885 tests, zero failures/errors, and three intentional live-provider skips. A planning-time offline `mvn test` run completed successfully against the inspected tree and PostgreSQL 17.11 with exactly 885 tests, zero failures/errors, and three skips. The available IntelliJ JBR reported Java 25.0.3 for that inspection run, so this does not replace the required final Java 21 verification gate.

## 3. Current Gap

The current system cannot cleanly implement “Upload my resume and find the best live jobs for me” because:

1. Upload immediately persists parser output as the only profile; there is no draft/review/confirmation distinction.
2. There is no profile update API or domain validation for corrected data.
3. The resume is effectively still an authority because the matching snapshot includes raw extracted text and the engine independently scans it for skills and keywords.
4. A profile is structurally owned by exactly one resume. This is acceptable provenance, but there are no documented correction or re-upload semantics.
5. The current ranking endpoint means “rank jobs in selected application statuses,” not “rank currently live, verified, requirement-ready discovered listings.” It may include manual jobs and closed/stale discovered jobs.
6. Ranked results omit `applyUrl`, listing liveness, extraction readiness, and `lastVerifiedAt`, so they cannot present a verified application path.
7. Location/work-mode remains explicitly unassessed. The profile has no preference fields even for future filtering/presentation.
8. There is no stable profile revision in assessment/ranking output, so a mutable profile would be ambiguous without a small concurrency/version boundary.

## 4. Proposed JC-009 Scope

**Ticket name: JC-009 — Authoritative Candidate Profiles & Live Job Recommendations**

JC-009 will:

- retain and harden the existing PDF upload/extraction boundary;
- make upload create a reviewable `DRAFT` profile;
- add validated full-profile replacement that confirms a draft or corrects an already confirmed profile;
- make confirmed `CandidateProfileData` the only candidate fact authority used by matching;
- add optimistic profile revisioning and timestamps;
- add a provider-neutral read seam for live/current/ranking-ready canonical discovered jobs;
- add a synchronous on-demand “live opportunities” service/endpoint that reuses `DeterministicMatchingEngine` and existing ranking semantics;
- return deterministic match reasons/gaps plus verified listing/application metadata;
- preserve the existing legacy assessment and `/job-rankings` contracts unless only additive version metadata is required.

It is one major ticket with the implementation sequence in section 21.

## 5. Proposed End-to-End Architecture

```text
PDF multipart upload
        |
        v
ResumeService: byte/type/signature limits (no DB transaction)
        |
        v
PdfBoxResumeTextExtractor (no DB transaction)
        |
        v
DeterministicProfileParser -> validated CandidateProfileData draft
        |
        v
short transaction: resumes + candidate_profiles(status=DRAFT, revision=1)
        |
        v
GET profile -> user reviews
        |
        v
PUT complete corrected profile + expectedRevision
        |
        v
short transaction: validate/canonicalize -> status=CONFIRMED, revision++
        |
        +---------------- CandidateAssessmentInputAdapter ----------------+
                                                                         |
Lever ----\                                                              v
Workday ---+--> canonical discovery listing --> canonical Job + requirements
future ATS-/          | LIVE + CURRENT + ready + active status           |
                      +---------------- EligibleOpportunityReadModel -----+
                                                                         |
                                                                         v
                                              CandidateOpportunityService
                                                                         |
                                    deterministic JC-006 engine per job
                                                                         |
                                      sort/filter/page; do not persist
                                                                         v
                              ranked live opportunities + reasons + apply URL
```

The “Workday” line is an architectural compatibility target, not implementation scope.

## 6. Candidate Domain Design

### 6.1 Authority and lifecycle

Evolve the existing `CandidateProfile`; do not introduce separate `ResumeCandidate`, `AssessmentCandidate`, or `RankingCandidate` domain models.

Add `CandidateProfileStatus { DRAFT, CONFIRMED }`, `revision`, and `updatedAt` to `CandidateProfile`. New uploads create `DRAFT`. Existing V3 profiles migrate to `CONFIRMED` because they are already accepted by current matching APIs. A successful full update confirms a draft; later full updates keep it confirmed and increment the revision.

After confirmation, `candidate_profiles.profile_data` is authoritative. The associated resume and extracted text are provenance/reprocessing inputs only. Parser and extractor metadata describe the seed, not the authority of later corrections.

Ranking and assessment must reject a `DRAFT` profile with a stable `409 PROFILE_NOT_CONFIRMED`; profile retrieval remains allowed. This makes silent trust impossible without a large workflow engine.

### 6.2 Minimum profile data

Keep the existing `CandidateProfileData` shape as the basis and move it to schema `v2` only where additions/semantics require it. Recommended classification:

| Field | Decision/classification |
|---|---|
| `skills` | Keep; required for current matching. Canonical, unique, sorted, bounded. |
| `totalExperienceMonths`, `experienceAssessment` | Keep; required for current matching. `UNKNOWN` requires null total; `KNOWN` requires a bounded non-null total. |
| `workExperience` | Keep; required to support experience review and explanations, though scoring consumes the derived total. |
| `roleCategories` | Keep; required by current role relevance. Rename only if backward JSON migration is intentionally handled. |
| `keywords` | Keep and make authoritative; required by current keyword relevance once raw text is removed. |
| `evidence`, `warnings` | Keep; required for grounded explanations and extraction uncertainty. Evidence becomes provenance only, never an independent skill override. |
| `observedExperienceMonths` | Keep for backward compatibility/presentation of partial derivation, but not scoring authority. |
| `education`, `projects` | Keep; useful for review/presentation and future matching, not scored in JC-009. |
| `displayName` | Add as optional presentation data; no email/phone is needed for matching. |
| `headline` | Add as optional presentation/role-review data. |
| `currentLocation` | Add as optional near-term filtering/presentation data; not scored in JC-009. |
| `preferredLocations` | Add as optional near-term filtering input; expose but do not claim it is assessed until job locations are canonicalized. |
| `workModePreferences` (`REMOTE`, `HYBRID`, `ONSITE`) | Add as optional near-term filtering input; remain an unassessed factor in JC-009 unless exact canonical job metadata exists. |
| `targetRoles` | Add as optional near-term ranking/filtering input and presentation; do not silently replace observed `roleCategories` in frozen scoring. |
| links | Optional presentation-only. Permit a small typed/bounded list (for example LinkedIn/GitHub/portfolio) only if returned by the manual acceptance UI/client; otherwise defer. Recommended: defer from JC-009. |
| technology-specific experience | Future-only; there is no current scoring consumer and resume derivation would be unreliable. |
| salary expectations, notice period/availability | Future-only; omit. |
| phone, email, postal address | Not required; omit to reduce stored PII. |

### 6.3 Validation and updates

Add a `CandidateProfileValidator`/canonicalizer used by both parser output and update requests. Enforce realistic bounds: list sizes, string lengths, no control/NUL characters, canonical unique skills/keywords/roles, valid dates, start not after end, ongoing implies no end, experience 0–960 months, consistent assessment/total, and bounded source/evidence text. Reject the whole update on any invalid field; do not partially apply.

Use full `PUT`, not JSON Merge Patch. Nested JSONB patch semantics would be hard to validate and easy to misunderstand. The request includes `expectedRevision`; mismatch returns `409 PROFILE_REVISION_CONFLICT`. Persist with JPA `@Version` or an explicit revision comparison, but expose a simple integer revision in responses.

### 6.4 Single-user versus multi-user boundary

Continue explicit persisted `candidateProfileId` with no default/latest pointer and no authentication. This is already the repository convention and works for manual QA. Do not hardcode Ankur or create a singleton row.

This does not block multi-user support: a later auth ticket can add nullable-then-required `owner_user_id` plus an owner/profile index and authorize the same IDs. Adding a speculative `users` table now would provide no security because no authentication principal exists.

## 7. Resume Ingestion Design

Keep `POST /api/resumes` and the current limits/defaults: one PDF, 5 MiB application limit, 6 MiB request limit, 25 pages, 200,000 extracted characters, and 50 meaningful alphanumeric characters. Continue accepting declared `application/pdf` or `application/octet-stream` only when filename and magic signature also agree.

Retain the current processing order and behavior:

1. servlet request/file bounds;
2. exactly-one-file validation;
3. extension/content-type/signature checks;
4. bounded in-memory read;
5. PDFBox load/extraction with page/text limits;
6. deterministic profile seed extraction and validation;
7. sanitized display filename;
8. one short atomic persistence transaction.

Persist raw extracted text and source metadata, not raw resume bytes. This matches V3 and is sufficient for audit/reprocessing without adding object storage. Extracted text is sensitive and must remain absent from normal responses/logs. No filesystem path derived from a client filename is used; no permanent temporary file is needed.

Re-upload semantics: every upload creates a new resume and new draft profile. It does not mutate, delete, or implicitly supersede an existing profile. The caller explicitly adopts the new `candidateProfileId` after review. This preserves reproducibility and current explicit-selection semantics without a “current profile” table. Cleanup/deletion is out of scope until identity/auth ownership exists; do not expose resume deletion merely to solve retention policy prematurely.

## 8. Candidate Extraction Strategy

Use **deterministic resume-assisted prefill plus explicit user correction** for JC-009.

Rationale:

- `DeterministicProfileParser` already produces the facts the existing deterministic engine consumes and preserves source/warning evidence.
- It is local, bounded, cheap, retry-free, testable, and cannot hallucinate facts absent from the resume.
- Human confirmation is being added, so imperfect parsing becomes a correctable draft rather than trusted truth.
- JC-007 is job-side shadow intelligence with strict semantics. Reusing it would couple candidate extraction to the wrong domain and reopen a frozen ticket.
- A new LLM subsystem would require prompt/schema/provider/failure/privacy/evaluation work disproportionate to the V1 product need.

Introduce only a narrow candidate-side `CandidateProfileExtractor` interface if needed to separate orchestration from `DeterministicProfileParser`; the parser is its sole JC-009 implementation. This abstraction solves a present test/domain seam and prevents `ResumeService` from depending on a specific extraction mechanism; it must not include providers, retry frameworks, attempts, or persistence.

Grounding rules remain conservative: structured values must derive from extracted text, uncertain experience stays unknown, parser warnings/evidence are retained, and validation failure persists nothing. User edits are explicitly user-confirmed facts and may add information not in the resume.

## 9. Human Review / Correction Model

The minimal workflow is:

```text
upload -> persisted DRAFT -> GET -> PUT complete reviewed profile -> CONFIRMED
```

No separate draft table or history table is required. `status`, `revision`, `createdAt`, and `updatedAt` on the existing row are enough. `PUT` both replaces profile data and confirms it. Repeating an identical valid PUT may either increment revision or be semantic no-op; recommended: semantic no-op returns the current representation without incrementing revision, because revision represents changed authoritative facts.

Parser-derived evidence and warnings are response/provenance data, not directly writable client fields. The update DTO accepts corrected facts without `sourceText`; the server retains grounded resume evidence for unchanged facts and marks added/corrected facts as user-confirmed without inventing resume quotations. System metadata (`resumeId`, schema/parser/vocabulary versions, status, revision, timestamps) is also server-owned. On user correction, set profile schema to v2 and retain original parser/extractor metadata as provenance. Add an `authority`/`reviewed` response property through status rather than rewriting parser evidence as if it came from the resume.

An upload never changes an older profile. Old on-demand assessment responses are not persisted today, so no assessment invalidation table is needed. New calls always use the current profile revision and current job snapshot; responses must include `profileRevision` and `profileUpdatedAt` so results are reproducible as a description of their inputs.

## 10. Existing Assessment / Ranking Integration

Keep `DeterministicMatchingEngine`, `MatchingPolicy.v1`, job requirements, `MatchResult`, score caps, recommendation thresholds, sort order, and non-persistence frozen.

Add `CandidateAssessmentInputAdapter` between the authoritative profile aggregate and `CandidateMatchingSnapshot`. Its responsibilities are limited to:

- require `CONFIRMED`;
- copy authoritative structured data and revision metadata;
- create deterministic matching evidence from authoritative profile fields/evidence, not `Resume.extractedText`.

Recommended snapshot change: rename `extractedText` conceptually to `matchingEvidenceText` and have the adapter build it deterministically from confirmed profile skills, keywords, role/work/project facts, and evidence. This lets the existing engine retain its v1 token/evidence behavior without allowing an old resume to override user corrections. Tests must prove that removing a resume-derived skill/keyword from the confirmed profile removes its scoring effect.

If implementation shows this adapter would fabricate misleading evidence lines, make the smallest engine amendment instead: use `profile.skills`, `profile.keywords`, and `profile.evidence` directly. That is an unavoidable authority correction, not a scoring redesign; every legacy score fixture must remain unchanged for equivalent parser-generated profiles.

Add `profileRevision`/`profileUpdatedAt` additively to assessment and opportunity metadata. Do not persist assessments, add caches, call JC-007, or duplicate score calculations.

Preserve `GET /api/candidate-profiles/{id}/job-rankings` as the legacy status-based all-canonical-jobs ranking endpoint. It is heavily specified/tested and is not equivalent to live recommendations.

## 11. Job Eligibility

Define a single provider-neutral query for candidate-visible opportunities. A row is eligible only when:

1. it has an `external_job_listings` row linked to the canonical job;
2. `external_job_listings.availability = LIVE` (the JC-008 liveness authority);
3. extraction is current: persisted `extraction_fingerprint` equals the fingerprint of the current JC-005 extractor version plus the current canonical job description;
4. canonical requirements are ranking-ready: at least one `job_skills` row or positive `min_years_experience`;
5. canonical `JobStatus` is one of the active default statuses (`DISCOVERED`, `SHORTLISTED`, `APPLIED`, `INTERVIEWING`);
6. at least one safe application path exists, resolved as `applyUrl`, then `hostedJobUrl`, then canonical `jobUrl`.

Do not infer liveness from job status, last sync result, provider ID, or age. Do not create a second liveness column.

Do not require `job_sources.enabled`. In JC-008 that flag controls future synchronization, not the truth of the most recently verified listing; changing it should not retroactively close a listing. Return source/provider and `lastVerifiedAt` for transparency. Do not add an arbitrary freshness cutoff in JC-009 because no canonical freshness SLA exists; make a stale-age policy a genuine open product decision only if manual acceptance demands it.

Jobs that are live but pending/stale/unavailable extraction or insufficiently specified are excluded from ranked opportunities and counted by readiness reason in response metadata. They are not “zero matches.” CLOSED listings are excluded even if their canonical job status remains `DISCOVERED`. Manual jobs without listings remain available to legacy assessment/ranking APIs but are excluded from live opportunities.

## 12. Product Orchestration

Add `CandidateOpportunityService.find(...)` (conceptual name) rather than overloading `JobRankingService` semantics.

Execution:

1. load and validate the confirmed candidate snapshot first;
2. query all eligible provider-neutral opportunity snapshots in one bounded read transaction/query;
3. invoke the existing engine once for each eligible job;
4. treat the readiness query as authoritative, but still defensively classify `MatchCannotBeComputedException` rather than returning a false low score;
5. apply existing `minScore` and `recommendation` filters;
6. sort score descending, canonical job creation descending, job ID descending, matching legacy semantics;
7. assign global ranks and slice `page`/`size`;
8. project listing verification/application metadata and the existing `RankingMatchSummary`.

Keep it synchronous and on-demand. Assessments are cheap deterministic computations and the current ranker already scans all eligible jobs before pagination. Do not add a background pipeline, events, cached rankings, or assessment tables. Add a performance regression using a realistic fixture set (recommended 1,000 eligible jobs) and document observed latency/query count. If later production measurements show the synchronous scan is too slow, that is evidence for a separate optimization ticket.

Profile edits, job updates, requirement changes, close/reopen, and re-extraction need no invalidation mechanism because nothing is cached. Each request reads current authoritative state. Include the candidate revision, job `updatedAt`, listing `lastVerifiedAt`, algorithm version, vocabulary version, and extractor currency in the response.

## 13. API Contract

Keep existing routes and add the smallest coherent surface:

### `POST /api/resumes`

Existing multipart request. Response remains `201 Created` with resume metadata and nested profile, but the profile now includes:

```json
{
  "status": "DRAFT",
  "revision": 1,
  "schemaVersion": "v2",
  "updatedAt": "..."
}
```

### `GET /api/candidate-profiles/{id}`

Existing `200`/`404` contract. Returns editable profile data and server-owned lifecycle/version metadata. Never returns extracted resume text.

### `PUT /api/candidate-profiles/{id}`

Full replacement and confirmation:

```json
{
  "expectedRevision": 1,
  "profile": {
    "displayName": "Ankur ...",
    "headline": "Backend Engineer",
    "skills": ["java", "spring boot", "postgresql"],
    "experienceAssessment": "KNOWN",
    "totalExperienceMonths": 48,
    "observedExperienceMonths": 48,
    "workExperience": [],
    "education": [],
    "projects": [],
    "keywords": ["payments"],
    "roleCategories": ["backend"],
    "targetRoles": ["backend"],
    "currentLocation": "...",
    "preferredLocations": [],
    "workModePreferences": ["REMOTE"]
  }
}
```

Returns `200` with `status=CONFIRMED`. Invalid data is `400` with the existing validation error envelope. Missing profile is `404`; revision conflict is `409`. Client JSON cannot set IDs, resume association, parser/extractor versions, status, or timestamps.

### `GET /api/candidate-profiles/{id}/opportunities`

Query parameters: reuse `minScore`, repeatable `recommendation`, `page` (0), and `size` (20, max 100). Do not expose arbitrary terminal status override on this product endpoint; its contract is live active opportunities.

Response minimum:

```json
{
  "candidateProfileId": 1,
  "profileRevision": 2,
  "algorithmVersion": "deterministic-v1",
  "evaluatedJobCount": 42,
  "excludedNotReadyCount": 3,
  "filteredJobCount": 18,
  "page": 0,
  "size": 20,
  "opportunities": [{
    "rank": 1,
    "job": {
      "id": 10,
      "title": "Backend Engineer",
      "company": "Example",
      "location": "Remote",
      "status": "DISCOVERED"
    },
    "listing": {
      "listingId": 20,
      "sourceId": 3,
      "provider": "LEVER",
      "availability": "LIVE",
      "extractionState": "CURRENT",
      "rankingReady": true,
      "applicationUrl": "https://...",
      "lastVerifiedAt": "..."
    },
    "match": {
      "overallScore": 82.50,
      "recommendation": "STRONG_MATCH",
      "matchedRequiredSkills": [],
      "missingRequiredSkills": [],
      "matchedPreferredSkills": [],
      "unmatchedPreferredSkills": [],
      "experienceComparison": {},
      "appliedCaps": [],
      "strengths": [],
      "gaps": [],
      "warnings": [],
      "unassessedFactors": ["LOCATION_WORK_MODE"]
    }
  }]
}
```

Use `RankingMatchSummary.from(result)`; no presentation-layer scoring or LLM explanation. `200` with empty arrays/counts is the normal no-opportunity/no-filter-match state. Missing profile is `404`; draft profile is `409`; invalid query is `400`; an unexpected computation defect is sanitized `500` and fails the request rather than returning misleading partial ranking.

## 14. Persistence / Migration Plan

Add exactly one Flyway migration, expected `V7__make_candidate_profiles_authoritative.sql`:

| Change | Reason/invariant |
|---|---|
| `candidate_profiles.status VARCHAR(16)`: add nullable, backfill existing rows to `CONFIRMED`, make non-null, set the ongoing default to `DRAFT`, and add check `IN ('DRAFT','CONFIRMED')` | Existing rows remain usable, accidental new rows fail safe as drafts, and DB enforces lifecycle vocabulary. |
| `candidate_profiles.revision BIGINT NOT NULL DEFAULT 1` plus `CHECK (revision > 0)` | Stable optimistic concurrency and assessment input identity. |
| `candidate_profiles.updated_at TIMESTAMP(6)` backfilled from `created_at`, then `NOT NULL` | Records authority changes and supports result traceability. |
| optional Hibernate `@Version` mapped to `revision` | Atomically rejects lost updates; no special index required because updates address the primary key. |

Do not add candidate-skill, employment, education, project, assessment, ranking, or profile-version tables. JSONB matches the current write/read pattern: complete aggregate replacement, no server-side skill search, and no relational joins on candidate internals. Add service-level validation because PostgreSQL cannot reasonably enforce the nested v2 schema without duplicating application logic. Keep the one-to-one resume foreign key and existing unique constraint.

No index is needed for `status`: every current operation addresses profiles by primary key. No `users`/owner column is added without authentication. No external-listing schema change is needed; JC-008 already stores all eligibility/application facts. The provider-neutral opportunity query can join existing tables and compute fingerprint currency in application code, matching `JobSourceService`.

`SchemaMigrationIntegrationTest` must cover V6→V7 with existing profiles becoming confirmed at revision 1 and retaining JSON/resume relationships, plus fresh migration and check constraints.

## 15. Transaction Boundaries

- `ResumeController` and `ResumeService.upload(...)`: no transaction while reading multipart bytes, loading PDFBox, extracting text, or parsing/validating candidate data.
- `ResumePersistenceService.save(...)`: one ordinary short `@Transactional` unit for resume + draft profile insert. Failure rolls both back, preserving the existing test.
- profile `PUT`: parsing/Bean Validation occurs before the service transaction where possible; one short ordinary transaction loads with revision check, updates JSONB/status/metadata, and flushes. No `REQUIRES_NEW`.
- candidate matching snapshot load: short `readOnly` transaction; adapt to a detached immutable snapshot before computation.
- eligible opportunity snapshot load: one short `readOnly` transaction/query. It returns detached canonical job/listing projections; no transaction remains open during the N engine calls, sorting, or response projection.
- opportunity scoring: no transaction and no network access.
- JC-008 provider traversal, lease heartbeat, fencing, and `REQUIRES_NEW` mutation ownership remain unchanged.

Do not cargo-cult discovery lease transactions into profile ingestion. There is no external network/LLM operation in candidate extraction for JC-009.

## 16. Failure Model

| Condition | Status/behavior |
|---|---|
| missing/empty/not-exactly-one file | `400`; persist nothing |
| oversized file/request | `413`; persist nothing |
| wrong extension/type/signature | `415`; persist nothing |
| corrupt, encrypted, over-page/text-limit PDF | `422`; persist nothing |
| no meaningful extractable text/image-only | `422`; persist nothing |
| deterministic extraction produces partial facts | `201 DRAFT` with warnings/unknown fields; not a system failure |
| extraction/validation throws unexpectedly | sanitized `500`; log exception type/context only, not text; persist nothing |
| invalid profile correction | `400` field errors; no partial update |
| profile missing | `404` |
| draft used for assessment/opportunities | `409 PROFILE_NOT_CONFIRMED` |
| stale `expectedRevision` | `409 PROFILE_REVISION_CONFLICT`, return no sensitive state beyond current revision if desired |
| no eligible live jobs | `200`, zero counts and empty opportunities |
| live jobs exist but are not current/ready | `200`, empty/partial opportunities with aggregate excluded-readiness counts |
| eligible job unexpectedly becomes uncomputable between query and compute | classify as unassessed/not-ready in response; do not score zero |
| all scores filtered out or all are genuine zero matches | `200`, empty filtered result or ranked zero-score results according to query; distinguish via counts |
| unexpected engine/projection failure | sanitized `500`; no partial result |

Extend `ApiErrorHandler` with candidate validation/conflict handlers while preserving `ApiErrorResponse` and `ValidationErrorResponse`. Never expose parser stack traces, raw text, job provider bodies, or internal class names.

## 17. Security / Privacy

Minimum safeguards:

- retain servlet and application byte/page/character limits and actual PDF signature validation;
- retain exact-one-file behavior and client filename basename/control-character sanitization; never use it as a filesystem path;
- parse in memory with PDFBox limits; create no persistent temp files;
- do not store raw PDF bytes or add object storage in JC-009;
- never return or log extracted text, resume bytes, full user-entered profile JSON, evidence source blocks, filenames with paths, or application URL query strings;
- log stable profile/resume IDs and safe failure codes only;
- validate all nested strings/list cardinalities to limit JSONB and response amplification;
- omit email, phone, street address, and salary/notice data because the workflow does not need them;
- document that there is still no authentication/authorization and that the API must not be exposed publicly until the auth/deployment milestone;
- keep production credentials and real resumes out of the repository/test fixtures.

Retention/deletion policy is a required launch concern but not safely implementable as anonymous delete endpoints. Record it as a later auth/privacy ticket, not hidden scope.

## 18. Workday Compatibility

JC-009 must depend on canonical tables/projections only:

```text
external_job_listings -> jobs -> job_skills/min_years_experience
```

The opportunity service may expose `JobSourceProvider` as output metadata but must not branch on `LEVER`, import `discovery.lever.*`, consume Lever external IDs, pagination, payloads, regions, or gateways. Eligibility uses listing availability, extraction fingerprint/current canonical description, canonical requirements, job status, URLs, and timestamps.

That allows a later Workday synchronizer to create/update the same `JobSource`/`ExternalJobListing`/`Job` records and enter the downstream flow automatically.

Two current discovery details must be fixed in the separate Workday ticket, not JC-009: V4’s provider check permits only `LEVER`, and `JobSource.region` is typed as `LeverRegion`. Neither prevents JC-009’s provider-neutral downstream read model if JC-009 avoids provider-specific branching.

## 19. Testing Plan

### Unit tests

- `CandidateProfileValidatorTest`: canonicalization, duplicates, bounds, NUL/control text, date ordering, ongoing/end consistency, experience invariants, list limits.
- `CandidateProfileTest`: DRAFT creation, confirmation/correction, semantic no-op, revision/timestamp/status transitions.
- `CandidateAssessmentInputAdapterTest`: only confirmed profiles; deterministic output; removed resume skill/keyword cannot reappear; user-added fact does appear; evidence remains grounded.
- extend `ResumeServiceTest`: parser result validated before persistence; upload still has no active transaction; draft status delegated.
- opportunity eligibility projection/query tests: LIVE vs CLOSED, current vs stale/pending/unavailable extraction, ready vs unready requirements, active vs terminal status, URL fallback, disabled source does not invalidate verified LIVE state.
- `CandidateOpportunityServiceTest`: candidate loaded first, every eligible job uses the existing engine, sort/filter/page parity, unassessed classification, zero/empty states, no result recalculation.
- DTO/query tests for response immutability and invalid parameters.

### PostgreSQL integration tests

- V6→V7 and empty-schema migration; existing profile backfill; DB status/revision checks.
- JSONB v2 round-trip and old v1 JSON read/backfill strategy.
- atomic resume+draft creation rollback.
- complete update/confirmation and semantic no-op.
- two concurrent updates with the same expected revision: exactly one succeeds, one conflicts; no lost update.
- resume/profile foreign key and deletion behavior remains unchanged.
- eligible listing query against real V4/V5/V7 schema, including close/reopen and changed extraction fingerprint.

### API tests

- existing multipart success/error matrix, now asserting DRAFT/revision.
- GET draft and confirmed profile.
- PUT confirmation/correction, malformed nested data, forbidden server-owned fields if strict DTO mapping applies, missing ID, stale revision.
- draft assessment/ranking/opportunity conflict.
- live opportunities: ranked result, verified application URL, metadata, filters, pagination, no jobs, all not ready, all filtered, and sanitized internal failure.

### Cross-ticket regressions

- all current JC-005 extraction fixtures and `JobRequirementReleaseTest` unchanged;
- JC-006 assessment and `/api/matches` parity for equivalent confirmed parser profiles;
- all JC-007 contract/isolation/evaluation/provider tests unchanged; no candidate package import in intelligence;
- all JC-008 sync, close/reopen, fingerprint, scheduling, lease/fencing, and API tests unchanged;
- legacy job CRUD and `/job-rankings` behavior unchanged;
- deterministic engine golden scores unchanged for semantically identical profile input.

### End-to-end automated test

Create/synchronize fixture-backed canonical listings through the JC-008 seam; upload a real generated text PDF; assert DRAFT; correct/confirm profile; request opportunities; assert only LIVE+CURRENT+ready active listings appear, ordering equals direct engine results, CLOSED/stale/unready/manual jobs do not appear, explanations are engine-derived, and application URL/verification time come from the listing.

### Verification gates

Run the complete Maven suite on Java 21 and PostgreSQL 17.11, with zero failures/errors and only documented opt-in live-provider skips. Add a query-count/performance regression for 1,000 eligible jobs. Then run a separate terminal adversarial QA before release/freeze.

## 20. Manual Acceptance Plan

1. Start PostgreSQL 17.11 with `docker compose up -d` and run the application with Java 21.
2. Create/enable one or more real Lever sources through `POST /api/job-sources` and run `POST /api/job-sources/{id}/sync`; alternatively use fixture-backed sources when provider access is intentionally unavailable.
3. Verify sync success and inspect `/api/job-sources/{id}/listings?availability=LIVE`; record that candidate-visible fixtures are `CURRENT` and `rankingReady=true` and have an application path.
4. Upload Ankur’s actual current text-based PDF through `POST /api/resumes`; verify `201`, safe filename metadata, no extracted text in the body, and nested `DRAFT` profile revision 1.
5. Retrieve the profile. Compare skills, employment dates/total, education, projects, roles, keywords, warnings, and source-grounded facts with the resume.
6. Submit a complete corrected profile with `PUT /api/candidate-profiles/{id}` and `expectedRevision=1`. Verify `CONFIRMED`, revision advancement, and that deliberately removed parser mistakes are absent.
7. Request `/api/candidate-profiles/{id}/opportunities`. Verify only currently LIVE/current/ready active listings are evaluated.
8. Independently assess at least the top job through `POST /api/jobs/{jobId}/assessment`; score, recommendation, matched/missing skills, experience result, strengths, and gaps must agree.
9. Inspect several strong, weak, and zero-score results. Explanations must be deterministic facts, not prose unsupported by assessment output.
10. Open each returned `applicationUrl`; confirm it is the listing apply URL or documented hosted/canonical fallback and corresponds to the same live listing.
11. Close one fixture listing through a subsequent complete sync (or use a controlled reconciliation fixture), request opportunities again, and verify it disappears without changing the candidate or creating invalidation records. Reopen/update it and verify current state is observed.
12. Correct the profile again; verify the revision changes and new opportunities immediately reflect the new facts with no stored-ranking cleanup.
13. Confirm logs and repository contain no resume text, resume bytes, Ankur-specific fixture, secret, or sensitive URL query.

Automate steps 2–8 structurally with fixtures. Keep the real resume inspection, subjective correction quality, real provider/application-link opening, and PII/log audit as intentional manual acceptance.

## 21. Implementation Order

### Step 1 — Migration and aggregate lifecycle

- Add: `V7__make_candidate_profiles_authoritative.sql`, `CandidateProfileStatus.java`.
- Modify: `CandidateProfile.java`, `CandidateProfileResponse.java`, `SchemaMigrationIntegrationTest.java`, `ResumePersistenceIntegrationTest.java`.
- Tests: fresh/upgrade migration, backfill, constraints, draft/confirm/revision/concurrent update.
- Complete when existing profiles migrate confirmed and concurrent writes cannot be lost.

### Step 2 — Authoritative profile schema and validation

- Add: `CandidateProfileUpdateRequest.java`, `CandidateProfileValidator.java` (and small work-mode enum if included).
- Modify: `CandidateProfileData.java`, `CandidateProfile.java`, response DTOs.
- Tests: nested validation/canonicalization and backward v1 JSON fixtures.
- Complete when every parser/user profile accepted by persistence satisfies the same bounded invariants.

### Step 3 — Review/correction API

- Add: `CandidateProfileService.java` or evolve `ResumePersistenceService` with a clearly separated update method; candidate conflict exceptions.
- Modify: `CandidateProfileController.java`, `ApiErrorHandler.java`, controller/API integration tests.
- Tests: GET/PUT, draft-to-confirmed, confirmed correction, semantic no-op, 400/404/409, sanitized 500.
- Complete when an uploaded draft can be reviewed and safely made authoritative with optimistic concurrency.

### Step 4 — Matching authority adapter

- Add: `CandidateAssessmentInputAdapter.java` if implementation confirms it is the cleanest seam.
- Modify: `CandidateMatchingSnapshot.java`, `ResumePersistenceService.matchingSnapshot(...)`, minimally `DeterministicMatchingEngine` only if structured direct consumption is necessary; assessment/ranking response metadata.
- Tests: old score parity; raw resume no longer overrides corrections; draft rejection; profile revision metadata.
- Complete when only confirmed profile facts affect matching and all frozen deterministic fixtures remain equivalent.

### Step 5 — Provider-neutral eligible opportunity read seam

- Add: `EligibleOpportunitySnapshot.java`, `EligibleOpportunityReadService.java` and repository projection/query in the discovery/job boundary.
- Modify: `ExternalJobListingRepository.java` only as a read addition; reuse fingerprint/readiness authorities rather than duplicating formulas where possible.
- Tests: all liveness/currency/readiness/status/URL/source-enabled combinations; constant query count.
- Complete when the seam returns canonical job snapshots plus listing metadata and imports no Lever implementation type.

### Step 6 — Product orchestration and contract

- Add: `CandidateOpportunityService.java`, `CandidateOpportunityController.java`, `CandidateOpportunityQuery.java`, opportunity response DTOs.
- Reuse: `DeterministicMatchingEngine`, `RankingMatchSummary`, legacy comparator/filter semantics where practical without changing legacy endpoint behavior.
- Tests: service, controller, PostgreSQL integration, error/empty/count/pagination behavior.
- Complete when confirmed candidates receive correctly ordered live/current/ready canonical opportunities with verified paths and deterministic reasons.

### Step 7 — Cross-ticket E2E, docs, manual QA

- Add: one candidate-to-discovery E2E integration test; JC-009 terminal QA document only during the QA phase.
- Modify: `README.md` API/config/manual instructions and current-scope limitations.
- Tests: full suite, Java 21/PostgreSQL 17.11, performance/query-count fixture, intentional provider skips only.
- Complete when the manual Ankur-resume/live-job scenario passes, regression suite is green, privacy checks pass, and terminal adversarial QA recommends release/freeze.

## 22. Explicit Non-Goals

JC-009 will not build:

- Workday, Greenhouse, Ashby, or another ATS connector;
- frontend screens;
- authentication, authorization, users, or multi-tenancy;
- deployment/hosting;
- auto-apply, browser automation, resume tailoring, cover letters, application tracking, recruiter outreach, or notifications;
- OCR or non-PDF document ingestion;
- raw resume object storage;
- LLM candidate extraction/explanations, embeddings, vector search, chat, or a generalized agent framework;
- persisted/cached assessments or rankings, queues, schedulers, distributed jobs, or invalidation events;
- technology-specific experience scoring, salary/notice matching, or speculative portal fields;
- redesign of JC-005 requirements, JC-006 scoring, JC-007 intelligence, or JC-008 synchronization/lease architecture.

## 23. Risks / Open Decisions

### 1. Authoritative matching evidence representation

Risk: the v1 engine consumes raw extracted text as a secondary authority. A synthetic adapter text can preserve algorithm structure but may make evidence wording awkward.

Recommended decision: implement the adapter first; if evidence cannot stay truthful, make the minimal direct-structured-input engine amendment and freeze it with parity tests. Never keep raw resume fallback after confirmation.

### 2. Backward JSON schema handling

Risk: existing JSONB rows contain v1 data and record deserialization can break if new fields are non-defaulted.

Recommended decision: additions are nullable/empty-by-default in the JSON contract; migrate relational lifecycle columns only. Treat existing rows as confirmed v1, and convert to v2 on first PUT rather than rewriting JSON in SQL.

### 3. Source freshness

Risk: a listing may remain `LIVE` when its source has not synchronized recently. JC-008 records `lastVerifiedAt` but defines no expiry SLA.

Recommended decision: do not invent a hard cutoff in JC-009. Return `lastVerifiedAt`; require a successful fresh sync in manual acceptance. Define an expiry policy only with a product SLA, ideally in the multi-provider discovery roadmap.

### 4. Synchronous ranking volume

Risk: ranking all eligible jobs before pagination is O(n) and materializes results.

Recommended decision: retain the current synchronous design and gate with a 1,000-job performance/query-count test. Do not add background persistence without measured failure.

### 5. Optional preference fields

Risk: storing location/work-mode/target preferences may imply they affect ranking when job-side location is free text and the engine lists location/work mode as unassessed.

Recommended decision: add them as clearly classified near-term inputs and return the existing unassessed factor; do not score them in JC-009. If the implementation team wants the absolute smallest schema change, these additions may be deferred without blocking the ticket’s core acceptance.

These are implementation choices with recommendations, not blockers to starting.

## 24. Definition of Done

JC-009 is done only when:

- V7 migrates fresh and V6 databases safely; existing profiles remain usable;
- upload creates a bounded, persisted draft and never holds a DB transaction during file parsing;
- a client can retrieve, fully correct, and confirm the profile with validated optimistic concurrency;
- confirmed profile data, not raw resume text, is the candidate matching authority;
- draft profiles cannot be assessed/ranked/recommended;
- legacy assessment, `/api/matches`, all-jobs ranking, job CRUD, and deterministic score semantics retain regression coverage;
- the new opportunity endpoint selects only canonical jobs attached to `LIVE`, extraction-current, ranking-ready listings in active job statuses;
- results use existing engine output for scores/reasons and include verified listing/application metadata;
- closed, stale, unready, terminal, and manual-only jobs are correctly excluded and counted/characterized;
- opportunities remain provider-neutral and import no Lever gateway/payload/ID/pagination type;
- no assessments/rankings are persisted and current profile/job/listing changes are observed on the next request;
- stable 400/404/409/413/415/422/500 and normal-empty-state behavior is tested;
- privacy/logging checks show no raw bytes/text, real resume, credentials, or sensitive URL details leak;
- unit, API, PostgreSQL integration, E2E, query-count/performance, and complete cross-ticket suites pass on Java 21/PostgreSQL 17.11;
- the concrete manual Ankur resume → correction → live opportunities → explanations → verified apply-link exercise passes;
- terminal adversarial QA recommends release and freeze;
- Workday remains a separate mandatory pre-launch ticket and can feed this flow through canonical discovery records.

## 25. Final Planning Verdict

**READY TO IMPLEMENT WITH AMENDMENTS**

The repository is ready for JC-009 implementation. The amendments to the working theme are to evolve rather than recreate the existing resume/profile domain, make profile authority explicit, isolate raw resume text from scoring, and add a separate live-opportunities contract instead of silently changing the frozen legacy ranking endpoint.
