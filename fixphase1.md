# Phase 1 Fix Plan — API Regression Tool

Companion to `phase1plan.md` (the contract) and `feedbackphase1.md` (the review). This document says how to fix every finding in the review, in what order, and how to prove each fix.

Source root: `api-regression/src/main/java/com/sabyasachi/apiregression/` (all main paths below are relative to it). Tests: `api-regression/src/test/java/com/sabyasachi/apiregression/`, resources `api-regression/src/test/resources/`.
Line numbers are taken from the review and refer to the code as reviewed. Re-confirm them with grep before editing, because earlier work packages shift lines.

Sign-off rule: 7b–7d are not signed off until WP0–WP7 are complete and the Definition of Done (section 8) is ticked.

---

## 1. Work packages, order, dependencies, effort

| WP | Scope | Findings | Depends on | Effort |
|---|---|---|---|---|
| WP0 | Test harness first: `XlsxFixtureBuilder`, spec fixtures, probe conversion (failing tests, red) | enables all | none | 1.5 d |
| WP1 | Override engine correctness: transaction model, identity conflict set, cell rules, body handling, sheet matching | H1–H6, M1, M2 (partial), M10, M11, Low | WP0 | 5–6 d |
| WP2 | Spec loader hardening | S1 (+ M2 remainder, M8) | WP0; independent of WP1 | 1.5 d |
| WP3 | Secrets and report redaction | S2, S6 | WP0 | 2 d |
| WP4 | HTTP and validation hardening | S3, S4, S5, M5 | WP0; M5 uses WP1 `RequestPartOverrider` changes | 2 d |
| WP5 | Jackson/slf4j alignment + dependency-check | M7 | WP1–WP4 merged (so regressions are attributable) | 0.5 d |
| WP6 | Missing 7c: oneOf/anyOf, additionalProperties, OAS 3.1, reason codes; M3, M4 | M3, M4, 7c gaps | WP1, WP2, WP5 | 4 d |
| WP7 | 7d + remaining importer/UI items + Step 8 + README | M6, M9, M8 (UI half), 7d gaps, Step 8 | WP1, WP3, WP6 | 3 d |
| WP8 | Test-table build-out, invariance/idempotency, sign-off, live verification | test coverage, DoD | all | 2 d (partly parallel with WP6/7) |

Total roughly 21–23 engineer-days. Recommended sequence follows the review: WP0 → WP1 → (WP2 ∥ WP3) → WP4 → WP5 → WP6 → WP7 → WP8. WP2 and WP3 touch disjoint files from WP1 and can run in parallel with it if two people are available. WP5 deliberately comes after all behaviour fixes, then the whole suite and fat jar are re-run on the new Jackson.

Rule for every WP: write the failing regression test first, confirm it fails on current code for the reason the review states, then fix, then confirm green. Exit gate per WP: `mvn -q test` green, no previously passing test deleted (only tests that assert wrong behaviour may be changed, and each such change is listed in the PR description).

---

## 2. Cross-cutting design decisions

### D1. Apply-on-copy / rollback transaction model (H6, H5, H2)
- `ParameterOverrideService` never mutates a TestCase during processing. For each sheet it builds a `WorkingRequest` (deep copy of `{url, headers map, parsed body ObjectNode or null}`) from `baseline` (or from the current request if `baseline == null`, capturing baseline then).
- For each row: `PayloadOverrider` / `RequestPartOverrider` operate on a **row-scoped copy** of the working node (`node.deepCopy()`). If the row yields ERROR or SKIPPED, the copy is discarded and the working node is unchanged. If MODIFIED/ADDED, the copy is promoted to be the working node. Cost is a deepCopy per row, acceptable at maxRows 5,000 with typical small payloads. Do not optimise until measured.
- Sheet commit: after all rows, if the sheet is not CONFLICT/UNMATCHED/NOT_APPLIED and the apply policy allows it (`applyWithErrors` or zero errors), write `WorkingRequest` back to the TestCase in a single step. Dry run runs the identical pipeline and simply never commits. This guarantees dry run and apply produce identical entries (also feeds M9).
- Overrider methods return a result object (`OverrideOutcome{action, reasonCode, reason, oldValue, newValue, resultNode}`) instead of mutating in place. Remove any code path that writes into the shared tree before validation completes.

### D2. Identity-based conflict set (H2)
- Replace `HashSet<TestCase>` (`ParameterOverrideService.java:89,130,143`) with `Collections.newSetFromMap(new IdentityHashMap<>())`. Both dry run and apply share one helper `claimTarget(TestCase, sheet)` so the logic cannot diverge again.
- Do not "fix" `TestCase.hashCode/equals` (`model/TestCase.java:200-226`) beyond this: value-based equals on a mutable class is unsafe in hash collections. Add a Javadoc warning, and grep for other `HashSet<TestCase>`/`HashMap<TestCase,...>` uses and convert them.
- Conflict is decided in sheet order (workbook order); first claimer wins, later sheets get CONFLICT and are not applied, in both modes.

### D3. Reason-code taxonomy
Single `enum ReasonCode` in `payload/` (replace free strings; keep serialisation as the enum name so existing JSON stays readable). Entry fields `action` + `reasonCode` + `reason` + optional `candidates`/`warnings`.

| Group | Codes |
|---|---|
| Skips | BLANK, DISABLED, DUPLICATE, HIDDEN_SHEET |
| Cell | CELL_ERROR, FORMULA_NO_CACHE, NUMBER_PRECISION_LOSS, INVALID_JSON, TYPE_UNCOERCIBLE, LEADING_ZEROS_DROPPED (warning), TYPE_CHANGED (warning) |
| Path | PATH_INVALID, PATH_UNSUPPORTED (`*`, `..`, `?()`), INDEX_OUT_OF_RANGE, NEEDS_INDEX |
| Resolution | AMBIGUOUS, AMBIGUOUS_LOCATION, AMBIGUOUS_POLYMORPHIC, AMBIGUOUS_OPERATION, AMBIGUOUS_TARGET, OPERATION_NOT_FOUND, SPEC_UNAVAILABLE, NOT_IN_SPEC (carries did-you-mean in `candidates`), UNDECLARED (warning) |
| Body | BODY_NOT_JSON, BODY_NOT_APPLICABLE, BODY_MEDIA_UNSUPPORTED |
| Request parts | QUERY_REPEATED, PATH_PARAM_NOT_FOUND, HEADER_NOT_DECLARED, LOCATION_UNSUPPORTED (cookie) |
| Constraints | CONSTRAINT_ENUM, CONSTRAINT_FORMAT, CONSTRAINT_LENGTH, CONSTRAINT_RANGE, CONSTRAINT_PATTERN, CONSTRAINT_PATTERN_SKIPPED, CONSTRAINT_NULLABLE, READONLY_MODIFY, READONLY_ADD |
| Sheet | UNMATCHED, CONFLICT, ROW_CAP_EXCEEDED, WORKBOOK_ENCRYPTED, WORKBOOK_UNSUPPORTED |
| Warnings only | REQUIRED_SIBLINGS_MISSING, MANUAL_EDITS_DISCARDED, MERGED_CELL, WHITESPACE, FILTERED_ROW |
| Fallback | INTERNAL_ERROR (replaces OVERRIDE_FAILED, see M11) |

Rule: the generic catch-all may only produce INTERNAL_ERROR, includes the exception class and message in `reason`, and logs the stack trace. Every anticipated failure must throw/return a typed `OverrideException(ReasonCode, message, candidates)`.

### D4. `$ref` allowlist policy (S1)
- Allowed: `#/...` (internal) and relative file refs that, after `toRealPath()`, resolve under the real path of the spec's own directory (symlinks resolved; refuse if the real path escapes).
- Denied: any `http:`, `https:`, `ftp:`, `jar:`, `file:` absolute URI, UNC paths, `..` escapes, and any ref whose target extension is not `.json/.yaml/.yml`.
- Implementation: a pre-parse pass over the raw spec tree (Jackson `JsonNode`/YAML) collects every `$ref` string, including those in the files transitively reached (recursively load allowed relative files with the same checks, depth cap 20, file count cap 200, each file size cap 10 MB). Any denied ref becomes a spec message (`REMOTE_REF_BLOCKED` / `REF_OUTSIDE_DIR`) and the load fails closed for that ref (replace the ref with an unresolved marker or fail the whole load; default: fail the whole load, because a half-resolved spec silently misleads gap-fill).
- Parse with `ParseOptions.setResolve(false)` plus our own resolution, or, if staying on swagger-parser resolution, set `resolve=true` only after the pre-check passes and with `setResolveFully(true)` unchanged. Preferred and simpler: pre-check then keep parser resolution (the pre-check is the security boundary; the parser then only ever sees allowed refs). Document that decision; verify with PoC (section 7.3).
- Block-list is not sufficient on its own (redirects, `file:` aliases), hence allowlist.

### D5. Secret redaction strategy (S2)
- Central `util/Redactor` (new, small): `redactHeaders(Map)`, `redactUrl(String)`, `redactBody(String, jsonPaths)`, with a case-insensitive deny set: `authorization, proxy-authorization, cookie, set-cookie, x-api-key, api-key, apikey, x-auth-token, x-access-token, token`, plus any header named in the suite's `Auth`/token config. Query params redacted by name: `api_key, apikey, key, token, access_token, secret, password, signature, sig, code`. Replacement string `***REDACTED***`.
- Redaction lives in the report layer (`ReportWriter`, override report writers, `TestResult` serialisation for reports), applied at write time, so the in-memory objects used for the actual run stay intact. Add a `ReportOptions.includeSecrets` flag default false (a clearly labelled opt-in for local debugging only; not exposed in the GUI in Phase 1).
- `TokenProviderResolver`: stop retaining the token response body and headers on result objects. Keep only: provider name, success flag, status code, `tokenExtracted` boolean. Mark any field that holds the token `@JsonIgnore` and `transient`.
- Suite files: secrets are stored as environment references. Postman environment import maps a variable flagged `type=secret` or whose key matches the secret-name heuristic to a reference `{{env:NAME}}` and a separate `secrets.local.json`/OS environment lookup (default: environment variable `APIREG_SECRET_<NAME>`), with the plain value held only in memory. Open Question Q3 below covers the UX. Minimum acceptable for Phase 1: redaction in reports + `0600` file mode on suite and report files (POSIX; on Windows set owner-only ACL best effort, documented) + a visible warning in the import dialog that these values are saved in plaintext if the user chooses not to use references.

### D6. Regex deadline approach (S4)
- Add `util/SafeRegex`: (1) cap pattern length (1,000 chars) and input length (4,096 chars; longer input = skip check with warning `CONSTRAINT_PATTERN_SKIPPED` "input too long", not ERROR); (2) wrap `CharSequence` in a `DeadlineCharSequence` whose `charAt` checks `System.nanoTime()` against a deadline (default 100 ms per match) and throws `RegexTimeoutException`; this interrupts catastrophic backtracking without threads; (3) on timeout record `CONSTRAINT_PATTERN_SKIPPED` warning (policy WARN) and also warning under STRICT (cannot prove violation), never ERROR; (4) cache compiled `Pattern` per pattern string; (5) use `Matcher.find()` semantics (JSON Schema `pattern` is unanchored, M5).
- The dry run/apply orchestration must not run on the Swing EDT: `ParameterImportDialog` runs dry run/apply in a `SwingWorker`, with progress and cancel.

### D7. Identity of targets and normalisation
- One `TargetMatcher` (new, in `payload/`) does sheet→TestCase matching (M1) so dry run and apply share it. Matching order per plan: exact name → explicit/resolved operationId → `METHOD /path-template`. Path-template match is done on parsed path segments, not `contains`.
- One `UrlParts` helper parses a TestCase url into scheme/host/path/query with `{{var}}` preserved (placeholders in path/host must not be percent-encoded or split on `{`/`}`), used by `SpecIndex` lookup, `RequestPartOverrider`, and `TargetMatcher`.

### D8. Policy for undeclared paths (H1)
Explicit body path resolution returns a tri-state `Resolution{FOUND_IN_PAYLOAD, DECLARED_IN_SPEC(pointer), UNDECLARED, NO_SCHEMA}` instead of null. Mapping: payload hit → MODIFY; spec declared → ADD (with intermediates); `UNDECLARED` with explicit `additionalProperties` true/schema and explicit full path → ADD + UNDECLARED warning; `UNDECLARED` otherwise → ERROR NOT_IN_SPEC; `NO_SCHEMA` (no spec or no operation) → ERROR SPEC_UNAVAILABLE. Null must never mean "go ahead".

---

## 3. Phase WP0 — Test harness (do first)

Purpose: every later fix is red-then-green.

### 3.1 `XlsxFixtureBuilder` (test scope, `src/test/java/.../testsupport/XlsxFixtureBuilder.java`)
- Fluent POI (`XSSFWorkbook`) builder: `sheet(name).target("POST /customers").meta("location","body").row(param, value)` plus typed setters: `.text`, `.number`, `.bool`, `.date`, `.blank`, `.formula(expr, cachedValue)`, `.formulaError(FormulaError)`, `.formulaNoCache()`, `.hidden()`, `.veryHidden()`, `.merge(r1,c1,r2,c2)`, `.hiddenRow(n)`, `.enabled("N")`, `.type("json")`, `.location("query")`.
- Cached formula values: write via `cell.setCellFormula` then set the cached result through `XSSFCell.getCTCell().getV()` / `CTCell.setT(STCellType.E)` for errors; for "no cached value", save without `evaluateAll`. Confirm round trip by re-reading with the importer in a smoke test of the builder itself.
- `build(Path)` writes into `@TempDir`; `encrypted(Path, password)` helper using POI `EncryptionInfo`/`POIFSFileSystem` for the M6 test; `main(...)` generates `examples/sample_params.xlsx` (see 9.4).
- Header contract in builder: always emits plan-contract headers `parameter,value,type,location,enabled,notes` (not `op/path/param`); a separate `legacyHeaders()` switch exists only for the one test that asserts the undocumented aliases are rejected or warned (M6).

### 3.2 Spec fixtures (`src/test/resources/spec/`)
`customers-oas3.yaml`, `customers-swagger2.json`, `customers-oas31.yaml`, same API: `POST /customers` (body `Customer = allOf[Base, {address: $ref Address}]`, `contacts[]` array of `Contact`), `GET /customers` (query `channel`, `limit` integer), `GET /customers/{id}` (path `id` integer, header `X-Request-Id`), a `oneOf`+discriminator `Payment` (card|bank), recursive `Node` schema, `readOnly` `createdAt`, enum `status`, `pattern` `^[A-Z]{2}\d{4}$` on `code`, `date`-format `dob`, `nullable` field (3.0) / `type:[string,null]` (3.1), `additionalProperties: true` on a `meta` object and absent elsewhere, a second operation sharing a leaf name (`city` under both `address` and `billing`) for ambiguity. Plus hostile fixtures: `ref-remote.yaml` ($ref to `http://127.0.0.1:{port}/x.yaml`), `ref-relative-outside.yaml`, `ref-symlink.yaml` (created at test time), `evil-pattern.yaml`.

### 3.3 Probe conversion
Convert the two probe programs into JUnit tests (do not copy their `main`, port the assertions):
- scratchpad `probe/Probe.java` → `ParameterOverrideRegressionTest` (H1–H6, M1, M4, M10 cases).
- scratchpad `probe31/P31.java` → `SpecAndSecurityRegressionTest` (OAS 3.1 array items/types loss, M3; plus S1 PoCs).
- The PoC sources under scratchpad `poc/` and `data/` (S1 remote ref server, S4 `^(.*a){20}$` input, S3 redirect server, S2 report grep) become `SecurityRegressionTest` cases; generate data in `@TempDir`, never reference the scratchpad path.
Procedure: read each probe, list the assertion per defect in a table in the test class Javadoc (defect id → expected-correct behaviour), write each as an independent `@Test` named `h1_undeclaredBodyPath_isNotInSpecNotAdded` etc. Run them: all HIGH ones must fail now (record the failure output in the PR to prove they bite). Mark them `@Disabled("WPx")` only if the build must stay green meanwhile; the preferred workflow is a branch where they are enabled and red until fixed.

### 3.4 Exit criteria
Builder self-tests green; the red/green matrix (defect → test → currently failing) exists in the PR; fixtures validate with `--check-spec` (except the hostile ones).

---

## 4. Phase WP1 — Override engine correctness (H1–H6, M1, M2 part, M10, M11, Low)

Order inside WP1: D3 enum and `OverrideException` → D7 helpers (`UrlParts`, `TargetMatcher`) → D2 → H3 → D1 transaction model with H6 → H1 → H5 → H4 → M10 → M11 → Low items.

### H1 — Undeclared fields get added
- Root cause: `navigateExplicitPath` returns null for both "not declared" and "no schema", and the caller treats null as permission to add (`payload/ParameterOverrideService.java:385-388, 429-430`).
- Change: introduce `Resolution` tri-state (D8) as the return type of `navigateExplicitPath` (and `SchemaNavigator.resolveExplicit`). At the two call sites: `UNDECLARED` → NOT_IN_SPEC ERROR with did-you-mean (case-insensitive equal, or Levenshtein ≤ 2 against sibling property names, max 5 candidates); `NO_SCHEMA` → SPEC_UNAVAILABLE ERROR. The ADD path runs only on `DECLARED_IN_SPEC` (or the additionalProperties exception, delivered fully in WP6). Never add on null.
- Tests (`ParameterOverrideServiceTest` / `GapFillTest`):
  - `h1_explicitPathNotInPayloadNotInSpec_isNotInSpec`: customers-oas3 + POST /customers body `{"name":"a"}`, sheet row `$.bogus.field`=x → entry ERROR/NOT_IN_SPEC, body byte-equal to baseline, did-you-mean absent.
  - `h1_noSpec_explicitMissingPath_isSpecUnavailable`: same without spec → ERROR/SPEC_UNAVAILABLE, nothing added.
  - `h1_typoGetsDidYouMean`: `$.adress.city` → candidates contains `address`.
- Acceptance: both PoCs from the review no longer add fields; no new fields in any body unless reason code is ADDED with a `specPointer`.

### H2 — Second sheet silently wipes the first
- Root cause: value-hash `HashSet<TestCase>` (`ParameterOverrideService.java:89,130,143`) over mutable fields (`model/TestCase.java:200-226`); after sheet 1 mutates the TestCase, the `contains` check in apply mode misses it.
- Change: D2 (identity set + shared `claimTarget`), used by dryRun and apply.
- Tests: `h2_twoSheetsSameTestCase_applyMode_secondIsConflict` (two sheets both targeting TestCase "create customer", both modify different fields; assert second sheet status CONFLICT, none of its entries applied, first sheet's fields still present). `h2_dryRunAndApplyAgree` (same workbook: dry-run and apply summaries equal). Add `h2_distinctTestCasesEqualByValue_notConflict` (two TestCases with identical content but different identity are separate targets).
- Acceptance: DoD "conflicting sheets not applied" is now true in both modes; README sentence becomes accurate (WP7).

### H3 — Spreadsheet junk written as real data
- Root cause: `importer/ExcelParameterImporter.java:266-272` (cached error), `294-296` (no cached value → `0`), `323-327` (≥1e15 only warns); `ParameterOverrideService.java:209` applies the cell regardless of cell kind.
- Change:
  - Formula: `getCachedFormulaResultType()`; `ERROR` → cell kind `ERROR`, row ERROR `CELL_ERROR` ("cached formula error #REF!"); `_NONE`/missing → ERROR `FORMULA_NO_CACHE`; never emit a numeric default.
  - Numeric: use `BigDecimal.valueOf(double)`/`Double.toString` path per plan; if `abs ≥ 1e15` → cell kind `NUMERIC_TOO_PRECISE`, ERROR `NUMBER_PRECISION_LOSS` with message "enter as Text cell". Also guard the literal `1234567890123456789`-style double round trip.
  - The service must refuse to coerce any `ParamRow` whose `cellKind` is an error kind: check at the top of the per-row pipeline (line 209 region) and emit the ERROR entry before touching any node.
  - Define `ParamRow.cellKind` as an enum {TEXT, NUMERIC, BOOLEAN, DATE, FORMULA_OK, ERROR, NO_CACHE, TOO_PRECISE, BLANK}.
- Tests (`ExcelParameterImporterTest` + service level): `h3_cachedFormulaError_isError`, `h3_formulaWithoutCachedValue_isError_notZero`, `h3_numberAtOrAbove1e15_isError` (1e15, 1234567890123456789, and 999999999999999 stays OK), `h3_textCellPan_keptVerbatim`. Each asserts the body is untouched.
- Acceptance: no `#ERROR!`/`0` placeholders reachable; the review's 1234567890123456800 case is ERROR.

### H4 — Query/header/path rows skip all rules
- Root cause: `ParameterOverrideService.java:246-268` applies a raw string; `payload/RequestPartOverrider.java:63-73, 125, 141-151` has no sentinel handling, no spec check, no coercion, no encoding.
- Change: route non-body rows through the same pipeline as body rows: (1) cell-value rules including sentinels: `<empty>` → empty string value (`x=` in query, header with empty value), `<null>` → ERROR `NULL_NOT_ALLOWED_HERE` for query/header/path (null has no representation; document it), never the literal text; (2) resolve declared parameter from `SpecIndex` (merged path+op params) when a spec exists; check `in` matches the requested location; (3) `TypeCoercer` with the parameter schema (integer/boolean/array style simple); (4) `ConstraintValidator` on the parameter schema (M5 tail); (5) encoding: query names/values `URLEncoder`-style percent encoding with `%20` for space, leaving `{{var}}` untouched, preserving other params' raw order and raw encoding; path segment values encoded with a path-segment encoder (RFC 3986 pchar; `/` encoded as `%2F`), placeholders untouched; (6) header value validated for CR/LF (reject `\r`/`\n` → ERROR `HEADER_INVALID_VALUE`, prevents header injection); header name case-insensitive match, original casing kept; (7) ADD of a missing query/header/path param only if spec-declared (with `in` correct); undeclared → NOT_IN_SPEC.
- Tests (`RequestPartOverriderTest`): `h4_emptySentinel_query_writesEmptyValue` (not `%3Cempty%3E`), `h4_nullSentinelHeader_isError`, `h4_queryValueEncoded` (`a b&c=d` → `a%20b%26c%3Dd`), `h4_pathValueEncoded` (`a/b` → `a%2Fb`), `h4_headerCrlf_rejected`, `h4_integerQueryParamWithText_isTypeUncoercible`, `h4_queryOrderAndOthersPreserved`, `h4_placeholderQueryValueUntouched`.
- Acceptance: all four location types go through identical coercion/validation; the review's `x=%3Cempty%3E` and literal-header-`<null>` outputs cannot occur.

### H5 — Body handling breaks non-body sheets
- Root cause: `payload/JsonTemplateCodec.java:78-79` returns/writes `{ }` for empty body, rows for GET therefore add `{}`; `ParameterOverrideService.java:165-176` aborts the entire sheet when the body is not JSON; BODY_NOT_APPLICABLE never raised; array root rejected at `JsonTemplateCodec.java:86-88`.
- Change:
  - Codec: `parse(String)` returns `ParsedBody{state: ABSENT | JSON(node) | NOT_JSON(reason)}`. Empty/blank body → ABSENT (not `{}`). `serialize` of ABSENT returns the original (null/empty) string unchanged. Support any JSON root (object or array); `$[0].x` paths valid for array roots. Keep placeholder masking, exact decimals, duplicate key detection (duplicate key → body state NOT_JSON with reason DUPLICATE_KEYS, which only fails BODY rows).
  - Service: parse the body once per sheet lazily; body rows only consult it. State handling per row: location != BODY → body never touched or parsed. BODY row and state NOT_JSON → ERROR `BODY_NOT_JSON` for that row only. BODY row and ABSENT: if the operation's spec has a JSON `requestBody` → start from `{}` (or `[]` if root schema type array) and proceed; else ERROR `BODY_NOT_APPLICABLE` (this is the GET case); no spec and ABSENT → ERROR `BODY_NOT_APPLICABLE` for MODIFY (nothing to modify) per plan (gap-fill requires spec: SPEC_UNAVAILABLE for the explicit-add case; choose BODY_NOT_APPLICABLE when method is GET/HEAD/DELETE-without-body, else SPEC_UNAVAILABLE).
  - Commit step (D1): only write body back if at least one body row produced MODIFIED/ADDED, so a GET sheet with only query rows leaves `bodyTemplate` exactly as it was (null stays null).
- Tests: `h5_getQuerySheet_bodyStaysNull` (GET /customers sheet with a query row; assert `bodyTemplate == null` before/after), `h5_nonJsonBody_queryRowStillApplied_bodyRowErrors` (form body + one query row + one body row), `h5_bodyRowOnGet_isBodyNotApplicable`, `h5_arrayRootedBody_modifyIndexZero`, `h5_emptyBodyWithSpecRequestBody_startsFromObject`.
- Acceptance: no TestCase gains a body it did not have unless a body row was ADDED with a spec pointer.

### H6 — Failed row leaves partial changes
- Root cause: `payload/PayloadOverrider.java:84-95, 109-118` creates intermediates in the live tree before the final set/validation, and a later failure doesn't undo them (`{"a":{"b":[]}}` persisted).
- Change: D1. Concretely, `PayloadOverrider.apply(root, path, value, ...)` deep-copies `root`, mutates the copy, validates, and returns `OverrideOutcome` carrying the new root on success or `null` root on failure. Intermediates are created on the copy only. Remove in-place helper methods or make them private to the copy flow.
- Tests: `h6_failedRowLeavesBodyUnchanged` (body `{}`; row `$.a.b[3]` with value, IndexOutOfRange; assert body still `{}`), `h6_typeUncoercibleAfterIntermediatesCreated` (row creating `a.b.c` where `c` integer, value `abc`; assert body byte-equal to baseline, assert via invariance helper from WP8), `h6_failedRowDoesNotAffectLaterRows` (row1 fails, row2 succeeds; only row2's pointer changed).
- Acceptance: for every ERROR/SKIPPED entry, the invariance helper proves the working tree identical to its pre-row state.

### M1 — Sheet-to-API matching
- Root cause: `ParameterOverrideService.java:507-523` uses `url.contains()` and no operationId matching.
- Change: `TargetMatcher`: parse `METHOD /path-template` target (method case-insensitive; one space; path begins `/`). For each TestCase: compare method; derive the TestCase's path via `UrlParts` (strip scheme/host/`{{baseUrl}}`/query/fragment, strip server/base path with segment-boundary check), then match segments against the target template: literal = literal (case-sensitive), template `{x}` matches any one segment including `{{var}}` and `:id`. Exact segment count equality. Zero matches → UNMATCHED; one → matched `METHOD_PATH`; more than one → AMBIGUOUS_TARGET with candidate names. operationId: match `TestCase.operationId` first, then resolve each TestCase's operation through `SpecIndex` (when a spec is attached) and compare ids; record `matchedBy=OPERATION_ID`; on first successful resolution auto-fill `TestCase.operationId` (only on apply). The exact name lookup stays first. No fuzzy matching.
- Tests (`ParameterOverrideServiceTest` / `TargetMatcherTest`): `m1_getCustomersById_matchesTemplateTarget` (`GET /customers/{id}` ↔ `{{baseUrl}}/customers/123`), `m1_getCustomers_doesNotMatchCustomersById` (not AMBIGUOUS), `m1_postAndGetSamePath_separatedByMethod`, `m1_operationIdTarget_viaSpec`, `m1_twoTestCasesSameTemplate_isAmbiguousTarget_withCandidates`, `m1_queryStringIgnoredInMatching`.
- Acceptance: both review examples resolve correctly.

### M2 (WP1 part) — operation lookup
- `SpecIndex.java:211-214`: strip any scheme+host (not only `{{baseUrl}}`), any `{{var}}` host prefix, and the query/fragment. `SpecIndex.java:225`: base-path stripping requires a segment boundary (`/v1` strips from `/v1/x` but not `/v10/x`). `ParameterOverrideService.java:187`: let `AMBIGUOUS_OPERATION` propagate as its own reason (with candidate `operationId`s/templates), not SPEC_UNAVAILABLE. Tests: `m2_hostAndBasePathStripped`, `m2_basePathSegmentBoundary`, `m2_ambiguousOperationReported`. (Remaining M2 items live in WP2/WP6.)

### M10 — ParamPath parsing
- Root cause: `payload/ParamPath.java:117-144` lax index parse; `PayloadOverrider.java:140` splits on `.`/`[` so quoted keys with those characters break.
- Change: rewrite as a small state-machine tokenizer producing `List<Segment{key|index}>`: accepted forms `$`, `$.a.b`, `a.b`, `a[0].b`, `$['odd.key']`, `$["k"]`, unicode keys; indices non-negative base-10 digits only (reject `[-1]`, `[01]`? accept `[0]`, reject leading `+`/`-`/spaces/empty `[]`/`[abc]`), reject `*`, `..`, `?(`, unterminated brackets/quotes, empty segments (`a..b` is recursive descent = PATH_UNSUPPORTED; `a.` = PATH_INVALID). `PayloadOverrider` walks `List<Segment>` and never re-parses a string.
- Tests (`ParamPathTest`): table-driven valid and invalid cases including `[-1]`, `$['a.b'].c`, `$['x[0]']`, `a[0][1]`, `$..a`, `a.*`, `$[?(@.x)]`, `''`.
- Acceptance: invalid paths ERROR with PATH_INVALID/PATH_UNSUPPORTED; keys with dots work via bracket notation.

### M11 — Error reporting
- Change `ParameterOverrideService.java:270-275`: catch `OverrideException` first and map to its ReasonCode and candidates; the final catch-all produces INTERNAL_ERROR with exception class+message and logs the stack trace once. Audit all `throw new IllegalArgumentException/RuntimeException` in `payload/` and `spec/` and convert to `OverrideException` with codes.
- Test: `m11_specificReasonCodePreserved` (parameterised over several failures; assert no OVERRIDE_FAILED remains; grep test asserting string `OVERRIDE_FAILED` absent from main sources).

### Low items
- Workbook leak (`ExcelParameterImporter.java:61, 86`): try-with-resources around the `XSSFWorkbook` and the input stream; test with a corrupt workbook that throws midway and assert the file handle is released (open/delete the temp file on Windows semantics where possible; at least assert no exception from a second import).
- Delete path (`PayloadOverrider.removeOverride`, line 155): the plan forbids deletions. Remove the method; remove or rewrite its test as `nothingIsEverDeleted` using the invariance helper (assert the multiset of pointers after is a superset of before for every scenario). If revert-to-baseline needs a restore operation, it is done by replacing the whole request from `baseline` in `TestCase`/service, not by deleting nodes.

WP1 exit: all H1–H6, M1, M10, M11 tests green, review's probe programs (converted) green for these IDs, full suite green.

---

## 5. Phase WP2 — Spec loader hardening (S1, M2 remainder, M8 core)

### S1 — `$ref` following (Medium)
- Root cause: `spec/ProviderSpecLoader.java:88-94` calls swagger-parser with resolve/resolveFully and no restriction; remote and arbitrary local `$ref` are fetched/read.
- Change: implement D4 as `spec/RefGuard`. Steps: (1) read spec bytes (size cap 10 MB), parse with Jackson (JSON/YAML mapper; YAML via snakeyaml with default alias limits kept); (2) walk the tree, gather `$ref` values; (3) classify each: `#...` ok; scheme present (`http`, `https`, `file`, `jar`, `ftp`, anything with `:` before first `/` except Windows drive letters which are rejected too) → block; relative → resolve against spec dir, `toRealPath()` (if file missing, record `REF_UNRESOLVED`), require `startsWith(realSpecDir)`, extension allowlist, then recurse into that file with the same guard (visited set to avoid cycles; caps from D4); (4) any blocked ref → `ProviderSpecLoader` returns a `SpecLoadResult` with `specMessages` entries and `openApi == null` (fail closed); (5) only if the guard passes call `OpenAPIV3Parser`/`SwaggerParser` with `ParseOptions.setResolve(true)`, `setResolveFully(true)`, `setResolveCombinators(false)`. Note the review's suggested `setResolve(false)`; we choose pre-check + keep resolve because `SchemaNavigator` depends on resolved relative-file refs (document in code comment). If the parser also exposes a remote-allowlist option in 2.1.48, set it to deny as defence in depth (verify option name; plan §1 says confirm when wiring). For Swagger 2.0 inputs the guard runs on the raw 2.0 tree before conversion.
- Also: spec pre-check must run in `--check-spec` (Main) and `ParameterOverrideService`.
- Tests (`SpecLoaderSecurityTest`, start a `com.sun.net.httpserver.HttpServer` on loopback): `s1_remoteRef_notFetched` (server counter stays 0; load result has REMOTE_REF_BLOCKED), `s1_relativeRefOutsideSpecDir_blocked` (ref `../../secret.yaml` with a secret marker file; marker never in model; message REF_OUTSIDE_DIR), `s1_symlinkOutsideDir_blocked` (skip on Windows, `Assumptions.assumeTrue(supportsSymlinks)`), `s1_relativeRefInsideDir_works` (split-file spec), `s1_fileSchemeRef_blocked`, `s1_cycleAcrossFiles_terminates`.
- Acceptance: PoC from the review produces zero outbound requests and no local file content; legitimate split specs still load.
- Live verification still required (section 7.3): `169.254.169.254` ref, symlink behaviour in swagger-parser if the guard were bypassed.

### M2 remainder / M8 — specPath
- `ui/MainFrame.java:260`: store `specPath` relative to the suite file directory when the spec is under or beside it (`suiteDir.relativize`), else store absolute and warn once. `ParameterOverrideService.java:68`: resolve against `suitePath.getParent()`, not the working directory; add `TestSuite` runtime field `transient Path suiteFile`. Missing/unreadable spec → `specMessages` entry `SPEC_FILE_MISSING` shown above the entries table and in the dialog (not silent), and gap-fill rows get SPEC_UNAVAILABLE as plan requires while MODIFY rows still apply.
- Tests: `m8_relativeSpecPath_resolvesAgainstSuiteDir_notCwd` (change `user.dir`-independent by using absolute temp dirs and a suite file elsewhere), `m8_missingSpec_reportedInSpecMessages`, `m8_savedPathIsRelative` (SuiteRepository round trip).

WP2 exit: S1 tests green; sample specs still pass `--check-spec` from the fat jar.

---

## 6. Phase WP3 — Secrets and report hardening (S2, S6)

### S2 — Plaintext secrets
- Root cause: `runner/TokenProviderResolver.java:78-79` retains body/headers/token on its result; reports serialise them; resolved URLs with `?api_key=` flow into reports; `store/SuiteRepository.java` and `importer/PostmanEnvironmentImporter.java` store secrets plaintext.
- Change (D5):
  1. New `util/Redactor` + unit tests.
  2. `TokenProviderResolver`: drop retained response body/headers; token field `@JsonIgnore transient`; `TestResult` gains no secret fields; the runtime token map stays in-memory only (`SuiteRunner` context).
  3. `ReportWriter` (JSON, HTML, override JSON/HTML): pass every URL through `Redactor.redactUrl`, headers through `redactHeaders`, request/response bodies through `redactBody` for known token JSON paths (the suite's `tokenJsonPath` values) and common names (`access_token`, `refresh_token`, `id_token`, `client_secret`, `password`). `OverrideEntry.rawValue/oldValue/coercedValue` for rows whose parameter name matches the secret deny list → redacted in the written report (in-memory copy intact for the preview panel, which also masks with a toggle).
  4. `SuiteRepository.save`: write to a temp file then atomic move; set POSIX `rw-------` (try/catch `UnsupportedOperationException` on Windows: apply owner-only ACL best effort, otherwise log once). Report writers do the same.
  5. `PostmanEnvironmentImporter`: variables with `type == "secret"` or key matching the secret heuristic are stored as `{{env:KEY}}`-style references (D5) with resolution in `Substitutor` from `System.getenv("APIREG_SECRET_" + KEY)` or a gitignored `secrets.local.json` beside the suite; `Auth` secret fields (bearer token, basic password, API key value) likewise accept a reference and the editor offers "store as reference". Default for Phase 1 if the user declines: keep plaintext, show a one-time warning, and rely on 0600.
- Tests (`ReportRedactionTest`, `RedactorTest`, `SuiteRepositoryPermissionTest`): `s2_reportDoesNotContainToken` (run a mock Authman, then grep the JSON and HTML reports bytes for the token and for the client secret: absent), `s2_urlApiKeyRedacted` (`?api_key=SECRET&x=1` → `api_key=***REDACTED***&x=1`), `s2_authorizationHeaderRedacted`, `s2_tokenResultNotSerialised` (Jackson-serialise `TokenProviderResolver` result: no token substring), `s2_suiteFileMode0600` (POSIX only, assume), `s2_postmanSecretBecomesReference` (env JSON with secret type → suite JSON contains reference not value), `s2_substitutorResolvesEnvReference`.
- Acceptance: `grep` of every generated report/suite for a known canary secret finds nothing; run in CI as a canary test.

### S6 — HTML report hardening
- `ReportWriter.java:~118`: HTML-escape all dynamic values via one `esc()` helper, including enum names, counts, notes, sheet names, and attribute values; add `<meta http-equiv="Content-Security-Policy" content="default-src 'none'; style-src 'unsafe-inline'; img-src data:">` (inline styles only, no scripts); `<meta charset="utf-8">`.
- Tests (`ReportWriterTest`): `s6_htmlEscapesAllFields` (sheet name `<script>alert(1)</script>`, notes `"><img onerror>`, parameter name with `&`; assert escaped output and absence of raw `<script`), `s6_cspMetaPresent`.

WP3 exit: S2/S6 tests green; canary-secret grep test green.

---

## 7. Phase WP4 — HTTP and validation hardening (S3, S4, S5, M5)

### S3 — Redirect header leak
- Root cause: `client/HttpRunner.java:82` `HttpClient.Redirect.NORMAL` forwards custom headers (`X-Api-Key`) on cross-host redirects.
- Change: use `Redirect.NEVER` and a small manual follower: up to 5 hops; on each hop compute origin (scheme, host, port); if the origin changes, drop all headers in the Redactor deny set plus any header in `TestCase.auth` config and any header whose name the suite marks sensitive; downgrade https→http refused (stop and report); 301/302/303 on POST → GET with no body per RFC behaviour (keep consistent with prior behaviour: record in the result `redirectChain` sans secrets); 307/308 preserve method and body only for same-origin. Make the policy a suite option `followRedirects` (default true, with the above safe behaviour) and `redirectsCrossOriginAllowed` default false (cross-origin redirects are surfaced as the response, not followed; decide per Q5).
- Tests (`HttpRunnerRedirectTest`, two local `HttpServer`s on different ports = different origins): `s3_crossOriginRedirect_dropsApiKeyHeader` (server B records headers; `X-Api-Key` absent, also `Authorization` absent), `s3_sameOriginRedirect_keepsHeaders`, `s3_redirectLoop_capped`, `s3_httpsToHttpDowngrade_refused` (unit-level with URI check), `s3_postRedirect303_becomesGet`.
- Acceptance: PoC server never receives secret headers.

### S4 — Regex DoS
- Root cause: `spec/ConstraintValidator.java:137-142` runs spec `pattern` on user cell values with `matches()`, unbounded.
- Change: D6 `SafeRegex`; replace `matches()` with `find()` (M5 overlap); run validation inside the service worker thread, not EDT; add timing budget per sheet (e.g. total regex time cap 5 s, then remaining pattern checks skipped with warning).
- Tests (`SafeRegexTest`, `ConstraintValidatorTest`): `s4_catastrophicPattern_returnsWithin500ms` (`^(.*a){20}$` vs 35 `a`+`b` chars; assert elapsed < 1 s using `assertTimeoutPreemptively` as a backstop and assert result is the skipped warning), `s4_overlongInput_skippedWithWarning`, `s4_overlongPattern_skipped`, `s4_validPatternStillEnforced`, `s4_patternUnanchoredFindSemantics` (see M5), `s4_strictPolicyTimeoutIsWarningNotError`.
- Acceptance: PoC completes in < 1 s; UI test (manual) confirms the dialog stays responsive.

### S5 — Zip bomb
- Root cause: only 20 MB compressed-size check guards `ExcelParameterImporter.java:20, 44, 61`.
- Change: before opening, set `ZipSecureFile.setMinInflateRatio(0.01)`, `ZipSecureFile.setMaxEntrySize(100_000_000L)`, `ZipSecureFile.setMaxTextSize(10_000_000L)` (verify exact names against POI 5.5.1), `IOUtils.setByteArrayMaxOverride(50_000_000)`; map `IOException`/`InvalidFormatException`/`RecordFormatException` thrown for these into a clean sheet-level or workbook-level ERROR `WORKBOOK_UNSUPPORTED` ("workbook rejected: too large when decompressed"), never a stack trace to the UI. Also cap sheet count (200) and column count read per row (256).
- Tests: `s5_highRatioWorkbook_rejectedCleanly` (build in `@TempDir` an xlsx whose sheet XML is gigabytes-equivalent by writing a repetitive 60 MB-inflated entry of few KB compressed; keep the real file small), `s5_normalWorkbookStillLoads`. Mark live heap-exhaustion verification manual (7.3).

### M5 — Constraint checks
- `spec/ConstraintValidator.java:77, 90, 141`: enum comparison is exact (case-sensitive, compare typed JSON values: string "A" ≠ "a", number 1 equals 1.0 by `BigDecimal.compareTo`); `pattern` via `find()`; nullable semantics: OAS 3.0 requires `nullable: true`, 3.1 requires `"null"` in `type` array or in a `oneOf/anyOf` branch with null, default for missing = NOT nullable (violation under `<null>`); `format: date`, `date-time`, `uuid`, `email`, `int32/int64` range checks; `minLength/maxLength` in code points; `minimum/maximum` with exclusive handling for both 3.0 (boolean) and 3.1 (numeric) styles using `BigDecimal`. Apply to non-body params through the H4 pipeline using the parameter's schema.
- Tests: `m5_enumCaseSensitive`, `m5_patternUnanchored_matchesSubstring`, `m5_missingNullable_nullIsViolation_warnOrStrictError`, `m5_oas31NullInTypeArray_allowsNull`, `m5_queryParamEnumChecked`, `m5_headerParamLengthChecked`, `m5_exclusiveMinimum30And31`, `m5_int32Overflow`.

WP4 exit: all above green; S3/S4 PoCs neutralised.

---

## 8. Phase WP5 — Jackson / slf4j alignment (M7)

- `pom.xml:20`: remove the direct `jackson.version` 2.18.2 pin; add `com.fasterxml.jackson:jackson-bom` import in `dependencyManagement` at the version swagger-core 2.2.53 expects (review/plan state 2.22.1; first run `mvn dependency:tree -Dincludes=com.fasterxml.jackson*` and take the highest version in the swagger tree). Verify 2.22.x exists on Central at bump time (the plan says 2.22.1; confirm and record the actual version in the PR).
- Align slf4j: pick one `slf4j.version` (2.0.16 to match slf4j-simple) in properties and manage both `slf4j-api` and `slf4j-simple` with it.
- Steps: (1) `mvn -q dependency:tree` before/after saved; (2) `mvn dependency:analyze` for convergence issues; optionally add `maven-enforcer-plugin` `dependencyConvergence` + `requireUpperBoundDeps` rules so skew cannot return; (3) run `mvn test`; (4) rebuild the fat jar and run the smoke tests: `java -jar target/api-regression.jar --check-spec examples/sample_openapi.json`, same for `sample_swagger2.json` (proves ServicesResourceTransformer), headless `--run` on a sample suite, `--check-spec` for the OAS 3.1 fixture, import `examples/sample_params.xlsx` through the headless service, confirm no stderr "no Log4j provider" / `NoSuchMethodError` / `SLF4J: Class path contains multiple` warnings; (5) grep the jar: `unzip -l` shows a single version of each jackson module (`jackson-*-2.x.jar` classes only once; check `META-INF/MANIFEST.MF` `Multi-Release: true`, `module-info.class` excluded).
- OWASP: `mvn org.owasp:dependency-check-maven:check` (requires NVD API key and first-run download; if offline-restricted, run from a networked machine and attach the HTML report). Record results; any CVSS ≥ 7 dependency without an accepted suppression blocks sign-off. Decide whether to wire it as a non-default profile (`-Psecurity-scan`).
- Acceptance: `mvn clean package` green, jar smoke tests green, one Jackson version in the dependency tree, dependency-check report archived.

---

## 9. Phase WP6 — Missing 7c features and remaining spec/coercion findings (M3, M4)

### M3 — Spec walking
- `spec/SchemaNavigator.java:150, 170` lose array items and types on OAS 3.1 (3.1 schemas may have `type` as an array, `items` via `prefixItems` or `$defs`, `const`, `examples`). Change: normalise a `Schema` into an internal `SchemaView` that reads `getTypes()` (3.1) falling back to `getType()` (3.0), derives `nullable` from either form, and reads `getItems()` for arrays in both versions. Add a `SchemaView.isArray()/isObject()` used everywhere instead of direct `getType()` comparisons. Cover `$ref` siblings in 3.1.
- oneOf/anyOf (plan §3a.4 step 3c, 7c missing): `SchemaNavigator.candidates()` walks each branch as a labelled alternative; resolution rule: discriminator property present and its value already in the payload → matching branch (via `discriminator.mapping` or schema name); else property exists in exactly one branch, or exists with the same type in all → use; else ERROR AMBIGUOUS_POLYMORPHIC with the candidate branch names. Cycle guard via visited set of schema identities and depth cap 10 retained.
- `additionalProperties` (7c missing): `SchemaView.additionalProperties()` tri-state {ABSENT_DEFAULT_TRUE_BUT_UNDECLARED, TRUE, FALSE, SCHEMA}. Per plan: ADD under an undeclared key only if the parent explicitly sets true or a schema AND the user gave an explicit full path; warning UNDECLARED; typing from the additionalProperties schema when present. `false` or absent → NOT_IN_SPEC (H1 behaviour retained).
- NEEDS_INDEX: if the bare-name walk passes through an array and that array is present in payload without the index → ERROR NEEDS_INDEX with a suggested `a[0].b` candidate; if the array is absent → create it with element `[0]` (plan). Implemented in `GapFillService` portion of the service.
- Bare name that is a query param goes to BODY when there is no spec (`ParameterOverrideService.java:439-467`): in step 1 the bare-name lookup must search payload body leaf keys and the URL query/header names; with no spec, a hit in the query string → QUERY MODIFY; zero hits and no spec → SPEC_UNAVAILABLE (not BODY ADD). Fix ordering bug; never default a bare name to BODY.
- New reason codes to implement and test: did-you-mean (NOT_IN_SPEC candidates; implemented in H1), REQUIRED_SIBLINGS_MISSING (warning when a newly created intermediate object has declared `required` children not set by any row on this sheet; list them), AMBIGUOUS_LOCATION (bare name found in both operation parameters and body schema), OPERATION_NOT_FOUND (spec present, no operation matches → gap-fill rows get this, MODIFY rows still apply).
- Tests (`GapFillTest`, `SpecIndexTest`, run for oas3, swagger2, oas31 via `@ParameterizedTest`): `m3_oas31_arrayItemsAndTypesPreserved`, `m3_oneOf_discriminatorPicksBranch`, `m3_oneOf_ambiguousProperty_isAmbiguousPolymorphic`, `m3_oneOf_propertyInOneBranch_used`, `m3_needsIndex_whenArrayPresent`, `m3_arrayAbsent_createdWithElementZero`, `m3_noSpec_bareQueryName_modifiesQuery_notBody`, `m3_additionalPropertiesTrue_explicitPath_addedWithWarning`, `m3_additionalPropertiesAbsent_isNotInSpec`, `m3_requiredSiblingsMissing_warning`, `m3_ambiguousLocation_listsBoth`, `m3_operationNotFound_modifyStillApplied`, `m3_recursiveSchema_terminates`, `m3_ambiguousLeaf_listsAllCandidatePaths` (`city`), `m3_readOnlyAdd_isError`, `m3_readOnlyModify_warnOrStrictError`, `m3_specMissing_modificationsStillApplied`.
- Acceptance: all 7c items in feedback §4 implemented and listed with passing tests; Swagger 2.0, 3.0, 3.1 fixtures give identical answers (`SpecIndexTest` parameterised assertion).

### M4 — Type coercion
- `payload/TypeCoercer.java:58-60`: a value that is exactly one `{{var}}` placeholder into a numeric/boolean target is written unquoted: represent as a `RawPlaceholderNode` (a `TextNode` subclass with a marker or via the codec's mask sentinel table) so serialisation emits `{{var}}` unquoted. Existing unquoted placeholders in the source body (masked sentinels) must be classified "placeholder of unknown type" not "string", so type-mismatch logic does not mis-flag or wrongly coerce them. `TypeCoercer.java:95-100`: spec type object/array triggers JSON parse (same as `type=json`), invalid JSON → ERROR INVALID_JSON. `format: date` target → `yyyy-MM-dd`; `date-time` → `yyyy-MM-dd'T'HH:mm:ss` (no zone, per D13 in the plan table); date cell with spec format absent → datetime per plan; only emit date-only when target format is `date` or `type` column says `date`.
- Tests (`TypeCoercerTest`, plus codec round trip): `m4_placeholderIntoInteger_unquoted` (`"amount": {{amt}}` after override of another field is intact, and an override `{{amt}}` into integer field serialises unquoted and the result re-masks cleanly), `m4_existingUnquotedPlaceholder_notTreatedAsString`, `m4_specObjectTypeParsesJson`, `m4_specArrayTypeParsesJson`, `m4_formatDate_dateOnlyOutput`, `m4_formatDateTime_noZone`, `m4_leadingZerosDroppedWarning_intoInteger`, `m4_typeColumnOverrides_withTypeChangedWarning`.

WP6 exit: all tests green on all three spec fixtures; `--check-spec` on all three from the fat jar.

---

## 10. Phase WP7 — Importer reporting, 7d UI/reporting, Step 8, README (M6, M8 UI, M9, 7d gaps)

### M6 — Importer reporting (`importer/ExcelParameterImporter.java`)
- Hidden sheets (line 77): do not drop; emit a `SheetParams` with `status=SKIPPED`, reason HIDDEN_SHEET (very hidden always skipped, `skipHiddenSheets=false` imports hidden, never very hidden).
- Case-insensitive duplicate parameters (line 241): the plan says "same `parameter` twice" last wins, earlier rows SKIPPED (DUPLICATE) with a warning naming the winner. Key comparison on the normalised parameter string (trimmed? plan says no trim on values; for the parameter column normalise trim + case-fold only for duplicate detection, keep the original as written). The skipped rows must appear in the report, not vanish.
- Row cap (line 166): if rows exceed `maxRowsPerSheet`, the whole sheet is NOT_APPLIED (reason ROW_CAP_EXCEEDED), zero rows processed, not truncated.
- Encrypted workbooks: detect `OLE2` signature / `EncryptionInfo` stream (POI throws `EncryptedDocumentException`) → workbook-level ERROR WORKBOOK_ENCRYPTED with message "save an unprotected copy". `.xls`/`.csv` extension or content sniff (OLE2 without OOXML, text) → WORKBOOK_UNSUPPORTED "Save As .xlsx". Read via `InputStream` not `File` (plan §3a.7; verify, and test).
- Column contract: accept only `parameter,value,type,location,enabled,notes` (case-insensitive). The undocumented `op/path/param` columns: reject with a clear header error naming the expected columns; migrate the tests to the contract headers via `XlsxFixtureBuilder`. If any user already relies on them (Q4), accept them behind a deprecation warning for one release instead.
- Tests (`ExcelParameterImporterTest`, the plan's ~18 cases): blank vs `<null>` vs `<empty>`; integral numeric strips `.0`; ≥1e15 ERROR; date cell (date and datetime); boolean cell; formula cached value and cached error and no-cache; text leading zeros kept; `A1=target` plus metadata rows; header in any column order; unknown column warning; `enabled=N`; duplicate rows last-wins plus warning (including case-differing duplicates); hidden sheet appears in report as skipped; very hidden skipped; merged cells (value top-left only, warning); row cap marks NOT_APPLIED; `.xls` rejected; encrypted rejected with message; `op/path/param` headers rejected; leading/trailing whitespace warning; hidden/filtered row processed with warning; workbook closed on exception.

### M9 — Preview vs apply
- `ui/ParameterImportDialog.java:168`: the dry-run `OverrideReport` records `workbookSha256`, `specSha256`, and a `suiteFingerprint` (hash of each target TestCase's baseline request). Apply takes the dry-run report and verifies the workbook and spec sha256 still match (re-hash the files); mismatch → refuse with "files changed since preview, re-run dry run" (or auto re-run and re-show). Apply should use the bytes read at preview time where feasible: cache the workbook bytes in the dialog and pass an `InputStream`/byte[] to `apply`, so there is no TOCTOU.
- Line 178: the success dialog shows the counts of the apply report (the `OverrideReport` returned by `apply`), not the dry-run counts; include applied vs skipped-by-policy numbers.
- Tests: `m9_workbookChangedBetweenPreviewAndApply_isRejected` (service-level with hash arg), `m9_successCountsComeFromApplyReport` (headless: apply with errors blocked vs allowed, counts differ from dry run when `applyWithErrors=false` and sheets are not applied; assert the dialog's message-producing helper, extracted into a testable `ApplyResultFormatter`).

### 7d gaps
- `ReportWriter.writeOverrideJson/Html`: header fields workbook name + sha256, spec path + version + sha256, policy, dry-run vs applied, timestamp (`createdAt` UTC ISO-8601); per-sheet counts; entries sorted errors first (ERROR, then SKIPPED, then ADDED, MODIFIED; stable by sheet then row); `OverrideReport.specVersion` filled from the loader (`openapi: 3.0.3`, `swagger: 2.0`, `3.1.0`); `specMessages` shown above entries. `OverridePreviewPanel` uses the same sort.
- "Manual edits since last import" warning: after every apply compute and store `lastOverride.producedFingerprint` = SHA-256 of `{url, sorted headers, bodyTemplate}` as produced. At the next dry run, if the TestCase's current request fingerprint differs from `producedFingerprint`, add warning `MANUAL_EDITS_DISCARDED` to the sheet summary and report ("manual edits since last import will be discarded"). Revert to baseline and Promote to baseline in `TestCaseEditDialog` (check these exist and work; add if missing) update/clear `lastOverride` correctly.
- PARTIAL OVERRIDE badge: verify it renders when `lastOverride.errors > 0`; test via a lightweight model-level predicate `RunResultRow.isPartialOverride(TestCase)`.
- Tests (`ReportWriterTest`): `writeOverrideJson_hasHeaderAndPerSheetCounts` (sha256s, specVersion, policy, dryRun, createdAt non-null), `writeOverrideHtml_summaryTableAndErrorRowsFirst`, `manualEditsWarning_whenRequestChangedAfterApply`, `noWarning_whenRequestUnchanged`, `promoteBaseline_clearsWarning`.

### Step 8 (plan polish gaps from §1 summary)
- Config: request timeout, retry count (default 0), sequential vs thread pool size, exposed on `TestSuite.runOptions` and in headless `--run` flags. `HttpRunner` uses `HttpRequest.timeout` + client `connectTimeout`; retries only for idempotent methods on IOException/timeouts (and 502/503/504 optional), exponential backoff capped. Tests with a delayed mock server: `step8_timeoutHonoured`, `step8_retryOnlyIdempotent`, `step8_poolRunsInParallel_resultOrderPreserved`.
- Unresolved `{{var}}` pre-run validation, ignore-path heuristic button: confirm present; add tests if missing.

### README Definition of Done (correct until true)
- Rewrite the README DoD section line by line against section 12 below. Remove claims that are not yet true; re-add them only once the matching test exists and passes (invariance, idempotency, conflict handling, `--check-spec` from the jar). Add a "Known limitations" section (xlsx only, no remote specs, secrets handling mode, encrypted workbook unsupported). Add the security notes (redaction, `$ref` policy, redirect policy).

WP7 exit: all tests green; manual GUI walk-through checklist (section 12.4) done.

---

## 11. Phase WP8 — Test strategy build-out and sign-off

### 11.1 Cross-cutting test helpers (`testsupport/`)
- `XlsxFixtureBuilder` (WP0), `SpecFixtures` (loads from `src/test/resources/spec/*` via `@ParameterizedTest @MethodSource` supplying {oas3, swagger2, oas31}), `SuiteBuilder` (fluent TestCase/TestSuite with baseline), `JsonPointerDiff.assertUntouchedExcept(before, after, Set<JsonPointer> allowedChanged)`: walks both trees, asserts every pointer not in `allowedChanged` (and not a descendant of an allowed pointer or a created-intermediate ancestor) has equal value and equal position/key order.
- `RecordingServer` (JDK `HttpServer` capturing requests/headers) for S3 and token/auth tests.

### 11.2 Untouched-fields invariance (plan: `PayloadOverriderTest`, service-level)
- Property-style: for a corpus of 10+ realistic bodies (nested, arrays, numbers like `10.50`, unicode keys, placeholders unquoted), for every scenario (success, failure, partial success) assert `JsonPointerDiff.assertUntouchedExcept(baseline, result, targetPointers)`. Include `nothingIsEverDeleted` (pointer set after ⊇ before). Run over each H-defect scenario so failed rows prove rollback (H6).
- Structural guarantee only (plan): comparison is JSON-tree equality with key order, not bytes.

### 11.3 Byte-identical idempotency
- `ParameterOverrideServiceTest.roundTripIdempotency`: apply workbook W to suite S → serialise (url, headers map as ordered list, bodyTemplate strings); apply W again → assert string equality of each field (byte-identical), and the only difference in suite JSON diff is `lastOverride.appliedAt`. Also `changedWorkbookReplacesPriorOverrides` (W1 then W2: result equals applying W2 straight to the baseline), `applyThenSaveLoadApply_identical` (SuiteRepository round trip in the middle), `dryRunNeverMutatesSuite` (deep equals of a serialised snapshot before/after), `applyBlockedOnErrorsUnlessApplyWithErrors`.
- Idempotency of first-time formatting churn: first apply may reformat whitespace (plan); the second must not. Assert via the above.

### 11.4 Fixtures to add
- Specs: three customers fixtures (WP0) plus hostile ones.
- OAS 3.1 specifics: `type: [string, "null"]`, array `items`, `const`, `$defs`, `exclusiveMinimum` numeric.
- Customers workbook generator: `XlsxFixtureBuilder.customersWorkbook(Path)` producing one sheet modifying existing, one adding nested + query param, one NOT_IN_SPEC row, one ambiguous `city`, one `<null>` row, one conflict pair; used by service tests and to generate `examples/sample_params.xlsx`.

### 11.5 Test table delivery matrix (plan test table vs current)
| Class | Planned | Current | Add |
|---|---|---|---|
| `ExcelParameterImporterTest` | ~18 | 1 | the M6/H3 list above (≥ 20 cases) |
| `JsonTemplateCodecTest` | 4 | check | unquoted placeholder round trip, key order, `10.50`, duplicate keys, plus ABSENT/array-root cases (H5), placeholder as number (M4) |
| `PayloadOverriderTest` | 8 | check | invariance, array modify/append/out-of-range, type change with/without `type`, JSON literal, nothing deleted, rollback (H6) |
| `RequestPartOverriderTest` | 4 | check | H4 list |
| `ParamPathTest` | n/a | none | M10 table |
| `SpecIndexTest` | 7 | check | three-version parity, ambiguity, basePath, recursion |
| `GapFillTest` | 9 | 3 | M3 list |
| `ParameterOverrideServiceTest` | 6 | 1 | H1/H2/H5/M1/M9/idempotency |
| `ReportWriterTest` | 2 | check | 7d + S6 + S2 canary |
| `SecurityRegressionTest` | n/a | none | S1, S3, S4, S5 PoC conversions |
| `HttpRunnerRedirectTest` | n/a | none | S3 |

### 11.6 Process rules
- Every merged PR links its defect id and names the test(s) that fail without the change.
- CI gate (local script is fine): `mvn -q clean verify` + fat-jar smoke script `scripts/smoke.sh` (see 12.2) must pass.
- Mutation sanity check for the High fixes: temporarily revert each H fix and confirm its test goes red (record once per defect in the PR).

---

## 12. Verification checklist / Definition of Done

### 12.1 Automated
- [ ] `mvn -q clean test` green on a clean checkout; test count materially above 37 (target ≥ 150) and the red/green matrix from WP0 is fully green.
- [ ] Every finding id H1–H6, M1–M11, Low x2, S1–S6 maps to at least one named test (traceability table in `fixphase1-traceability` section of the PR description; generate by grepping test names for the id prefix).
- [ ] `JsonPointerDiff` invariance and byte-identical idempotency tests present and green.
- [ ] No `OVERRIDE_FAILED` string and no `HashSet<TestCase>` left in main sources (grep checks in a test or CI script).
- [ ] Canary-secret grep test (S2) green.

### 12.2 Fat jar smoke (`scripts/smoke.sh`, plus manual run once)
- [ ] `mvn clean package` produces one runnable jar.
- [ ] `java -jar target/api-regression.jar --check-spec examples/sample_openapi.json` prints version + operation count.
- [ ] Same for `examples/sample_swagger2.json` (proves ServiceLoader merge) and the OAS 3.1 fixture.
- [ ] A spec containing a remote `$ref` is refused with REMOTE_REF_BLOCKED and no outbound request (check with a local listener).
- [ ] Headless `--run` on `examples/sample_suite.json` works; `ParameterOverrideService` via the sample workbook produces expected counts (script prints and diffs against an expected summary file).
- [ ] No stderr noise: no "no Log4j provider", no `NoSuchMethodError`, no multiple-SLF4J-binding warning.
- [ ] Jar contains one version per Jackson module; `Multi-Release: true`; `module-info.class` excluded.
- [ ] GUI starts (`java -jar`) and opens a blank window (Step 1 regression).

### 12.3 Dependency and security
- [ ] `mvn org.owasp:dependency-check-maven:check` run; report archived; no unaccepted High/Critical.
- [ ] `mvn dependency:tree` shows converged Jackson and slf4j versions.
- [ ] Re-run of the review's security PoCs (scratchpad `poc/` data converted to tests) all neutralised.

### 12.4 Manual / live verification still required (carry over from the review)
- [ ] Fetch of cloud-metadata ref `http://169.254.169.254/...` as a `$ref` is blocked (run on a cloud VM or with a loopback stand-in; confirm via packet capture/listener that no request leaves).
- [ ] Heap exhaustion from a crafted xlsx (zip bomb) does not crash the app: run with `-Xmx256m` on a crafted file.
- [ ] swagger-parser behaviour with `file:` refs and symlinks, to confirm the guard is the boundary and not the parser (test with the guard temporarily disabled to see what the parser would do; expectation documented).
- [ ] GUI walk-through with the user's real provider spec and workbook: attach spec (saved relative), import, dry run, apply, save, reopen (overrides persisted), run suite against the target API, export override JSON+HTML, re-import same workbook (suite JSON diff only `lastOverride.appliedAt`), verify PARTIAL OVERRIDE badge and the manual-edits warning, Revert/Promote baseline.
- [ ] At least one real Postman collection imported and run end-to-end; one real Authman-style endpoint verified (plan DoD items that were never evidenced).
- [ ] UI responsiveness during regex-heavy validation (S4) on a large workbook.

### 12.5 Docs
- [ ] README DoD rewritten to match reality; limitations and security notes added.
- [ ] `phase1plan.md` untouched; deviations (e.g. pre-check+resolve instead of `setResolve(false)`; two-column-name decision for `op/path/param`) recorded in a short "Deviations" section appended to this file or in README.

---

## 13. Open questions (recommended defaults, proceed on defaults unless told otherwise)

| # | Question | Recommended default |
|---|---|---|
| Q1 | Fail the whole spec load when any `$ref` is blocked, or load with the offending refs unresolved? | Fail closed (whole spec unusable, clear message). A half-resolved spec silently misleads gap-fill |
| Q2 | Jackson target version: plan says 2.22.1; verify it exists/what swagger-core 2.2.53 actually brings | Take the version from the swagger tree via `jackson-bom`, record in PR |
| Q3 | Secret storage UX: environment references vs plaintext + 0600 | Phase 1: redact in reports + 0600 + env-reference support for Postman env secrets and Auth; plaintext remains possible with a warning |
| Q4 | Do any users rely on the undocumented `op/path/param` columns? | No: reject with a clear header error and migrate tests to contract headers. If yes, accept for one release with a deprecation warning |
| Q5 | Cross-origin redirects: follow (dropping secrets) or refuse? | Follow up to 5 hops same-origin; cross-origin not followed by default (response surfaced), option to allow with secret stripping |
| Q6 | `<null>` for query/header/path | ERROR NULL_NOT_ALLOWED_HERE (no null representation); `<empty>` = empty value |
| Q7 | Regex budget: 100 ms per match, 4,096 char input cap | Yes; timeouts are warnings in both policies, never ERROR |
| Q8 | Missing spec file on load: error dialog vs report message | `specMessages` + non-blocking banner; MODIFY rows still apply (plan) |
| Q9 | Constraint messages: expose under H4 for non-body params as warnings in WARN mode | Yes, same policy table as body |
| Q10 | Should `TestCase.equals/hashCode` be changed to identity? | No; keep value semantics but ban hash collections of TestCase (grep rule), use identity sets |
| Q11 | Is POI 6.0.0 still too fresh? (plan: revisit after a patch release) | Stay on 5.5.1 for this fix cycle; revisit separately |
| Q12 | Do we need the OWASP dependency-check as a standing profile? | Yes, as `-Psecurity-scan`, not in the default build (needs NVD key and network) |

---

## 14. Suggested PR slicing
1. PR-A: WP0 harness + red tests (tests marked expected-fail or on a branch).
2. PR-B: WP1 (split into B1 transaction model + identity set + H3, B2 H1/H5/H4, B3 M1/M10/M11/Low).
3. PR-C: WP2 (S1/M8). PR-D: WP3 (S2/S6). Parallel with B.
4. PR-E: WP4 (S3/S4/S5/M5).
5. PR-F: WP5 (Jackson/slf4j) alone, so any regression is attributable.
6. PR-G: WP6 (7c + M3/M4). PR-H: WP7 (importer/7d/Step 8/README). PR-I: WP8 sign-off and live verification notes.
