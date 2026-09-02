# Job Copilot

Job Copilot is an AI-powered job search workspace intended to help candidates discover, evaluate, tailor for, prepare for, and track job applications.

JC-001 establishes the backend foundation with one complete job-creation vertical slice:

```text
HTTP → JobController → JobService → JobRepository → JPA/Hibernate → PostgreSQL
```

## Current scope

The current API can create a job with `POST /api/jobs`, list persisted jobs with `GET /api/jobs`, and reject blank job titles and companies. Authentication, ingestion, matching, AI, resume processing, application automation, and a frontend are not part of JC-001.

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

Compose starts `postgres:17.6-alpine` on `localhost:5432` with database, username, and password all set to `job_copilot`. Data is stored in the named volume `job_copilot_postgres_data`.

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

The automated JC-001 tests exercise routing, JSON responses, successful creation responses, and request validation with MockMvc. They do not connect to PostgreSQL. The real persistence path is verified manually for this milestone.

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

Expected status: `201 Created`. The response includes the generated `id`, `createdAt`, and `updatedAt`.

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

The endpoint returns a JSON array. JC-001 does not define ordering, pagination, searching, or filtering.

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

## Verify persistent storage

1. Create a job and confirm it appears in `GET /api/jobs`.
2. Stop Spring Boot with `Ctrl+C`.
3. Restart PostgreSQL without deleting its volume:

   ```powershell
   docker compose down
   docker compose up -d
   ```

4. Start Spring Boot again and call `GET /api/jobs`.
5. Confirm the previously created job is still returned.

Stop PostgreSQL while preserving its data:

```powershell
docker compose down
```

The following command deliberately deletes the local database and should only be used when a clean database is intended:

```powershell
docker compose down --volumes
```

## Configuration

`src/main/resources/application.yml` supports these environment-variable overrides:

| Variable | Development default |
|---|---|
| `DB_URL` | `jdbc:postgresql://localhost:5432/job_copilot` |
| `DB_USERNAME` | `job_copilot` |
| `DB_PASSWORD` | `job_copilot` |
| `JPA_DDL_AUTO` | `update` |

`ddl-auto=update` is intentionally limited to local JC-001 development. It is not a production migration strategy. Flyway or another explicit migration tool must be introduced before production deployment or significant schema evolution. Do not commit production credentials or a secret-bearing `.env` file.

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
