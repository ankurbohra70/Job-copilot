# Job Copilot

Job Copilot is an AI-powered job search workspace intended to help candidates discover, evaluate, tailor for, prepare for, and track job applications.

The backend currently covers job management plus JC-003 resume extraction and explainable matching:

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

Job APIs still support creation, retrieval, replacement, status changes, deletion, pagination, sorting, title/company search, and status filtering. JC-003 adds job matching requirements, PDF resume upload, candidate profiles, and on-demand matching.

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
| `POST` | `/api/matches` | Compute an explainable match (not persisted) |

Authentication, job ingestion, OCR, LLM/embedding matching, candidate profile editing, application automation, and a frontend are out of scope.

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

A new empty database migrates V1 through V3 automatically on startup. An existing pre-Flyway database must be backed up and explicitly baselined at V1 before V2/V3 can run. Do not delete the Docker volume, rebuild the database, or baseline unknown/drifted schemas as a shortcut.

Inspect history with:

```powershell
docker compose exec postgres psql -U job_copilot -d job_copilot -c "SELECT installed_rank, version, description, success FROM flyway_schema_history ORDER BY installed_rank;"
```

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

## Matching

`POST /api/matches` computes a score from a stored candidate profile and job. The result is not persisted.

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
