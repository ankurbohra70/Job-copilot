# JC-010 reuse findings and later connector research

Research provenance: the completed September 16, 2026 reuse audit inspected
[`kineticsystem/find-me-a-freaking-job`](https://github.com/kineticsystem/find-me-a-freaking-job).
No permissive license was verified. These are protocol observations and independent
implementation notes, not permission to copy source, regexes, or fixtures. Validate
all provider behavior independently before release. `JC-010-PLAN.md` remains the
implementation and acceptance authority.

## Current classification

| Learning | Decision in the current working tree |
| --- | --- |
| Workday CXS POST shape, trusted endpoint coordinates, bounded one-shot HTTP, search/detail decoding, URL checks, status classification | ALREADY IMPLEMENTED in the JC-010 Phase 1 gateway and decoder. |
| Later search pages may report zero total; a 20 + 20 + 5 inventory still needs every detail and a completeness proof | IMPLEMENTED in JC-010 traversal and adversarial tests. |
| Optional later-page total, escaped HTML, paragraph/list text, Unicode | IMPLEMENTED in JC-010 decoding and normalization tests. |
| Opaque Workday ID versus posting ID, requisition ID, path, locale and repost stability | RESOLVED FOR V1 BETA after later Phase 5 live evidence; the research itself did not select the persisted identity. |
| Typed ATS URL detection, careers-page sniffing, board probes, trusted URL harvesting, candidate provenance | DEFER TO ATS DISCOVERY. |
| Greenhouse and Ashby public endpoint/field sketches | DEFER TO GREENHOUSE/ASHBY. |
| Candidate/profile revision, preference authority, extraction validation and fingerprints, Lever synchronization/lease/reconciliation | ALREADY IMPLEMENTED under JC-006/007/008/009; preserve those contracts. |
| Salary formatting, remote inference and prompt truncation sketches | REJECT for JC-010 scope; evaluate only if a later validated domain ticket needs them. |
| Aggregator and browser/feed adapters, persisted evaluation history | REJECT for JC-010 scope; no authoritative complete-board contract was established. |
| Global company/title/location fingerprints; tenant-only Workday source IDs; successful local truncation; skipped/failed detail treated as complete; automatic source activation; uncontrolled redirects; model-scored authority; model retry; process-local sync | REJECT. |

Workday now enters the shared lease-fenced synchronization path. For the current
fixture-backed contract, detail `jobPostingInfo.id` is the source-scoped
`external_job_id`; later Phase 5 live evidence froze that choice for V1 Beta. Only a complete
search/detail traversal can produce the shared completion token used by
reconciliation. PostgreSQL aftermath tests cover closure, reopening, repeats,
failed search/detail, rate limits, malformed/inconsistent pagination, duplicate
identity/path, and traversal bounds. These tests establish application behavior
for this contract, not real provider identity stability.

## Identity evidence as of September 16, 2026

**STABLE IDENTITY EVIDENCE:** The three captured tenant detail fixtures each
contain an opaque `jobPostingInfo.id`, distinct from their requisition ID,
posting ID, path, and external URL. The decoder and traversal preserve it.
Repeated synthetic fetches with that same ID reuse one listing and canonical
job in PostgreSQL. This proves the local contract only.

**LIKELY BUT UNPROVEN:** The opaque ID is likely the best available posting key.
No captured before/after pair demonstrates its stability across title,
description, location, URL/path, locale, site, or repost changes. The gated live
test checks a single fetch and does not establish those properties. The
`externalUrl` appears to be the public posting/apply entry for the captured
sites; broader apply behavior remains unproven.

**PRESENTATION DATA:** Title, description, location, `externalPath`, and
`externalUrl` are mapped or validated as content and navigation data. They are
allowed to change without intentionally changing the local listing key.

**UNSAFE FOR IDENTITY:** Requisition ID, posting ID, path, URL, title, company,
and location are not accepted as a fallback identity. Requisition IDs may span
sites or postings; path/URL include mutable slugs. Missing or duplicate opaque
IDs fail the run before reconciliation.

The later JC-010 Phase 5 acceptance closed the live identity and V1 Beta launch gates. A sanctioned controlled
edit/closure/reopen lifecycle was unavailable and remains an explicit limitation, not a V1 Beta blocker.

## Future ATS discovery ticket

Map ATS URL detection to a typed provider/source **candidate**. Harvesting trusted
job and application URLs produces another candidate. Careers-page link sniffing is
an evidence-producing fallback. Board probing adds validation evidence, not proof
of employer ownership. Activate an authoritative `JobSource` only after candidate
verification. Keep candidate provenance and state separate from active sources.

Do not activate from untrusted job-description text. A valid empty board can still
be a source. Workday coordinates must retain host, tenant, and site dimensions;
test locale effects on identity. Validate SSRF, redirects, and every final
destination. Use browser rendering only as a bounded fallback when deterministic
provider APIs and direct links do not establish the candidate.

## Future Greenhouse connector ticket

The audit identified a public board JSON endpoint with an optional `content=true`
query and observed fields for jobs, content, `location.name`, `absolute_url`,
`updated_at`, and `first_published` in its [adapter research](https://github.com/kineticsystem/find-me-a-freaking-job/blob/main/jobfinder/sources/greenhouse.py).
Independently validate the endpoint and board variants, job ID stability,
description/location/application mapping, complete inventory and empty-board
behavior. Apply bounded HTTP/JSON and explicit failure classification. Integrate
through the shared synchronization kernel and require live acceptance. The audit
found no dedicated external Greenhouse contract tests.

## Future Ashby connector ticket

The audit identified a public posting API with an observed `includeCompensation=true`
option and fields for plain/HTML description, job/apply URLs, `publishedAt`,
department, team, remote metadata, and compensation summary in its
[adapter research](https://github.com/kineticsystem/find-me-a-freaking-job/blob/main/jobfinder/sources/ashby.py).
Independently validate stable posting identity, field and URL semantics,
completeness, empty boards, response bounds, and failures. Keep compensation as
observed text until a domain contract exists. Integrate through the shared
synchronization kernel and require live acceptance. The audit found no dedicated
external Ashby contract tests.
