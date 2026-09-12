# Job Copilot

Job Copilot is an AI-powered job search workspace intended to help candidates discover, evaluate, tailor for, prepare for, and track job applications.

The backend currently covers job management plus JC-003 resume extraction, JC-004 matching/ranking, JC-005 job-requirement extraction, and JC-006 on-demand single-job assessment. JC-008 adds the discovery persistence foundation and a transport-only Lever connector:

```text
HTTP → JobController → JobService → JobRepository → JPA/Hibernate → PostgreSQL
```

```text
PDF Resume
  → ResumeTextExtractor (PDFBox)
  → DeterministicProfileParser
  → CandidateProfile
  → Job requirements
  → DeterministicMatchingEngine
  → Explainable MatchResult
```

Schema changes are applied with Flyway. Hibernate `ddl-auto` is `validate` only.

## Current scope

Job APIs still support creation, retrieval, replacement, status changes, deletion, pagination, sorting, title/company search, and status filtering. JC-003 adds job matching requirements, PDF resume upload, candidate profiles, and on-demand matching. JC-004A adds on-demand multi-job ranking for a stored candidate profile. JC-004B productizes that ranking with default status eligibility, explicit status override, score/recommendation filters, and paginated ranked results. JC-006 adds an explicit job-scoped assessment endpoint; `POST /api/matches` remains a compatibility entry point into the same single-job orchestration.

| Method | Endpoint | Purpose |
|---|---|---|
| `POST` | `/api/jobs` | Create a job in `DISCOVERED` status |
| `GET` | `/api/jobs/{id}` | Retrieve one job |
| `GET` | `/api/jobs` | List, search, filter, sort, and paginate jobs |
| `PUT` | `/api/jobs/{id}` | Replace editable job details while preserving identity and status |
| `PATCH` | `/api/jobs/{id}/status` | Change a job's status |
| `DELETE` | `/api/jobs/{id}` | Delete a job |
| `GET` | `/api/jobs/{id}/requirements` | Retrieve matching requirements |
| `PUT` | `/api/jobs/{id}/requirements` | Replace matching requirements |
| `POST` | `/api/resumes` | Upload a text-based PDF resume |
| `GET` | `/api/resumes/{id}` | Retrieve resume metadata and linked profile |
| `GET` | `/api/candidate-profiles/{id}` | Retrieve the extracted candidate profile |
| `GET` | `/api/candidate-profiles/{id}/job-rankings` | Rank status-eligible jobs for that profile (not persisted) |
| `POST` | `/api/jobs/{id}/assessment` | Assess one stored job against an explicit candidate profile (not persisted) |
| `POST` | `/api/matches` | Compatibility alias for the same single-job assessment |

Authentication, public discovery APIs, scheduled synchronization, OCR, LLM/embedding matching, candidate profile editing, application automation, and a frontend are out of scope. JC-008 Phase 3 provides an internal synchronous Lever synchronization service only.

## Prerequisites

- Java 21
- Docker with Docker Compose
- PowerShell on Windows, or a POSIX-compatible shell on macOS/Linux

The Maven Wrapper downloads Maven 3.9.11 on first use, so a separate Maven installation is not required.

```powershell
java -version
docker version
docker compose version
```

## Start PostgreSQL

```powershell
docker compose up -d
docker compose ps
```

Compose starts `postgres:17.11-alpine` on `localhost:5432` with database, username, and password all set to `job_copilot`. Data is stored in the named volume `job_copilot_postgres_data`.

These credentials and Hibernate settings are for local development only. They are not production configuration.

## Build and test

Windows PowerShell:

```powershell
.\mvnw.cmd clean verify
```

macOS/Linux:

```bash
./mvnw clean verify
```

The automated tests exercise the controller/API contract, error mappings, service behavior, entity behavior, Flyway migrations, PDF extraction, deterministic parsing, and matching. Integration tests start an isolated `postgres:17.11-alpine` Testcontainer. Docker must be running; Testcontainers use a dynamically assigned port and never connect to the persistent development database on `localhost:5432`.

## Run the application

```powershell
.\mvnw.cmd spring-boot:run
```

On macOS/Linux, use `./mvnw spring-boot:run`. The API starts at `http://localhost:8080`.

## Create a job

In a second PowerShell terminal:

```powershell
$body = @{
    title = "Backend Software Engineer"
    company = "Example Technologies"
    location = "Gurugram"
    jobUrl = "https://example.com/jobs/123"
    description = "Java Spring Boot backend role"
    source = "MANUAL"
    externalJobId = "123"
} | ConvertTo-Json

Invoke-RestMethod `
    -Method Post `
    -Uri "http://localhost:8080/api/jobs" `
    -ContentType "application/json" `
    -Body $body
```

Expected status: `201 Created`. The response includes the generated `id`, `createdAt`, `updatedAt`, and `status: "DISCOVERED"`. Create requests cannot select another status.

Equivalent curl request:

```bash
curl -i -X POST http://localhost:8080/api/jobs \
  -H 'Content-Type: application/json' \
  -d '{"title":"Backend Software Engineer","company":"Example Technologies","location":"Gurugram","jobUrl":"https://example.com/jobs/123","description":"Java Spring Boot backend role","source":"MANUAL","externalJobId":"123"}'
```

## List jobs

```powershell
Invoke-RestMethod -Method Get -Uri "http://localhost:8080/api/jobs"
```

The endpoint returns a pagination object containing `content`, `page`, `size`, `totalElements`, `totalPages`, `first`, and `last`.

Supported query parameters:

| Parameter | Default | Behavior |
|---|---|---|
| `page` | `0` | Zero-based page number |
| `size` | `20` | Page size from 1 through 100 |
| `sort` | `createdAt,desc` when omitted | One non-blank `field,direction` expression |
| `q` | none | Case-insensitive partial title/company search |
| `status` | none | Exact job-status filter |

Sortable fields are `id`, `title`, `company`, `status`, `createdAt`, and `updatedAt`. Directions are case-insensitive, so `asc`, `ASC`, `desc`, and `DESC` are valid. An explicitly empty or whitespace-only `sort` is invalid. For stable pagination, non-`id` sorts use `id` as a secondary field in the same direction; for example, `company,asc` is ordered by company ascending and then ID ascending.

```powershell
Invoke-RestMethod -Method Get -Uri "http://localhost:8080/api/jobs?page=0&size=10&sort=company,asc&q=backend&status=SHORTLISTED"
```

Search and status use AND semantics. A blank or whitespace-only `q` does not apply a search filter. Search is a case-insensitive title/company substring match; `%`, `_`, and `\` in user input are treated as literal characters rather than SQL `LIKE` syntax.

Unknown JSON properties remain accepted for this milestone. Search-length limits and optimistic locking are deferred; concurrent updates currently use last-write-wins semantics.

## Retrieve a job by ID

```powershell
Invoke-RestMethod -Method Get -Uri "http://localhost:8080/api/jobs/1"
```

A missing numeric ID returns `404 Not Found`; a non-numeric ID returns `400 Bad Request`.

## Replace editable job details

`PUT` requires a complete set of editable job details. `title` and `company` remain required. Optional properties can be cleared with JSON `null`.

```powershell
$updatedJob = @{
    title = "Senior Backend Software Engineer"
    company = "Example Technologies"
    location = "Remote"
    jobUrl = "https://example.com/jobs/123"
    description = "Updated Java backend role"
    source = "MANUAL"
    externalJobId = "123"
} | ConvertTo-Json

Invoke-RestMethod `
    -Method Put `
    -Uri "http://localhost:8080/api/jobs/1" `
    -ContentType "application/json" `
    -Body $updatedJob
```

PUT preserves `id`, `status`, and `createdAt`. JPA updates `updatedAt` when Hibernate writes the changed entity.

## Update job status

```powershell
$statusUpdate = @{ status = "SHORTLISTED" } | ConvertTo-Json

Invoke-RestMethod `
    -Method Patch `
    -Uri "http://localhost:8080/api/jobs/1/status" `
    -ContentType "application/json" `
    -Body $statusUpdate
```

Supported statuses:

- `DISCOVERED`
- `SHORTLISTED`
- `APPLIED`
- `INTERVIEWING`
- `OFFER`
- `REJECTED`
- `WITHDRAWN`

Status currently lives on `Job` as a deliberate milestone simplification. It can move to a user-specific application model when that domain is introduced.

## Delete a job

```powershell
Invoke-WebRequest -Method Delete -Uri "http://localhost:8080/api/jobs/1"
```

Successful deletion returns `204 No Content`. A subsequent lookup returns `404 Not Found`.

## Verify validation

```powershell
$invalidBody = @{
    title = ""
    company = "Example Technologies"
} | ConvertTo-Json

Invoke-WebRequest `
    -SkipHttpErrorCheck `
    -Method Post `
    -Uri "http://localhost:8080/api/jobs" `
    -ContentType "application/json" `
    -Body $invalidBody
```

Expected status: `400 Bad Request`. The response identifies `title` as invalid. A blank `company` is handled the same way.

## Manual PostgreSQL verification checklist

1. Start PostgreSQL and the application.
2. If the database contains pre-Week-1 rows, confirm every existing row has `status = DISCOVERED` after schema evolution.
3. Create multiple jobs and confirm each starts as `DISCOVERED`.
4. Retrieve one using `GET /api/jobs/{id}`.
5. Record its `id`, `status`, `createdAt`, and `updatedAt`.
6. Wait briefly, then PUT replacement details. Confirm `id`, `status`, and `createdAt` are unchanged while `updatedAt` advances.
7. Wait briefly, then PATCH the status. Confirm details and `createdAt` are unchanged while the status changes and `updatedAt` advances.
8. Search using a partial title with different casing.
9. Search using a partial company name with different casing, then verify `%`, `_`, and `\` are matched literally.
10. Filter by status.
11. Combine `q` and `status` and confirm AND semantics.
12. Create enough jobs to request multiple pages and verify `page`, `size`, and metadata.
13. Sort an allowlisted field in both ascending and descending directions, including uppercase direction text, and verify duplicate primary values have stable ID-based ordering.
14. Delete a job and confirm the response is `204` with no body.
15. Confirm its ID subsequently returns `404`.
16. Stop Spring Boot with `Ctrl+C`.
17. Restart PostgreSQL without deleting its volume:

   ```powershell
   docker compose down
   docker compose up -d
   ```

18. Start Spring Boot again and confirm the surviving jobs, statuses, and timestamps remain present.

Stop PostgreSQL while preserving its data:

```powershell
docker compose down
```

The following command deliberately deletes the local database and should only be used when a clean database is intended:

```powershell
docker compose down --volumes
```

## Flyway

Hibernate no longer evolves the schema. Flyway owns migrations and is configured with `baseline-on-migrate: false` and `clean-disabled: true`.

| Version | Script | Purpose |
|---|---|---|
| V1 | `V1__create_jobs.sql` | Jobs table matching the Week-1 schema, including `status` |
| V2 | `V2__add_job_requirements.sql` | `min_years_experience` and `job_skills` |
| V3 | `V3__add_resume_profiles.sql` | `resumes` and `candidate_profiles` |
| V4 | `V4__add_job_discovery_domain.sql` | External job sources, listings, liveness, and synchronization-run state |

A new empty database migrates V1 through V4 automatically on startup. An existing pre-Flyway database must be backed up and explicitly baselined at V1 before later migrations can run. Do not delete the Docker volume, rebuild the database, or baseline unknown/drifted schemas as a shortcut.

Inspect history with:

```powershell
docker compose exec postgres psql -U job_copilot -d job_copilot -c "SELECT installed_rank, version, description, success FROM flyway_schema_history ORDER BY installed_rank;"
```

## JC-008 Phase 1 discovery persistence

Phase 1 models configured Lever sources, external listing identity and `LIVE`/`CLOSED` availability, and
future synchronization-run state. Listing availability is separate from the user's `JobStatus`: for example,
an applied job may later have a closed provider listing. Verification timestamps live on the external listing,
so updating them does not change the job's `updatedAt` assessment metadata.

PostgreSQL enforces source identity, per-source external posting identity, one external listing per local job,
non-negative synchronization counters, safe bounded failure codes, and at most one `RUNNING` synchronization
per source. Deleting a job cascades only its external-listing metadata; deleting a source with listing or run
history is restricted.

Phase 1 contains no network behavior, mapping, digest calculation, reconciliation, requirement-extraction
integration, discovery endpoint, ranking/assessment change, or scheduler.

## JC-008 Phase 2 Lever network boundary

Phase 2 adds a synchronous, persistence-free Lever Postings API connector. One invocation accepts an already
canonical Lever site key, a `GLOBAL` or `EU` region, and one `skip`/`limit` page. It performs one bounded request
with redirects and retries disabled, then returns immutable provider data or a sanitized typed failure.

The connector does not load or mutate discovery entities, traverse pages, reconcile listings, map canonical jobs,
extract requirements, assess or rank jobs, schedule synchronization, or expose a controller. Unknown optional
provider fields and vocabulary are tolerated, while posting identity, required URLs, JSON structure, and response
memory are validated.

Normal tests use a local HTTP server. The opt-in real-provider acceptance test calls the current Lever GLOBAL and
EU demo feeds and is excluded from normal CI:

```powershell
.\mvnw.cmd "-Djobcopilot.lever.live=true" `
  "-Dtest=LeverPostingGatewayLiveAcceptanceTest" test
```

## JC-008 Phase 3 Lever synchronization

Phase 3 adds an internal synchronous synchronizer for one configured Lever source. Network requests occur outside
database transactions; run start, each page, each requirement extraction, finalization, failure terminalization,
and startup recovery use independent transactions. Presence from a valid page can remain after a later failure,
while reconciliation and `lastSuccessfulSyncAt` advance only after a complete, duplicate-free traversal.

The synchronizer preserves listing and canonical Job identity across refresh, closure, and reopening, and never
changes the user-owned Job application status. It has no controller, scheduler, queue, background worker, generic
provider abstraction, or matching/ranking redesign.

## Resume upload and candidate profiles

`POST /api/resumes` accepts `multipart/form-data` with a single part named `file`. Only text-based PDFs are supported.

Limitations:

- OCR is not supported. Image-only PDFs are rejected.
- Extraction and parsing are deterministic rule/vocabulary lookups, not an LLM.
- Incomplete employment chronology is stored as `UNKNOWN` experience, not as zero years.
- Extracted text is persisted for later reprocessing. Raw PDF bytes are not stored.
- Normal API responses do not include the full extracted text.
- Candidate corrections are deferred; JC-003 extraction is read-only.

Windows PowerShell:

```powershell
Invoke-RestMethod -Method Post -Uri "http://localhost:8080/api/resumes" -Form @{
    file = Get-Item -Path "$env:TEMP\job-copilot-sample-resume.pdf"
}
```

```bash
curl -i -X POST http://localhost:8080/api/resumes \
  -F "file=@resume.pdf;type=application/pdf"
```

Expected status: `201 Created` with `Location: /api/resumes/{id}`. Use `GET /api/resumes/{id}` and `GET /api/candidate-profiles/{id}` to retrieve metadata and the structured profile.

## Job matching requirements

Requirements are a separate resource. Replacing them does not change job status. Required skills win if the same canonical skill appears in both lists.

```powershell
$requirements = @{
    requiredSkills = @("Java", "Spring Boot", "PostgreSQL", "Redis")
    preferredSkills = @("Docker", "AWS")
    minYearsExperience = 1
} | ConvertTo-Json

Invoke-RestMethod -Method Put -Uri "http://localhost:8080/api/jobs/1/requirements" -ContentType "application/json" -Body $requirements
Invoke-RestMethod -Method Get -Uri "http://localhost:8080/api/jobs/1/requirements"
```

```bash
curl -i -X PUT http://localhost:8080/api/jobs/1/requirements \
  -H "Content-Type: application/json" \
  -d '{"requiredSkills":["Java","Spring Boot","PostgreSQL","Redis"],"preferredSkills":["Docker","AWS"],"minYearsExperience":1}'
```

Manual `PUT /api/jobs/{id}/requirements` remains available and is not replaced by extraction. There is no provenance flag: a later successful extraction overwrites intervening manual edits.

## Automatic job requirement extraction

`POST /api/jobs/{id}/requirements/extract` reads the **stored** job description and deterministically fills the existing requirements resource. The request has no body; the description is not sent by the client.

Extraction is rule-based. It is not an LLM, embedding, or semantic-inference step. Skills are recognized only when they match the versioned `matching-vocabulary.json` aliases and boundary rules. Unknown technologies are ignored.

Skill context:

- Clear required headings or inline markers (`required`, `requirements`, `required skills`, `must have`, `minimum qualifications`, and similar) classify matching skills as required.
- Clear preferred headings or inline markers (`preferred`, `preferred qualifications`, `nice to have`, `good to have`, `bonus`) classify matching skills as preferred.
- A recognized skill with no required/preferred evidence defaults to **preferred**, because required skills participate in hard matching score caps.
- If the same canonical skill appears as both, required wins. Stored required and preferred lists are canonical, unique, sorted, and disjoint.
- Section context does not leak through an unrelated heading such as `Responsibilities`.

Experience:

- Conservative numeric forms such as `2+ years`, `3 years of experience`, `minimum 2 years`, `at least 1 year`, `2.5 years`, `2-4 years`, and `3 to 5 years` are recognized.
- Ranges store the **lower bound**.
- Multiple valid minima store the greatest lower bound.
- Preferred-only experience is not stored as the mandatory minimum.
- Vague wording, word numbers, `up to`, `or` alternatives, and reversed ranges are ignored rather than guessed.

Successful extraction **fully replaces** the stored requirements. It does not merge with previous values.

Failed extraction (`422`) does not mutate existing requirements. Blank/null descriptions, no recognized skills and no valid positive experience, and parseable but domain-invalid minima (outside 0–80) all fail this way.

Repeating extraction when the semantic result is unchanged does not rewrite `job_skills` or advance `updatedAt`.

Windows PowerShell:

```powershell
Invoke-RestMethod -Method Post -Uri "http://localhost:8080/api/jobs/1/requirements/extract"
```

```bash
curl -i -X POST http://localhost:8080/api/jobs/1/requirements/extract
```

## Matching

Product flow:

1. Upload a resume and note the returned `candidateProfileId`.
2. Create or import a job.
3. Explicitly set or extract job requirements. Assessment does **not** extract requirements.
4. Assess one job:

`POST /api/jobs/{jobId}/assessment`

5. Rank jobs with the same explicit `candidateProfileId`. Ranking remains a separate bulk, on-demand computation.

Candidate selection is always explicit. Uploading another resume does not change what an existing `candidateProfileId` means. There is no active, current, or latest profile pointer.

Assessment is computed on demand from stored authoritative inputs (complete job and candidate matching snapshots). Results are **not** persisted: there is no assessment id, table, cache, history, or current-assessment field on the job.

`POST /api/matches` remains supported and delegates to the same single-job orchestration.

```powershell
$assessment = @{ candidateProfileId = 1 } | ConvertTo-Json
Invoke-RestMethod -Method Post -Uri "http://localhost:8080/api/jobs/1/assessment" -ContentType "application/json" -Body $assessment
```

```bash
curl -i -X POST http://localhost:8080/api/jobs/1/assessment \
  -H "Content-Type: application/json" \
  -d '{"candidateProfileId":1}'
```

```json
{
  "candidateProfileId": 1,
  "jobId": 1,
  "algorithmVersion": "deterministic-v1",
  "overallScore": 80.00,
  "recommendation": "STRONG_MATCH",
  "matchedRequiredSkills": ["java"],
  "missingRequiredSkills": [],
  "matchedPreferredSkills": [],
  "unmatchedPreferredSkills": [],
  "experienceComparison": { "status": "NOT_APPLICABLE" }
}
```

The compatibility endpoint:

```powershell
$match = @{ candidateProfileId = 1; jobId = 1 } | ConvertTo-Json
Invoke-RestMethod -Method Post -Uri "http://localhost:8080/api/matches" -ContentType "application/json" -Body $match
```

```bash
curl -i -X POST http://localhost:8080/api/matches \
  -H "Content-Type: application/json" \
  -d '{"candidateProfileId":1,"jobId":1}'
```

The response includes `overallScore`, `recommendation`, matched/missing skills, experience comparison, scoring breakdown, applied caps, strengths, gaps, and unassessed factors such as location/work mode.

Matching limitations:

- Skills match through the versioned canonical vocabulary in `matching-vocabulary.json`, not semantic similarity.
- Scoring is deterministic and reproducible. It is not a probability of hiring success.
- Missing evidence can be a parser limitation rather than a true skill gap.
- Missing required skills cap the overall score (79 if some required skills match, 49 if none match). They do not hard-disqualify.
- There is no LLM, embedding, or vector-database integration in JC-003.

A job with no skills and no positive `minYearsExperience` cannot be scored (`422`).

## Multi-job ranking

`GET /api/candidate-profiles/{candidateProfileId}/job-rankings` ranks status-eligible jobs against one candidate profile. Rankings are computed on demand and are not persisted. `DeterministicMatchingEngine` remains the only scoring and recommendation authority; this endpoint filters, globally ranks, and paginates those results.

With no `status` parameter, ranking evaluates exactly `DISCOVERED`, `SHORTLISTED`, `APPLIED`, and `INTERVIEWING`. `OFFER`, `REJECTED`, and `WITHDRAWN` are excluded by default. Explicit `status` values completely override that default (they are not intersected with it). Repeated query parameters are the contract (`?status=DISCOVERED&status=APPLIED`); comma-separated lists are not.

Optional query parameters:

| Parameter | Default | Notes |
|---|---|---|
| `status` | `DISCOVERED`, `SHORTLISTED`, `APPLIED`, `INTERVIEWING` | Repeatable. Blank or unknown values are `400`. Duplicates are ignored. |
| `minScore` | omitted (no score filter) | Inclusive `0`–`100`. Compared with `BigDecimal.compareTo`. Blank, malformed, out of range, or repeated → `400`. |
| `recommendation` | omitted (no recommendation filter) | Repeatable. Values are OR'd. Combined with `minScore` using AND. Blank or unknown → `400`. |
| `page` | `0` | Zero-based. Must be `>= 0`. Repeated → `400`. |
| `size` | `20` | `1`–`100`. Repeated → `400`. |

Windows PowerShell:

```powershell
Invoke-RestMethod -Method Get -Uri "http://localhost:8080/api/candidate-profiles/1/job-rankings"
Invoke-RestMethod -Method Get -Uri "http://localhost:8080/api/candidate-profiles/1/job-rankings?minScore=65&page=0&size=20"
```

```bash
curl -i "http://localhost:8080/api/candidate-profiles/1/job-rankings"
curl -i "http://localhost:8080/api/candidate-profiles/1/job-rankings?status=OFFER"
```

All status-eligible jobs are loaded and matched before pagination. Computable jobs remaining after `minScore` and `recommendation` filters are ranked by:

1. `overallScore` descending (`BigDecimal.compareTo`)
2. `createdAt` descending
3. `jobId` descending

Ranks are one-based positions in the complete filtered list and continue across pages (`page=0` has ranks `1–size`). Recommendation and status are not sort keys. `updatedAt` is not a tie-breaker. A page past the last page returns `200` with an empty `rankedJobs` array and accurate pagination metadata. If there are zero filtered ranked jobs, `totalPages` is `0`; `page=0` is both `first` and `last`.

A job that cannot be scored with the existing engine (no skills and no positive `minYearsExperience`) is **unassessed**, not a bad match. Unassessed jobs have no score, recommendation, or rank. They are returned in the complete `unassessedJobs` list for the resolved statuses (not paginated with ranked jobs, and not affected by `minScore` or `recommendation`) with reason `INSUFFICIENT_JOB_REQUIREMENTS`. They are ordered by `createdAt` descending, then `jobId` descending.

Count fields:

- `evaluatedJobCount` = status-eligible jobs passed to the engine
- `computableJobCount` = jobs that produced a `MatchResult` before post-score filters
- `unassessedJobCount` = jobs that produced `MatchCannotBeComputedException` (`== unassessedJobs.size()`)
- `filteredJobCount` = computable jobs remaining after `minScore`/`recommendation` filters, before pagination
- `pageResultCount` = `rankedJobs.size()` on the current page

`evaluatedJobCount` equals `computableJobCount + unassessedJobCount`. An empty eligible set returns `200` with empty arrays and zero counts. A missing candidate profile returns the existing `404` contract and does not scan jobs. An unexpected matching failure fails the whole request with a generic `500`; it does not return a partial ranking.

Each ranked row copies score, recommendation, skill lists, experience comparison, caps, strengths, gaps, warnings, and unassessed factors from the same `DeterministicMatchingEngine` result used by `POST /api/jobs/{jobId}/assessment` and `POST /api/matches`. Category breakdown and role/keyword relevance objects are omitted from the ranking summary; they remain on the single-job assessment API. Invoking assessment does not change ranking eligibility, order, ranks, filters, pagination, or counts.

## JC-007 Phase 1 contracts (shadow analysis foundation)

Phase 1 adds job-side contracts, versioned prompts and a shared output schema only. It adds no API,
provider client, execution orchestration, validation algorithms, or persistence. Existing extraction,
assessment, matching, ranking, and candidate behavior remain unchanged.

`JobIntelligencePrompt.LlmFirstInput` contains exactly raw `title` and `description`.
`HybridInput` adds captured current canonical requirements, including empty or manually corrected
values. Project the existing matching snapshot explicitly into these records; never serialize the
snapshot as model input. Neither input accepts an independent JC-005 baseline.

`JobExtractionBaseline` calls the existing extractor without persistence. Expected extraction inability
becomes `UNAVAILABLE / EXTRACTION_UNAVAILABLE`; unexpected defects propagate. Baseline capture may
happen before or after model execution, but never feeds prompt construction or acts as a readiness gate.

Both strategies share the `job-intelligence-output-v1` schema. Prompt labels are `llm-first-v1` and
`hybrid-enrichment-v1`. Each effective identity appends `:sha256:` and the SHA-256 of the exact UTF-8
assembled instructions or schema text. Resource changes therefore cannot retain the same effective
identity; intentional contract revisions still advance the readable version label.

`JobIntelligenceModel.ModelInput` is a sealed read-only interface. Its sole final implementation is
owned by `JobIntelligencePrompt`, with a private constructor and no accessible factory. Only `assemble`
creates requests; application callers, including package peers, cannot supply replacement serialized
strategy data, instructions, schema, or version identities. The private payload preserves full-request
value equality for isolation checks.

Generation settings contain only nullable `Double temperature` (null means omit) and
`int maxOutputTokens`. No maps, arbitrary metadata, headers, hints, or callbacks enter this contract.
Provider-specific configuration and operational settings validation remain deferred.

Provider `Output` is an untrusted candidate contract, distinct from accepted `JobIntelligence`.
Java-derived `minimumExperience` is absent from provider output. Evidence sources are only `TITLE`
and `DESCRIPTION`; canonical context is never evidence. Later validation must enforce grounding,
cross-field consistency (including experience units/ranges), normalization and derivation before acceptance.
Phase 1 records and schema do not claim semantic validation or acceptance.

Focused verification: `./mvnw -Dtest=JobIntelligenceContractTest,JobIntelligencePromptTest,JobIntelligenceIsolationTest,JobIntelligenceRequestBoundaryTest,JobExtractionBaselineTest test`
(use `mvnw.cmd` on Windows). Normal verification makes no live model calls.

## JC-007 Phase 2 (in-memory acceptance pipeline)

Phase 2 adds a provider-independent in-memory runner. It does not add HTTP clients, provider adapters,
retries, persistence, APIs, evaluation, candidate-side intelligence, or matching/ranking changes.

Flow: `StrategyInput` → `JobIntelligencePrompt.assemble` → one `JobIntelligenceModel.analyze` attempt →
strict local JSON decode → deterministic grounding → minimal copy/exact-decimal normalization →
Java-only `minimumExperience` → terminal `Accepted` or `Failed`.

Provider `COMPLETED` never implies acceptance. JC-005 baseline is not a runner argument.

### Execution

Timeout is a monotonic deadline covering queued and running provider time. The attempt is cancelled on
timeout; a late completion cannot replace the timeout failure. Interruption preserves the interrupt flag.
Exceptions crossing the provider boundary become `INVOCATION_EXCEPTION`. JVM `Error`s are not caught.
Validator/programming defects are `VALIDATOR_DEFECT`, not provider failures. There are no retries.

QA correction: default execution uses one process-owned daemon pool shared across runners, capped at four
workers and sixteen queued tasks, with idle worker expiry after thirty seconds. Saturation yields
`EXECUTION_UNAVAILABLE`; expired queued tasks are cancelled and removed. Adapters ignoring interruption can
occupy at most four workers indefinitely; Java cannot forcibly stop them. `invocationStarted` records entry
to `analyze`, not submission. Provider ID lookup also runs within the timed boundary. Invalid model/settings
and timeout-to-nanoseconds overflow fail before dispatch. Null provider result/outcome yields
`PROVIDER_CONTRACT_VIOLATION`.

### Strict decode

Candidate JSON is parsed with a dedicated local Jackson configuration (not Spring MVC). Exactly one JSON
object is required. Markdown fences, comments, trailing tokens, duplicate fields, unknown fields (including
`minimumExperience`), missing required fields, invalid enums, numeric strings, scalar-to-array coercion,
null collection elements, and schema bound violations fail in `DECODE`. Failure codes and locations are
typed; parser text and rejected excerpts are not stored.

Runtime operational limits (not frozen v1 schema constraints): 262144 candidate characters; nesting depth 16;
numeric token length 32; 64 uncertainties; 4096 processed tokens/nodes.
The candidate-size limit counts UTF-16 code units; schema string limits count Unicode code points.
Unknown fields report only the containing object location, never the untrusted field name. Missing-field
selection is sorted for reproducible diagnostics; explicit illegal nulls are distinct from missing fields.

### Grounding policy (`job-intelligence-grounding-v1-source-context-1`)

Evidence `quote` must be an exact contiguous substring of the declared raw TITLE or DESCRIPTION after JSON
decoding. Canonical requirements are never evidence. Duplicate evidence IDs and dangling references fail.
Unreferenced evidence is still quote-checked. Uncertainty cannot make a fabricated claim valid.

Facts use finite Java checks only:

- Technologies must appear as bounded tokens in referenced quotes (`Java` does not match `JavaScript`; `C`
  does not match `C++`).
- `REQUIRED` needs required-language markers in source-derived context; negation/optional/preferred-only windows
  do not establish a required fact.
- `PREFERRED` needs preferred-language markers.
- Experience clause `text` must be a substring of referenced quotes and must match a supported form:
  `N+`, `at least` / `minimum N`, `N–M` / `N-M` ranges (lower endpoint), `more than N` (accepted as a
  clause form; job-wide minimum is `AMBIGUOUS`), or a simple `N years|months` quantity. `minimum` and
  `unit` are both present or both null and must agree with the source-derived form. Both-null is allowed
  only for a supported exclusive expression, not for an ordinary explicit numeric minimum.
- Qualifications require the qualification text as an exact quote substring, with the same importance markers.
- Role and seniority are accepted only for keyword patterns in source-derived contexts. Responsibilities
  and technical concepts must be exact quote substrings. Anything else is `UNSUPPORTED_INTERPRETATION` or
  `UNSUPPORTED_CLAIM_FORM`. No LLM judge and no general NLP.

### Minimum experience

Derived only in Java from required `OVERALL` clauses. Preferred, unspecified, skill-specific, and relevant
clauses do not set the job-wide minimum. Multiple compatible lower bounds take the maximum; they are never
summed. Conditional alternatives, exclusive `more than N`, contradictory range vs higher minimum, and
`AMBIGUOUS_EXPERIENCE` uncertainty yield `AMBIGUOUS` with null months. No required overall clause yields
`NOT_STATED`. Years convert with exact `× 12` `BigDecimal` (example: `2.5 YEARS` → `30` months). `KNOWN`
requires nonnegative months.

### Terminal result

`Accepted(JobIntelligence, AttemptMetadata)` or `Failed(Failure, AttemptMetadata)`. Failure stages are
`PREFLIGHT`, `EXECUTION`, `DECODE`, `VALIDATION`. Metadata keeps strategy, invocation flag, provider/model
identities, prompt/schema identities, generation settings, timeout, elapsed, optional latency/usage/outcome,
validation-policy version, and strategy-data digest. It does not keep exception messages, bodies, headers,
or rejected candidate excerpts.

QA correction: external identifier metadata is omitted when empty, longer than 256 ASCII characters, or
outside `[A-Za-z0-9][A-Za-z0-9._:/@+\-]*`; values are never truncated into different identifiers. Negative
provider latency and individual negative usage counters are omitted. Missing counters remain null.

### Source-context and experience support V1

Evidence quotes establish exact provenance, not complete semantic context. `SourceContext` locates every
exact occurrence in the evidence's declared raw source and expands it to a complete sentence/clause.
Boundaries are semicolons, exclamation/question marks, periods followed by whitespace/end, and blank-line
paragraph breaks (LF/LF or LF/CRLF). Decimal points and unit abbreviations (`yr`, `yrs`, `mo`, `mos`, `min`)
do not end context. Single line breaks, commas, colons, parentheses and conjunctions stay attached so that
wrapped negation and local alternatives cannot be removed by cropping.

Each raw field is capped at 1,000,000 UTF-16 units, each complete context at 1,024 Unicode code points, and
each quote at 32 occurrences. Over-limit or cross-boundary quotes fail closed; contexts are never truncated.
Every occurrence and every cited context must support the claim. Mixed positive/negative occurrences,
conflicting quantity forms, unsupported local wording, or an additional unfavorable citation reject the
whole candidate. Canonical requirements never enter context extraction. Stored quotes, IDs, clause text,
and collection order are unchanged; offsets remain private implementation details.

Skills, qualifications and interpretations are checked against these contexts. Negation/optionality and
explicit alternatives/conditions veto affirmative non-experience claims. Required skills cannot borrow
markers across sentence/list boundaries or override a preferred marker. These conservative checks may
reject legitimate complex phrasing; they do not infer cross-item inheritance or implement general NLP.

`ExperienceSupport` independently establishes one quantity form, scope, and conditionality per source
context. Multiple quantity expressions in a single context are unsupported. General scope supports bare
quantities, `experience`, and `of experience`, including the finite modifiers `software engineering`,
`professional development`, `professional`, `development`, `work`, `overall`, or `total` before `experience`.
`of relevant/backend/frontend experience` supports RELEVANT. `of/with/using/in <skill>` with optional
`experience` supports SKILL_SPECIFIC for Java, Spring Boot, Kubernetes, Python, C, C++, C#, JavaScript, SQL,
Node.js, React, Go and R. This local validation list never enters model input. Other scope wording fails
closed, including an OVERALL label on a supported skill-specific expression or the reverse.

The finite prelude permits `must [have]`, `you must [have]`, `required`, `preferred`, `preferably`, and
`either`. Local suffix alternatives support `or` followed by a degree (including bachelor's/master's),
certification/equivalent certification, or equivalent experience. Suffix `if`, `unless`, and `in lieu of`
require a nonempty condition. Leading `if`, `unless`, or `in lieu of` conditions require a separating comma.
These constructions require `conditional=true`; absence requires false. An unrelated alternative in another
sentence cannot affect the clause. Unsupported alternatives, dangling `either`, and unrecognized preludes
fail closed. Parsing-only case/whitespace normalization does not rewrite accepted data.

The runner's derivation path consumes validated source forms, so cropped `3 years` within `more than
3 years` yields AMBIGUOUS, and an upper-end substring of a range cannot become a higher ordinary minimum.
Aggregate precedence is unchanged: 3–5 plus eligible 4 yields 48 months; 3–5 plus eligible 8 yields ambiguity.
For V1, any accepted `AMBIGUOUS_EXPERIENCE` uncertainty makes the aggregate ambiguous, regardless of its
free-text target. Other uncertainty codes do not change the minimum. This is an explicit conservative
limitation, not semantic interpretation of uncertainty targets. Unsupported/absent experience should be
represented by abstention/empty clauses, not fabricated null clauses.

Focused verification: `./mvnw "-Dtest=JobIntelligence*Test,MinimumExperienceDeriverTest,JobExtractionBaselineTest" test`.
Full terminal verification: `./mvnw clean verify` with Docker/Testcontainers available.

## JC-007 Phase 3 (strategy execution hardening)

Phase 3 accepts and hardens the existing provider-neutral execution path rather than adding a second
orchestration framework. Both `LLM_FIRST` and `HYBRID_ENRICHMENT` execute through
`JobIntelligenceRunner`, `JobIntelligenceAttempt`, and `JobIntelligenceModel`. Each run permits at most
one model invocation: provider failures (including a retryable outcome), malformed output, schema failure,
or deterministic validation failure are terminal, with no retry, repair prompt, or self-critique call.

`LlmFirstInput` remains structurally limited to raw title and description, and model-boundary tests verify
that canonical-only canary data cannot reach any textual request field. Hybrid input may include the current
canonical requirements captured from stored job state, but the validator still receives only raw TITLE and
DESCRIPTION; canonical context cannot establish evidence or make an unsupported claim pass.

Strict decoding, source grounding, uncertainty/abstention handling, experience validation, and Java-only
minimum-experience derivation remain authoritative. Expected JC-005 baseline unavailability is represented
by its independent baseline result and does not gate either AI strategy. Phase 3 adds no real provider
adapter, Spring wiring, persistence, API, comparison endpoint, or evaluation orchestration.

## JC-007 Phase 4 (OpenAI provider integration)

Phase 4 adds one production adapter from the existing `JobIntelligenceModel` boundary to the OpenAI
Responses API. `OpenAiJobIntelligenceModel` accepts only the already-assembled provider-neutral input,
performs one non-streaming request, and returns raw candidate text plus bounded provider metadata. It does
not inspect strategy types, reconstruct prompts, parse domain objects, validate evidence, or derive
experience. OpenAI Structured Outputs sends the repository's existing JSON Schema with strict enforcement;
the existing decoder and deterministic validator still process every completed candidate afterward.

The official `openai-java` SDK is pinned to `4.60.0`. SDK retries are set to zero, connection-failure replay
and redirects are disabled, and request bodies are marked non-replayable. This last control is necessary:
OkHttp can otherwise repeat a POST for HTTP 503 with `Retry-After: 0` even with connection retries disabled.
OkHttp `4.12.0`, already used by the SDK, is an explicit compile dependency for this transport control.
Loopback tests use the production client factory and count actual requests across success, HTTP errors,
redirects, authentication challenges, disconnects, partial bodies, and timeouts.
`JobIntelligenceAttempt` owns the domain deadline and cancellation and deducts queue/provider-ID time before
passing the remaining budget to the adapter. Budget refresh preserves assembled content and identities.
The adapter applies that remaining budget as a per-request transport ceiling. There is no repair, fallback,
or second completion. Missing provider metadata does not discard candidate output, and permanent errors in
failed Responses envelopes remain permanent. An existing model bean suppresses creation of the OpenAI model bean.

OpenAI wiring is disabled by default and requires `JOB_INTELLIGENCE_OPENAI_ENABLED=true` plus a nonblank
`OPENAI_API_KEY`. Supplying a key alone does not enable the integration. SDK request/body logging is off,
configuration rendering redacts the key, and provider error bodies are never returned in result metadata.
Normal CI performs no external OpenAI calls. The optional live smoke test runs only when
`JOB_INTELLIGENCE_LIVE_TEST=true`; it uses `OPENAI_MODEL` when set and otherwise uses the recommended
`gpt-5.6-terra` model:

```powershell
$env:JOB_INTELLIGENCE_LIVE_TEST = "true"
$env:OPENAI_API_KEY = "<your-key>"
./mvnw.cmd "-Dtest=OpenAiJobIntelligenceLiveSmokeTest" test
```

This phase adds no REST endpoint, persistence, migration, evaluation framework, second provider, or
matching/ranking behavior.

## JC-007 Phase 5 (offline evaluation harness)

Phase 5 adds a test-only, deterministic evaluation harness under
`src/test/java/com/jobcopilot/intelligence/evaluation`. It runs the frozen JC-005 extractor baseline,
Hybrid Enrichment, and LLM-first independently against exactly 24 manually reviewed cases. The versioned
case resource is the authority for manually reviewed gold truth and frozen Hybrid canonical context. The AI lanes
use committed fixture responses through the public `JobIntelligenceRunner`; they make no live provider
calls and do not bypass decoding, grounding validation, or Java-derived minimum experience.

The harness reports required/preferred skill precision, recall, and F1; class inversions, duplicate and
cross-class predictions; qualification metrics; clause-level experience quantity, importance, scope,
conditionality, and provenance; aggregate minimum-experience accuracy; abstention quality; reliability;
failure taxonomy; category breakdowns; and reproducibility identities. A zero denominator is rendered as
an unavailable metric rather than silently converted to zero.

Run the evaluation and generate deterministic human-readable and machine-readable reports with:

```powershell
./mvnw.cmd "-Dtest=com.jobcopilot.intelligence.evaluation.*Test" test
```

Reports are written to `target/job-intelligence-evaluation/report.txt` and `report.json`. They are build
artifacts and are not committed. Fixture-backed scores validate the evaluation machinery and expose
known deterministic behavior; they do not establish strategy or real-provider superiority. In this fixture set,
23 of 24 LLM-first attempts intentionally reuse the Hybrid candidate payload, so those two lanes are not
independent model-quality measurements.

Gemini real-provider acceptance is a separate, opt-in integration check. It proves that a small purposeful set
can traverse the existing provider-neutral runner and deterministic validation pipeline; it is not a comparative
benchmark. Ordinary `mvn verify` neither requires Gemini credentials nor spends quota. To run the live check,
set the `GEMINI_API_KEY` environment variable without printing or persisting its value, then run:

```powershell
./mvnw.cmd "-Djobcopilot.gemini.live=true" \
  "-Dtest=com.jobcopilot.intelligence.gemini.GeminiJobIntelligenceLiveAcceptanceTest" test
```

The acceptance configuration uses `gemini-3.5-flash-lite` by default, unary Gemini `generateContent`, JSON
response mode with the authoritative schema supplied as instruction, temperature 0, a 4096-token output limit,
and a 45-second per-attempt deadline. The strict local decoder and validator remain authoritative. It performs
three calls and never retries, repairs, or falls back. No production persistence, API, matching, ranking, or
canonical-authority behavior is changed.

## Configuration

`src/main/resources/application.yml` supports these environment-variable overrides:

| Variable | Development default |
|---|---|
| `DB_URL` | `jdbc:postgresql://localhost:5432/job_copilot` |
| `DB_USERNAME` | `job_copilot` |
| `DB_PASSWORD` | `job_copilot` |
| `RESUME_MAX_FILE_SIZE` | `5MB` |
| `RESUME_MAX_REQUEST_SIZE` | `6MB` |
| `RESUME_MAX_BYTES` | `5242880` |
| `RESUME_MAX_PAGES` | `25` |
| `RESUME_MAX_CHARACTERS` | `200000` |
| `RESUME_MIN_MEANINGFUL_CHARACTERS` | `50` |
| `JOB_INTELLIGENCE_OPENAI_ENABLED` | `false` |
| `OPENAI_API_KEY` | empty |
| `OPENAI_BASE_URL` | `https://api.openai.com/v1` |
| `OPENAI_CONNECT_TIMEOUT` | `5s` |
| `LEVER_CONNECT_TIMEOUT` | `5s` |
| `LEVER_REQUEST_TIMEOUT` | `20s` |
| `LEVER_MAX_RESPONSE_BYTES` | `10485760` |

Hibernate `ddl-auto` is `validate`. Schema evolution is Flyway-only. If validation or migration fails, do not delete the Docker volume automatically: preserve it, inspect `flyway_schema_history`, and apply an explicit local recovery plan.

Job-ingestion deduplication, including a possible future uniqueness rule for `source` and `externalJobId`, is intentionally deferred to the ingestion milestone. Do not commit production credentials, real resumes, or a secret-bearing `.env` file.

## Troubleshooting

### Java is not version 21

```powershell
java -version
.\mvnw.cmd -version
```

Ensure both commands use JDK 21 and update `JAVA_HOME` and `PATH` if needed.

### PostgreSQL does not become healthy

```powershell
docker compose ps
docker compose logs postgres
```

Check Docker Desktop, the configured credentials, and whether port 5432 is already in use.

### Port 5432 is occupied on Windows

```powershell
Get-NetTCPConnection -LocalPort 5432 -ErrorAction SilentlyContinue
docker ps
```

Stop the conflicting service or update both the Compose host port and `DB_URL` consistently.

### Credentials changed but authentication still fails

PostgreSQL initialization settings are retained in the named volume. If the existing local data can be discarded, run `docker compose down --volumes` and start Compose again. This permanently removes the current local database content.
