# Resume ingestion: layout-v2 / facts-v1 implementation and verification

Verified on 2026-10-03 against the current checkout. No commit or push was made.

## 1. Implementation summary

New uploads suggest supported candidate contact facts and professional links in a DRAFT profile. The candidate
reviews, corrects, clears, adds/removes links, and confirms the profile using the existing revision-controlled
replacement API. Original extraction evidence survives all later edits and is never evidence for corrected values.

## 2. Final architecture

`ResumeService` orchestrates legacy PDFBox text extraction plus observational layout, then rules-v5 on **only**
the legacy text, structured-profile canonicalization, facts-v1, and atomic draft persistence. PDF parsing stays
outside the transaction. `LayoutCapturingPdfTextStripper` observes superclass emission without modifying
positions, separators, sorting, or NUL removal. URI annotations are read from the same open document. Layout
is request-local; only bounded fact evidence persists. Unsupported geometry, capture limits, and unsafe/missing
contact regions leave layout-derived suggestions absent while retaining valid text and structured parsing.
Current title derives independently from the canonical structured work history.

## 3. Files changed by this milestone

Paths below are repository-relative. Pre-existing parser and evaluation changes are not included as authored changes.

**Backend additions** under `src/main/java/com/jobcopilot/resume/`:

- `ResumeDocument.java` (immutable nested page, line, span, hyperlink, and bounds records)
- `LayoutCapturingPdfTextStripper.java`
- `CandidateFactsExtractor.java`
- `DeterministicCandidateFactsExtractor.java`
- `CandidateFactsValidator.java`
- `ProfessionalLink.java`
- `CandidateFactExtraction.java`
- `CandidateFactExtractionData.java`

**Backend edits** under the same package:

- `ResumeTextExtractor.java`, `PdfBoxResumeTextExtractor.java`
- `ResumeService.java`, `ResumePersistenceService.java`
- `Resume.java`, `CandidateProfile.java`, `CandidateProfileFacts.java`
- `dto/CandidateProfileRequest.java`, `dto/CandidateProfileResponse.java`
- `dto/CandidateProfileConfirmationRequest.java`, `dto/ResumeResponse.java`

**Dependency/storage**:

- `pom.xml`
- `src/main/resources/db/migration/V10__add_candidate_fact_extraction.sql`

**Backend verification**:

- Added resume tests: `LayoutExtractionTest.java`, `CandidateFactsExtractorTest.java`,
  `CandidateFactsValidatorTest.java`, `CandidateFactPersistenceIntegrationTest.java`
- Updated resume tests: `ResumeServiceTest.java`, `ResumePersistenceIntegrationTest.java`,
  `ResumeUploadIntegrationTest.java`
- Updated `src/test/java/com/jobcopilot/SchemaMigrationIntegrationTest.java`
- Added `src/test/resources/resume/facts/master-shape.txt` (synthetic)

**Frontend**:

- `web/src/api/types.ts`
- `web/src/features/profile/ProfilePage.tsx`, `confirmPayload.ts`
- `web/src/features/profile/ProfilePage.test.tsx`, `confirmPayload.test.ts`
- `web/src/app/ProfileWorkspace.test.tsx`
- `web/src/test/fixtures.ts`
- `web/e2e/journey.spec.ts`

**Documentation**:

- `README.md`
- `src/test/resources/resume/deterministic/README.md`
- This report

The starting contents of `DeterministicProfileParser.java`, `CandidateProfileValidator.java`, and V1–V9
migrations were verified by SHA-256 to remain unchanged. Other existing parser/evaluation fixtures and edits
were preserved. The existing Space/Enter helpers were not edited.

## 4. Dependency changes

Added `com.googlecode.libphonenumber:libphonenumber:9.0.40`. It validates and normalizes explicitly international
phone numbers. No parser, OCR, Python, LLM, paid API, or frontend dependency was added. PDFBox remains 3.0.8.

## 5. Migration

`V10__add_candidate_fact_extraction.sql` adds only nullable columns:

- `resumes.layout_version VARCHAR(32)`
- `candidate_profiles.professional_links JSONB`
- `candidate_profiles.fact_extractor_version VARCHAR(64)`
- `candidate_profiles.fact_extraction JSONB`

JSON shape/list-size checks are included. No backfill, default capability values, indexes, employer columns,
or full-layout persistence are added. Existing migrations were not rewritten.

## 6. Version values

| Capability | New uploads |
|---|---|
| PDF engine / extractorVersion | pdfbox-3.0.8 |
| layoutVersion | layout-v2 |
| parserVersion | rules-v5 |
| factExtractorVersion | facts-v1 |
| vocabularyVersion | v1 |

Historical null capability metadata stays null. Structured-profile schema remains v1. Extraction metadata
has its own v1 schema. GET does not extract or reparse anything.

## 7. Exact layout text parity

All 13 `LayoutExtractionTest` cases pass. Exact post-NUL-removal equality uses the original recipe, on the
same platform, without trimming or carriage-return/Unicode tolerance. The full structured record is compared
at a fixed assessment date. Coverage includes all five real PDFs, positional spacing/columns, crop boxes,
four rotations, duplicate overlapping text, blank physical pages, repeated headers, embedded Unicode and
ligatures, URI metadata, metadata-limit fallback, and unsupported text direction.

The parity gate initially passed before production facts-v1 was connected to layout. Optional AI/SDeee
PDF tests were enabled using the existing local Downloads directory; private PDFs were not copied.

## 8. Complete rules-v5 regression

All 38 `DeterministicProfileParserV5Test` cases pass, including the five-resume matrix. The broader existing
parser suites pass 79 cases in total (base parser, structural regression, V3, V4, V5). Entire profile equality
covers work, education, projects, skills, keywords, roles, totals, observed experience, assessment, evidence,
warnings, dates, bounded source text, and logical bullets. Parser behavior and vocabulary were not changed.

## 9. facts-v1 test results

26 extraction tests and 9 link/domain validation tests pass. The sanitized master-shape acceptance verifies
name, email with preserved local-part case/lowercased domain, normalized international phone, explicit location,
LinkedIn/GitHub/LeetCode, ongoing title, original evidence, and repeated deterministic extraction.

## 10. Current-title behavior

Exactly one ongoing parsed work entry with a nonblank title and no explicit future start can suggest a title.
Ended employment, zero/multiple ongoing entries, and explicit future starts leave it null. Headline, summary,
and latest ended employment are not fallbacks. Confirmation never recalculates or overwrites user-submitted
title. No employer/latest-title fields were introduced.

## 11. Professional-link behavior

Editable `{type,url}` arrays support LINKEDIN, GITHUB, LEETCODE, WEBSITE, and OTHER. HTTP(S) URLs are validated,
safe schemeless URLs receive HTTPS, credentials/fragments are removed, raw escaped paths and query semantics
are retained, and normalized duplicates collapse. There are at most 20 links of 2048 characters each.
Provider types require parsed hostnames and supported personal-profile paths. Repository/project links do not
become GitHub personal-profile suggestions. URI annotations require associated visible header context.
Omitted lists clear links under existing full-replacement semantics. New controls preserve raw typing state.

## 12. Ambiguity / no-guess cases

Competing scalar candidates stay null with bounded diagnostic codes. Names require contact context and a
label or structural prominence; capitalization/first-line position alone is insufficient. Candidate locations
come only from supported contact/header context, never employer/education locations. No global text fallback,
geocoding, email-username name inference, or country inference is used. Supported countryless numbers stay
readable raw values. Malformed numbers/emails, dates/IDs/CGPAs, unsupported header context, project URLs,
unassociated annotations, and lookalike provider domains are covered. Legal declarations, relevant experience,
notice period, and all search preferences remain manual.

## 13. Persistence / historical-version results

The focused database/upload/migration group passes all 35 tests. DRAFT creation stays revision 1; upload and
GET agree; links and metadata round-trip; direct downstream facts/matching reject DRAFT. Acceptance, correction,
clearing, ordinary replacement, stale-revision rejection, no-op confirmation, and rollback pass. Evidence is
server-owned, persisted with non-updatable extraction columns, and not accepted from confirmation payloads.
Database row snapshots prove rules-v1 through rules-v5 historical data, versions, revisions, and timestamps
remain unchanged across migration and new uploads. Historical no-op confirmation also preserves null metadata.

## 14. Frontend results

The existing scalar initialization displays suggestions through the normal profile GET after upload navigation.
Link rows support add/edit/remove. Original-evidence display explicitly states that corrections are not attested
by resume evidence. Reload, profile switching, correction, clearing, 409 reconciliation, hidden structured-field
preservation, and exclusion of server-owned metadata from confirmation requests pass. No upload cache was added.

## 15–18. Verification totals

These groups overlap; they must not be summed as independent tests.

| Verification | Result |
|---|---|
| Final focused backend subset (facts, link validation, layout, service, candidate truth) | 55 passed |
| Initial exact-parity gate before wiring facts | 58 passed, 0 skipped |
| Parser/extractor/service regression stage | 192 passed, 0 skipped |
| Upload/persistence/migration stage | 35 passed, 0 skipped |
| Matching/evaluation + expanded facts/layout stage | 184 passed, 0 skipped |
| All backend integration-named suites in clean verify | 139 passed across 14 suites |
| Frontend Vitest | 18 passed across 11 files |
| Playwright Chromium journeys | 4 passed |
| Production TypeScript + Vite build | Passed |
| Frontend oxlint | Exit 0; 7 existing effect/dependency warnings |
| Full Maven clean verify | 1,145 discovered; 1,140 passed; 5 skipped; 0 failures/errors |

Full clean verify completed in 2:55 and produced the executable Spring Boot JAR. Maven was run with the
existing dependency cache and `-Drulesv5.localPdfDirectory=C:/Users/mitta/Downloads`. The frontend used the
installed package-script CLIs directly (`vitest run`, `tsc -b`, `vite build`, `playwright test`) because npm was
not on the executor PATH. Browser tests used an ignored temporary config on port 5174 to preserve an existing
server on 5173. The temporary Vite process was stopped after testing.

## 19. Skipped tests and reasons

Only existing opt-in live-provider tests were skipped:

- Lever gateway acceptance: `jobcopilot.lever.live` not enabled.
- Workday gateway acceptance: `jobcopilot.workday.live` not enabled.
- Workday live synchronization: `jobcopilot.workday.live.sync` not enabled.
- Gemini live acceptance: `jobcopilot.gemini.live` not enabled.
- OpenAI live smoke: `JOB_INTELLIGENCE_LIVE_TEST` not enabled.

No resume parity, facts, persistence, frontend, or browser-journey test was skipped.

## 20. Diff check

`git diff --check` passes. Windows LF/CRLF advisory messages are not whitespace failures.

## 21. Deviations / implementation adaptations

The core approved architecture is unchanged. Immutable layout types are nested records in `ResumeDocument`.
Backwards-compatible Java constructors retain existing test/caller seams; legacy HTTP bodies without links
remain accepted. Newly written resume/profile lifecycle timestamps are truncated to PostgreSQL microsecond
precision after integration tests exposed upload-versus-GET timestamp rounding; no historical timestamps
are changed. This is the only additional production behavior correction.

## 22. Remaining manual QA / risks

The real master resume was not supplied as an acceptance attachment. Its actual header extraction should be
checked locally; committed tests use a synthetic equivalent. Conservative header/name/location/annotation
rules intentionally leave unsupported layouts blank rather than guessing. Rotated header contexts are not
used for contact inference. New uploads have extra read-only response fields; clients that reject unknown
fields need coordinated updates. Existing full replacements from old clients omit and clear links.

The Space/Enter bug remains an explicit separate ticket. This milestone leaves the existing per-keystroke
scalar trimming and textarea line normalization unchanged; only the new link controls avoid that behavior.

No PII logging, URL fetching, external resume parser, legal/preference inference, matching/ranking change,
automatic historical reparse, commit, or push was introduced.
