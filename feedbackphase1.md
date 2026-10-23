# Phase 1 Review Feedback — Implementation vs `phase1plan.md`

Source root: `api-regression/src/main/java/com/sabyasachi/apiregression/` (paths below are relative to it).
Method: tech-advisor code review plus sentinel-appsec-auditor security audit, run by advisor-orchestrator. Defects were reproduced with probe programs. Project source was not modified; only Maven output went to `api-regression/target/`.

## Summary

Every plan step (1–8, including 7b–7d) exists as code, and 37 of 37 tests pass offline. The core override contract is not met: only listed parameters should change, nothing should be guessed, and nothing should be silently dropped. Six High defects were reproduced, and the current tests pass all six. There are no Critical security findings, but there are four Medium ones. The README Definition of Done overstates what is delivered, so **7b–7d should not be signed off**.

## 1. Implemented vs plan

| Step | Status |
|---|---|
| 1 Skeleton / shaded jar | Done. Fat jar runs; `--check-spec` works on both sample specs. |
| 2 Model / persistence | Done. |
| 3–5 HTTP, compare, runner, token | Done, plus a headless `--run` mode the plan defers to Phase 2. |
| 6 GUI | Done. |
| 7 Postman import | Done. |
| 7b Excel import, override-only | Partly. Many rules in plan §3a.2/§3a.3 are missing or wrong. |
| 7c Spec and gap-fill | Partly. Bare-name gap-fill works for simple OAS3/Swagger2 only. |
| 7d GUI / reporting | Mostly. HTML report lacks sha256, spec version, policy and timestamp; errors are not sorted first; the "manual edits since last import" warning is missing. |
| 8 Polish | Partly. No timeout, retry or thread-pool config. |

## 2. Functional findings

### High
- **H1: Undeclared fields get added.** An explicit body path found in neither payload nor spec is ADDED; the plan says NOT_IN_SPEC. With no spec it is also ADDED; the plan says SPEC_UNAVAILABLE. Cause: a null from `navigateExplicitPath` is treated as "no schema, go ahead". `payload/ParameterOverrideService.java:385-388, 429-430`.
- **H2: A second sheet can silently wipe the first.** Apply mode does not flag two sheets on the same TestCase as CONFLICT (dry run does). Cause: a `HashSet<TestCase>` (`ParameterOverrideService.java:89,130,143`) with value-based `hashCode` over mutable fields (`model/TestCase.java:200-226`). Use an identity set.
- **H3: Spreadsheet junk is written as real data.** A cached formula error is applied as `"#ERROR!"`; a formula with no cached value becomes `0`; numbers >= 1e15 are applied after a warning (plan says ERROR), e.g. 1234567890123456789 became 1234567890123456800. `importer/ExcelParameterImporter.java:266-272, 294-296, 323-327`; `ParameterOverrideService.java:209`.
- **H4: Query, header and path rows skip all the rules.** `<empty>` is written as `x=%3Cempty%3E`; `<null>` becomes a literal header value. No spec check, type coercion or constraint check; path values are not URL-encoded. `ParameterOverrideService.java:246-268`; `payload/RequestPartOverrider.java:63-73, 125, 141-151`.
- **H5: Body handling breaks non-body sheets.** A sheet applied to a GET writes `{ }` into the body (`payload/JsonTemplateCodec.java:78-79`). A non-JSON body aborts the whole sheet including query/header rows (`ParameterOverrideService.java:165-176`); the plan says only body rows should fail. BODY_NOT_APPLICABLE is never raised; array-rooted bodies are rejected (`JsonTemplateCodec.java:86-88`).
- **H6: A failed row leaves partial changes behind.** E.g. `{"a":{"b":[]}}` was persisted after a failed row (`payload/PayloadOverrider.java:84-95, 109-118`). Apply should work on a copy and roll back failed rows.

### Medium
- **M1: Sheet-to-API matching.** `METHOD /path` uses `url.contains()`, so `GET /customers/{id}` is UNMATCHED and `GET /customers` is AMBIGUOUS (`ParameterOverrideService.java:507-523`). operationId matching from the spec is not done.
- **M2: Operation lookup.** Only the `{{baseUrl}}` prefix is stripped (`spec/SpecIndex.java:211-214`). AMBIGUOUS_OPERATION is swallowed and reported as SPEC_UNAVAILABLE (`ParameterOverrideService.java:187`). Base-path stripping ignores segment boundaries (`SpecIndex.java:225`).
- **M3: Spec walking.** oneOf/anyOf never walked. On OAS 3.1, array items and types are lost (`spec/SchemaNavigator.java:150, 170`). NEEDS_INDEX never creates `[0]`. A bare name that is a query param goes to BODY when there is no spec (`ParameterOverrideService.java:439-467`).
- **M4: Type coercion.** A `{{var}}` placeholder into an integer field is quoted (`payload/TypeCoercer.java:58-60`). An existing unquoted placeholder is treated as a string. A spec type of object/array does not trigger JSON parsing (`TypeCoercer.java:95-100`). `format: date` still gets a full datetime.
- **M5: Constraint checks** (`spec/ConstraintValidator.java:77, 90, 141`). Enum comparison ignores case. `pattern` uses `matches()` instead of `find()`, giving false ERRORs in STRICT mode. Missing `nullable` is treated as nullable. Non-body params never checked.
- **M6: Importer reporting** (`importer/ExcelParameterImporter.java`). Hidden sheets (line 77) and case-insensitive duplicates (line 241) vanish from the report. Row cap truncates the sheet instead of marking it NOT_APPLIED (line 166). Encrypted workbooks get a generic error. Undocumented `op`/`path`/`param` columns are accepted and the tests use them instead of the plan's contract.
- **M7: Jackson version skew.** `pom.xml:20` pins 2.18.2 under swagger-core 2.2.53 (built on 2.22). The plan marks this "must fix". slf4j-api (2.0.11) and slf4j-simple (2.0.16) also differ.
- **M8: specPath saved as an absolute path** (`ui/MainFrame.java:260`) and resolved against the working directory (`ParameterOverrideService.java:68`). Plan says relative to the suite file. A missing spec file fails silently.
- **M9: Preview vs apply.** Apply re-reads workbook and spec with no sha check (`ui/ParameterImportDialog.java:168`). The success dialog shows the dry-run counts (line 178).
- **M10: ParamPath parsing** (`payload/ParamPath.java:117-144`). Malformed paths such as `[-1]` accepted silently. Keys containing `.` or `[` break `PayloadOverrider.java:140`.
- **M11: Error reporting.** A catch-all OVERRIDE_FAILED replaces specific reason codes (`ParameterOverrideService.java:270-275`).

### Low
- Workbook not closed if an exception occurs (`ExcelParameterImporter.java:61, 86`).
- A delete path exists and is tested (`PayloadOverrider.removeOverride`, line 155), contradicting the plan's "no deletions" rule.

## 3. Security findings

No Critical findings. Four Medium findings were confirmed with offline proofs of concept.

- **S1 (Medium): spec loading follows remote and local `$ref`s** (`spec/ProviderSpecLoader.java:88-94`). Uses resolve/resolveFully with no allowlist; the plan says remote refs are blocked, but they are not. A remote `$ref` caused an outbound GET to a local test server; a relative `$ref` read an arbitrary local YAML/JSON file. Fix: pre-check that every `$ref` is `#/...` or under the spec's own directory, and set `setResolve(false)`.
- **S2 (Medium): secrets persisted and exported in plaintext.** `runner/TokenProviderResolver.java:78-79` keeps the token response body, headers and token, and they go into the JSON and HTML reports. Resolved URLs containing `?api_key=` also appear in reports. Postman environment values and `Auth` secrets are saved in plain text by `store/SuiteRepository.java` and `importer/PostmanEnvironmentImporter.java`. Fix: redact in the report layer, mark `tokenExtracted` `@JsonIgnore`, store secrets as environment references, set file mode 0600.
- **S3 (Medium): redirects leak API-key and custom auth headers cross-host** (`client/HttpRunner.java:82`, `Redirect.NORMAL`). The JDK already strips `Authorization` and `Cookie`; `X-Api-Key` and other custom headers are forwarded. Fix: `Redirect.NEVER`, or follow manually and drop secret headers when the origin changes.
- **S4 (Medium): regex DoS from a spec `pattern` run against Excel cell values** (`spec/ConstraintValidator.java:137-142`). `^(.*a){20}$` against 35 characters ran for more than 120 seconds. Fix: cap input length, run the match under a deadline, keep it off the Swing UI thread.
- **S5 (Low): possible zip bomb** (suspected, not proven). Only a 20 MB compressed-size check guards `ExcelParameterImporter.java:20, 44, 61`. Set `ZipSecureFile` limits.
- **S6 (Low): HTML report hardening.** `ReportWriter.java:~118` appends enum values unescaped. Not exploitable today. Add a Content-Security-Policy meta tag.

Refuted or mitigated: formula evaluation (POI uses cached values only; no CSV/XLSX export), Jackson polymorphic typing (none in use), TLS (no custom trust code), SnakeYAML (alias limits at defaults), path traversal and command execution (no vector across a trust boundary).

Dependencies: the auditor found no known CVEs in the versions used, based on memory only. Run `mvn org.owasp:dependency-check-maven:check` to confirm.

Still needs live verification: fetching a cloud-metadata ref (169.254.169.254), heap exhaustion from a crafted xlsx, and whether swagger-parser follows `file:` refs and symlinks.

## 4. Plan vs code gaps

- **7c missing:** oneOf/anyOf/discriminator, `additionalProperties`, OAS 3.1; the codes did-you-mean, REQUIRED_SIBLINGS_MISSING, AMBIGUOUS_LOCATION and OPERATION_NOT_FOUND.
- **7d missing:** the report fields listed in section 1 and the "manual edits since last import" warning.
- **Remote `$ref` blocking:** the plan says blocked; it is not (S1).
- **Jackson bump:** plan says "must fix"; not done (M7).
- **README Definition of Done:** claims invariance and idempotency tests that do not exist, and claims "conflicting sheets not applied", which is false (H2).
- **Test coverage:** far below the plan's table; every High finding passes the current suite. `ExcelParameterImporterTest` has 1 test against about 18 planned; `ParameterOverrideServiceTest` has 1; `GapFillTest` has 3. No untouched-fields invariance test, no byte-identical idempotency test, no `XlsxFixtureBuilder`, no customers or OAS 3.1 fixtures.

## 5. Recommended next steps

1. Fix H1–H6 and M1 first, with one regression test per defect. Probe programs ready to convert: `/private/tmp/claude-501/-Users-sabyasachiroy-projects-ApiTesting/fd3c63c1-4bef-498c-ac6e-5188438aae55/scratchpad/probe/Probe.java` and `.../scratchpad/probe31/P31.java`.
2. Fix S1 (restrict `$ref`s) and S2 (redact secrets in reports and suite files). Both contradict the plan's own guarantees.
3. Switch to `Redirect.NEVER` (S3), cap and time-box regex validation (S4), set `ZipSecureFile` limits (S5).
4. Bump Jackson to 2.22.x, then re-run the full suite and fat-jar smoke tests.
5. Implement the missing 7c features and reason codes, build out the plan's test table, and correct the README Definition of Done until it is true.

Proof-of-concept sources: `/private/tmp/claude-501/-Users-sabyasachiroy-projects-ApiTesting/fd3c63c1-4bef-498c-ac6e-5188438aae55/scratchpad/poc/` and `.../scratchpad/data/`.
