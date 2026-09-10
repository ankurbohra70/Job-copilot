# JC-007 Phase 4 — Independent adversarial QA

## Verdict

PASS WITH FIXES

## Executive summary

The corrected working tree is safe to close and freeze. The initial implementation was not: HTTP 503 with Retry-After: 0 generated two real POSTs. QA reproduced and fixed this P1 defect, two P2 metadata/classification defects, a P2 deadline-budget defect, and a P3 Spring coexistence defect. Forty additional test cases pass. No live provider request was made.

The freeze baseline must include the two provider-neutral core corrections listed below. The assertion that frozen core remained completely unchanged is therefore NO; the public ModelInput/model contract, source isolation, schemas, decoding, validation, and derivation authority remain intact.

## Repository state

- Branch: week-2
- Starting/current commit: b51fb6d1c4c3492edbf8d9727013502c5e3bf85d
- Initial tree: eight modified tracked files; seven untracked Java files in three status groups. No commits, resets, staging, or branch changes performed.
- Git required a command-scoped safe.directory override because the sandbox identity differed from repository ownership. No global Git configuration changed.

Initial Phase 3 boundary (attributed by actual diff content, matching earlier QA sources/logs):

- src/main/java/com/jobcopilot/intelligence/JobIntelligenceDecoder.java
- src/main/java/com/jobcopilot/intelligence/JobIntelligenceRunner.java
- src/test/java/com/jobcopilot/intelligence/JobIntelligenceIsolationTest.java
- src/test/java/com/jobcopilot/intelligence/JobIntelligenceRunnerTest.java
- src/test/java/com/jobcopilot/intelligence/JobIntelligencePhase3QaTest.java (untracked)
- README.md Phase 3 section

Initial Phase 4 boundary:

- pom.xml; src/main/resources/application.yml; README.md Phase 4/configuration sections
- src/main/java/com/jobcopilot/intelligence/openai/OpenAiJobIntelligenceModel.java
- src/main/java/com/jobcopilot/intelligence/openai/OpenAiJobIntelligenceProperties.java
- src/main/java/com/jobcopilot/intelligence/openai/OpenAiJobIntelligenceConfiguration.java
- src/test/java/com/jobcopilot/intelligence/openai/OpenAiJobIntelligenceModelTest.java
- src/test/java/com/jobcopilot/intelligence/openai/OpenAiJobIntelligenceConfigurationTest.java
- src/test/java/com/jobcopilot/intelligence/openai/OpenAiJobIntelligenceLiveSmokeTest.java
- src/test/java/com/jobcopilot/intelligence/JobIntelligenceBoundaryQaTest.java

The six OpenAI Java files were initially untracked. Git has no separate Phase 3/4 commit boundary, so historical attribution cannot be proven solely from HEAD. Initial tracked diff and source hashes were saved in .local-backups/phase4-qa. Hash comparison confirms the five Phase 3 Java files above are unchanged by QA; their existing work was preserved.

QA changed:

- Production: JobIntelligenceAttempt.java, JobIntelligencePrompt.java, OpenAiJobIntelligenceConfiguration.java, OpenAiJobIntelligenceModel.java, pom.xml.
- Tests: OpenAiJobIntelligenceModelTest.java, OpenAiJobIntelligenceConfigurationTest.java, OpenAiJobIntelligenceLiveSmokeTest.java, JobIntelligenceBoundaryQaTest.java, JobIntelligenceRequestBoundaryTest.java.
- Added: JobIntelligencePhase4DeadlineQaTest.java and this report.
- Documentation: README.md Phase 4 transport/deadline description.

All Java paths above are under the intelligence directories listed in the inventories. QA did not alter properties, application configuration, schema, prompt resources, decoder, validator, derivation, persistence, or matching production code.

## Architecture verification

| Check | Answer |
|---|---|
| JobIntelligenceModel unchanged | YES |
| Provider SDK confined to integration package | YES |
| Frozen core unchanged | NO — Attempt and Prompt corrected for remaining budget |
| Decoder remains authoritative | YES |
| Validator remains authoritative | YES |

No provider types/enums or Spring imports entered core. The package-private budget-copy operation accepts an already sealed ModelInput and Duration, preserves all content and identities, and exposes no new public API. The boundary test now explicitly permits only this narrowly typed budget refresh alongside assembly; content substitution compilation tests remain intact. A regression compares the complete refreshed content against the original. Provider-helper boundary checks now apply beyond only the named adapter class. Source checks are not a general bytecode architecture verifier; actual current imports were also inspected.

## Findings

### P1 — OkHttp repeats a 503 POST

- Path: OpenAiJobIntelligenceConfiguration.java:40 (and pom.xml).
- Reproduction: transientHttpFailureIsClassifiedWithoutRetry, HTTP 503 plus Retry-After: 0; expected 1, observed 2 requests before fix.
- Root cause: OkHttp 4.12.0 RetryAndFollowUpInterceptor processes this response independently of retryOnConnectionFailure. The initial SDK transport really did set that flag false; maxRetries(0) was insufficient.
- Fix: use the SDK's public OkHttp bridge with an application interceptor marking request bodies isOneShot=true, alongside disabled connection retries and both redirect flags. Preserve original HTTP status/body and existing neutral classification. Explicit compile dependency uses the same existing OkHttp 4.12.0 version. Live smoke and loopback tests use the production factory.
- Regression: seven transient HTTP codes with Retry-After: 0; redirect/authentication, connection-reuse and disconnect matrices; effective transport flags/authenticators/interceptor count.

### P2 — Queue time grants a fresh provider budget

- Paths: JobIntelligenceAttempt.java:72; JobIntelligencePrompt.java:53.
- Reproduction: queueDelayIsDeductedBeforeProviderInvocation; 900 ms and 999 ms of a one-second budget elapsed before execution. Adapter received one second instead of 100 ms and 1 ms.
- Root cause: attempt used its deadline for waiting, but passed the original assembled timeout to analyze.
- Fix: calculate remaining budget after queue/providerId time and copy only that value into the sealed input before analyze. Original input stays unchanged; deadline authority and cancellation stay in Attempt.
- Regression: two deterministic clock/executor cases, full content preservation, existing saturation/interruption/late-completion tests and real transport timeout tests.

### P2 — Missing metadata discards completed output

- Path: OpenAiJobIntelligenceModel.java:85.
- Reproduction: missingMetadataDoesNotDiscardCompletedCandidate; absent cached_tokens, input_tokens_details or model caused PERMANENT_FAILURE.
- Root cause: SDK required-field getters threw before candidate extraction, even though local result metadata permits null.
- Fix: optional JsonField access for metadata, retaining available token counts and preserving raw candidate text.
- Regression: five variants covering missing usage, details, cached tokens, model, and HTTP request ID. Response ID is not substituted for request ID.

### P2 — Permanent failed-envelope errors marked retryable

- Path: OpenAiJobIntelligenceModel.java:93.
- Reproduction: failedEnvelopeRespectsPermanentVersusTransientError; invalid_prompt and data_residency_mismatch both incorrectly returned RETRYABLE_FAILURE.
- Root cause: every status=failed envelope was classified retryable without inspecting its typed error code.
- Fix: transient classification for server_error, rate_limit_exceeded, and vector_store_timeout; other failed errors are permanent. No retry is performed.
- Regression: server/rate-limit versus invalid-prompt/data-residency cases, one actual request each, no error message in result.

### P3 — Existing fake/model bean makes runner ambiguous

- Path: OpenAiJobIntelligenceConfiguration.java:63.
- Reproduction: existingModelDoesNotCreateAmbiguousRunnerDependency; enabled context plus existing JobIntelligenceModel failed startup with two candidates.
- Root cause: unconditional provider model bean creation.
- Fix: ConditionalOnMissingBean on the OpenAI model bean. No routing architecture added.
- Regression: context has one model and one runner with an existing fake; default enabled wiring still passes.

No unresolved blocking findings.

## Exactly-once transport verdict

Counts are actual loopback HTTP requests per independent analyze/run. The guarantee established is at most one request attempt, not guaranteed remote execution/delivery.

| Case | Before relevant fix | Final count |
|---|---:|---:|
| 200 completed | 1 | 1 |
| 408 + Retry-After: 0 | 1 | 1 |
| 409 + Retry-After: 0 | 1 | 1 |
| 429 + Retry-After: 0 | 1 | 1 |
| 500 + Retry-After: 0 | 1 | 1 |
| 502 + Retry-After: 0 | 1 | 1 |
| 503 + Retry-After: 0 | 2 | 1 |
| 504 + Retry-After: 0 | 1 | 1 |
| Malformed 200 JSON | 1 | 1 |
| Disconnect before headers | 1 | 1 |
| Close after headers / partial body | 1 | 1 each |
| Accepted request, stalled response | 1 | 1 |
| 301/302/303/307/308 and 401/407 challenges | 1 | 1 each |

Prior successful connection followed by a failing independent attempt: two requests total, one per call. Candidate decode failures never cause a repair call. SDK retry found: NO. Hidden transport retry found: YES, reproduced and fixed. Hidden repair/second completion found: NO.

Effective production transport has connection retries false, both redirect flags false, no authentication handlers, no network interceptors, and one body-replay prevention interceptor. SDK maxRetries remains zero and logging OFF, with no fromEnv override. Bytecode inspection corroborated both the original SDK setting and the independent OkHttp 503 follow-up path. DNS multi-address/TLS/HTTP2 coalescing were not separately fault-injected; local tests do not claim exhaustive network-environment coverage.

## LLM_FIRST isolation verdict

- Structural isolation preserved: YES.
- Transport-boundary canary absent: YES, complete serialized JSON inspected.
- Semantic request changes when unrelated canonical state changes: NO; complete JSON trees equal.
- Alternate leak found: NO.

Canonical canary exists in an assembled Hybrid fixture before the LLM_FIRST call. Changing unrelated canonical content cannot change the latter's model, instructions, input, schema, or other wire fields.

## Hybrid verdict

- Canonical context transmitted only through assembled strategyData: YES.
- Adapter interprets canonical semantics: NO.
- Raw source still authoritative downstream: YES.

Java canonical context with Python-only raw source remains in the assembled input exactly once; returned Java evidence is rejected by the existing validator. Earlier Phase 3 tests also cover matching Python evidence attached to an unsupported Java claim.

## Structured Output verdict

Strict JSON Schema enabled: YES. Repository schema unchanged: YES. Transmitted schema semantically identical: YES. Provider helper changed schema unexpectedly: NO. Existing decoder still runs: YES.

Full JSON-tree comparison includes $defs, references, anyOf/nullability, enums, required fields, additionalProperties, and all bounds. No second output DTO was introduced. Completed candidate tests cover unknown/missing/duplicate fields, malformed JSON, extreme numbers, trailing content, oversized and empty candidates; all remain terminal DECODE failures. Valid candidate regression paths remain accepted.

## Provider-outcome verdict

Completed: raw single text candidate only. Refusal: REFUSED, no candidate. Incomplete: INCOMPLETE, partial text discarded. Multiple text candidates: permanent unexpected shape, never concatenated. Reasoning items before/after the message are ignored. Empty/no-text completed output reaches the decoder. HTTP 408/409/429/5xx are retryable classification only; 400/401/403/404/422 permanent. Malformed envelopes fail safely. Unexpected IllegalStateException from the SDK collaborator propagates out of the adapter and the existing invocation boundary classifies execution defects.

The response iteration does not assume output[0] is a message, consistent with the [official Responses reference](https://developers.openai.com/api/reference/java/resources/responses/methods/create). The distinction between request errors and transient provider failures follows [official error guidance](https://developers.openai.com/api/docs/guides/error-codes).

## Timeout verdict

Domain timeout authority preserved: YES. Provider receives remaining budget: YES, fixed. Cancellation does not retry: YES in tested transport paths. Late response cannot replace terminal result: YES. Saturation/expired queued work does not invoke provider: YES. Waiting-thread interrupt status remains preserved. Direct provider timeout is classified as transport I/O; outer domain deadline stays TIMEOUT. Latency uses monotonic elapsed time and is nonnegative.

## Security verdict

Real secret committed/found in reviewed tree: NO. API key appears in properties toString: NO. Provider body leaks to public failure metadata: NO. Request logging exposes sensitive payload: NO logging site found; SDK logging explicitly OFF.

Failure tests inject title, description, candidate, refusal and provider-error sentinels and assert safe results. Keys are obvious dummy values. Fixed prompt instructions were inspected; no logger/second logging subsystem was introduced. No live key was present. These checks cover this integration's configuration and result boundary, not externally enabled packet capture or arbitrary application logging.

## Dependency verdict

Spring Boot remains 4.1.1; application Jackson remains tools.jackson 3.1.5. SDK 4.60.0 brings com.fasterxml.jackson 2.21.5, Kotlin, OkHttp/Okio and schema-helper libraries. QA promotes the already-used OkHttp 4.12.0/Okio transitives to compile access; no transport version change. No additional logging implementation, retry library, reactive stack or Spring AI was introduced. No observed linkage/serialization regression in the complete suite. Dependency tree saved under .local-backups/phase4-qa/dependencies.txt.

## Wiring verdict

Disabled/no key: starts without provider/client/runner. Key only: remains disabled. Enabled/no key: safe bounded startup failure. Invalid URL/nonpositive timeout: safe validation failure before client creation. Enabled valid dummy config: exactly one client/model/prompt/runner, no startup request. Existing fake model: one model/runner after fix. General multi-provider routing is outside scope.

## Live smoke review

Correctly gated: YES. Executed: NO — OPENAI_API_KEY absent and opt-in flag not enabled. It calls the real runner, retains decoder/validator stages, performs no persistence, and does not repeat poor output. Default gpt-5.6-terra supports Responses and Structured Outputs according to its [official model page](https://developers.openai.com/api/docs/models/gpt-5.6-terra); OPENAI_MODEL can override it. The test is transport/pipeline smoke, not quality evaluation, and does not require accepted extraction for all model content. Production factory reuse ensures the replay fix applies to live execution too. Actual live compatibility/request count remains unobserved.

## Verification results

All Maven commands ran from the repository with the existing local-cache override. Sandboxed compilation initially could not access one cached jar; approved elevated runs completed. No test or live-test gate was disabled to obtain success.

```powershell
.\mvnw.cmd '-Dmaven.repo.local=C:\Users\mitta\.m2\repository' '-Dtest=OpenAiJobIntelligenceModelTest,OpenAiJobIntelligenceConfigurationTest' test
# Original baseline: 25 tests, 0 failures/errors/skips.

.\mvnw.cmd '-Dmaven.repo.local=C:\Users\mitta\.m2\repository' '-Dtest=OpenAiJobIntelligenceModelTest,JobIntelligencePhase4DeadlineQaTest' test
# Before fixes: 52 tests, 6 failures, 0 errors/skips. After fixes: 52, 0 failures/errors/skips.

.\mvnw.cmd '-Dmaven.repo.local=C:\Users\mitta\.m2\repository' '-Dtest=OpenAiJobIntelligenceConfigurationTest' test
# Coexistence reproduction: 9 tests, 1 failure. Final focused suite: all 9 pass.

.\mvnw.cmd '-Dmaven.repo.local=C:\Users\mitta\.m2\repository' '-Dtest=OpenAiJobIntelligenceModelTest#failedEnvelopeRespectsPermanentVersusTransientError' test
# Reproduction: 4 tests, 2 failures. Final focused suite includes all four passing.

.\mvnw.cmd '-Dmaven.repo.local=C:\Users\mitta\.m2\repository' '-Dtest=*Intelligence*Test,*MinimumExperience*Test,*JobExtractionBaseline*Test' test
# Final: 267 tests, 0 failures, 0 errors, 1 gated live skip.

.\mvnw.cmd '-Dmaven.repo.local=C:\Users\mitta\.m2\repository' test
# 643 tests, 0 failures, 0 errors, 1 gated live skip.

.\mvnw.cmd '-Dmaven.repo.local=C:\Users\mitta\.m2\repository' clean verify
# 643 tests / 44 suites, 0 failures, 0 errors, 1 gated live skip; packaging passed.

.\mvnw.cmd '-Dmaven.repo.local=C:\Users\mitta\.m2\repository' dependency:tree '-DoutputFile=.local-backups/phase4-qa/dependencies.txt'

git -c safe.directory=C:/Users/mitta/OneDrive/Documents/GitHub/Job-copilot diff --check
git -c safe.directory=C:/Users/mitta/OneDrive/Documents/GitHub/Job-copilot status
git -c safe.directory=C:/Users/mitta/OneDrive/Documents/GitHub/Job-copilot diff
```

Final XML counts independently summed; only OpenAiJobIntelligenceLiveSmokeTest skipped. Logs: qa-phase4-baseline.log, qa-phase4-adversarial-before.log, qa-phase4-adversarial-after.log, qa-phase4-wiring-before.log, qa-phase4-failed-envelope-before.log, qa-phase4-focused-final.log, qa-phase4-full.log, qa-phase4-clean-verify.log. Final diff --check clean; Git emits ordinary LF/CRLF conversion notices. Working changes remain uncommitted and untracked integration files must be included in any release commit.

## Scope audit

No new REST endpoint, intelligence persistence, migration, queue/background worker, repair/retry/fallback, second provider/router, Spring AI, evaluation framework/dataset, candidate AI, JC-006, matching, ranking or recommendation change. Existing regression-test databases were used only by the pre-existing Testcontainers suite. No Phase 5 work performed.

## Release recommendation

Freeze the corrected working tree, including the explicitly documented core budget fix, rather than the initially reported implementation.

PHASE 4 RELEASE: YES
PHASE 4 CLOSURE: YES
SAFE TO FREEZE PHASE 4: YES
READY FOR JC-007 NEXT PHASE: YES
