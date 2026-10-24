# specupdate.md - Excel (.xlsx) Provider Specification

Status: PLAN (no code changed). Date: 2026-10-25 (rev 2). Base package: `com.sabyasachi.apiregression` (`src/main/java/com/sabyasachi/apiregression/`).

Requirement: the provider spec may be an Excel workbook of key/value rows describing the provider's request payload, with nesting shown by indentation OR braces/brackets. It feeds the existing gap-fill flow: a parameter in the override workbook that is missing from the imported payload is looked up in the provider spec and inserted at the right place with the right type.

## Changes in this revision
1. Zero-operations workbook is now an error (`SPEC_LAYOUT_INVALID`), not an empty "successful" spec (which would later degrade to a misleading `NOT_IN_SPEC`/`SPEC_UNAVAILABLE`). Reverse case (override importer given a spec workbook) specified in 2.1.
2. All examples/fixtures/parity test use `POST /getCustDetails` (user's real URL is `{{base_url}}/getCustDetails`; `mock/mock_server.py` serves `/getCustDetails`; `UrlParts` strips a leading `{{var}}`). Rule stated in 2.1.
3. Scope cut to a "v1 core" column set; Enum/Min/Max/Nullable/ReadOnly/Description and response rows moved to a labelled "deferred / v1.1" list (schema mapping kept). WP5 trimmed: reuse importer's zip-bomb/encrypted/XXE tests, short loader-specific hostile list. Estimates and DoD updated.
4. Layout-mode rule (2.4) rewritten plainly; brace mode is built second (WP2b) behind Q4.
5. Removed the impossible `METHOD /path` sheet-name fallback (Excel forbids `/` in sheet names); Q2 reworded.
6. `examples/sample_spec.xlsx` moved from optional WP7 into core (WP5), used by README and `--check-spec` acceptance.
7. Code refs re-verified against current source (method names plus approximate lines); several tightened. Plan shortened.

---

## 1. Feasibility check

Verdict: FEASIBLE, small change. Every consumer of the spec takes an `io.swagger.v3.oas.models.OpenAPI` and never touches spec file text. A new loader that emits an `OpenAPI` object leaves the gap-fill pipeline unchanged. (Verified: `ParameterOverrideService.process` ~`:123-129` does `specLoader.load(...)`, then `new SpecIndex(getOpenAPI())` and `new SchemaNavigator(getOpenAPI())`.)

### 1.1 Reused as-is

| Piece | Where (approx. lines) | Note |
|---|---|---|
| `SpecIndex(OpenAPI)`, `matchOperation(operationId, method, rawUrl)`, `extractCleanPath` | `spec/SpecIndex.java` ctor ~`:79`, `matchOperation` `:159`, `extractCleanPath` `:222` | Needs `getPaths()` with `PathItem` operations; `getServers()` optional. |
| `SchemaNavigator(OpenAPI)`: body-schema extraction, `findSchemaPathsForLeaf`, `resolveExplicitPath` | `spec/SchemaNavigator.java` | Needs `RequestBody.content["application/json"].schema` of `ObjectSchema`/`ArraySchema`/primitives with ordered `getProperties()` and `getRequired()`. `components` is null-checked; none needed. |
| `ConstraintValidator.validate(Schema, ...)` | `spec/ConstraintValidator.java` | Reads standard `Schema` fields (`pattern` in v1; enum/min/max etc. when v1.1 lands). |
| `ParameterOverrideService.process` | `payload/ParameterOverrideService.java` ~`:105-133`; `opMatch.findParameter(name, "query"/"header"/"path")` ~`:516-569,852` | Query/header/path rows must become `Parameter` objects on the `Operation`. |
| Report fields `specPath/specSha256/specVersion/specMessages` | set ~`:124-127` | Only the version label changes ("excel-1.0"). |
| POI hardening | `importer/ExcelParameterImporter.java`: `MAX_WORKBOOK_SIZE_BYTES :21`, `MAX_SHEETS_PER_WORKBOOK/MAX_COLS_PER_ROW :61-62`, `configureZipSecurity() :68` (public static), encrypted/OLE2 + sheet-count + hidden-sheet checks ~`:81-125`, FORMULA cache handling ~`:340-375` | Reuse `configureZipSecurity()` and the importer's existing tests. Do not copy cell-reading code; extract a shared helper only if copying would exceed ~40 lines. |

### 1.2 What assumes OpenAPI (must change)
1. `spec/ProviderSpecLoader.load(Path)` `:103`: runs `RefGuard.scan(path)` (`:122`) and `OpenAPIParser.readLocation` (`:168`); both fail on a zip. Single dispatch point (insert after the cache check `:113`, bytes read `:118`, sha256 `:119`).
2. `RefGuard` is text-only; not applicable to xlsx (no `$ref` allowed, section 4).
3. Pickers/wording: `ui/ParameterImportDialog.java` (spec title ~`:66`, `chooseSpec` filter ~`:131`); `ui/MainFrame.java` (`attachProviderSpec` filter ~`:255`, `ProviderSpecLoader.loadOpenAPI` `:262`).
4. CLI: `Main.java` `--check-spec` (`:31`, usage `:54`, `loadOpenAPI` `:70`).
5. `ParameterOverrideService.apply` re-hash of spec bytes (~`:60-67`) is format-agnostic; works as-is.

Doc drift (not blocking): README "S5" says 50/50 sheets/columns; code is 200/256 (`ExcelParameterImporter.java:61-62`). Fix wording in WP6.

### 1.3 Integration seam
| Option | Verdict |
|---|---|
| A. `ExcelSpecLoader` builds a synthetic `OpenAPI` in memory; `ProviderSpecLoader.load` dispatches to it for `.xlsx` | RECOMMENDED: 1 new class + ~15 lines in the loader + UI/CLI text; zero change to `SpecIndex`/`SchemaNavigator`/`ConstraintValidator`/`ParameterOverrideService`. |
| B. New neutral `ProviderSpec` abstraction | Reject: rewrites three large classes and many call sites for no user gain. |
| C. Convert Excel to OpenAPI YAML text, then normal loader | Reject: pushes untrusted content through `RefGuard`/swagger-parser text path. |

Expected net new production code (v1 core): about 250-350 lines + ~25 lines of edits.

---

## 2. Spec Workbook contract (v1)

### 2.1 Workbook and sheet mapping
- `.xlsx` only (`.xls`/`.csv`/`.xlsm`/`.xlsb` rejected with `WORKBOOK_UNSUPPORTED`).
- One sheet = one operation. Prologue rows above the header (key in col A, value in col B; same convention as the importer's `target`/`location` rows ~`:195-207`):

| Meta key (case-insensitive) | Meaning | Default |
|---|---|---|
| `operation` | `METHOD /path`, e.g. `POST /getCustDetails` | none (sheet skipped) |
| `operationId` | optional; matched by `SpecIndex.matchOperation(operationId, ...)` | sheet name |
| `basePath` | optional, becomes `servers[0].url` | empty |
| `contentType` | request media type | `application/json` |

- Mapping: the `operation` row is the ONLY source of method + path; the sheet name is used only as the default `operationId`. A sheet without an `operation` row (or without a recognisable header) is skipped with a message; `README`/`Notes`/`Legend` sheets are skipped silently. (A `METHOD /path` sheet name is impossible: Excel forbids `/`.)
- Path rule: the `operation` path must equal the test-case URL path after the base URL; any leading `{{var}}` is stripped (`payload/UrlParts.java` ~`:46`), so `{{base_url}}/getCustDetails` matches `POST /getCustDetails`. Do not include `/api` unless the real URL does.
- Same `METHOD path` on two sheets: `SPEC_LAYOUT_INVALID`.
- Zero operations after load (every sheet skipped, e.g. user picked the override workbook or any wrong .xlsx): `SPEC_LAYOUT_INVALID` "no operation sheets found; is this the override workbook?" Never return an empty spec as success.
- Reverse case: the override importer given a spec workbook. Its header scan rejects an `operation` cell and non-allowed headers (`REJECTED_HEADERS`, `ExcelParameterImporter.java:79,169-181`) so it should already fail with "Invalid column header ...". Add one test to confirm (`specWorkbook_givenToOverrideImporter_failsClearly`); if the message is not clear, add one hint sentence there (only importer edit allowed, no behaviour change).

### 2.2 Columns (header located by the `Key` cell, case-insensitive)

v1 core:

| Column | Required | Meaning |
|---|---|---|
| `Key` | yes | Field name; nesting per 2.3/2.4. A literal `.` in a key is a real property name. |
| `Value` | no | Example value; infers type when `Type` blank. Blank for containers. |
| `Type` | no | `string`, `integer`, `number`, `boolean`, `object`, `array`, `string[]`, `object[]`, ... Wins over inference. |
| `Required` | no | `Y`/`N`/`true`/`false` (default `N`); fills the PARENT's `required` list. |
| `Pattern` | no | Java regex to `Schema.pattern` (ReDoS protection inherited from `SafeRegex` in `ConstraintValidator`). |
| `Location` | only if needed | `body` (default), `query`, `header`, `path`. Omit the column and all rows are body. |

Deferred / v1.1 (schema mapping kept, NOT built in v1; columns present in a sheet are ignored with one sheet-level message):

| Column | Maps to |
|---|---|
| `Enum` (pipe-separated) | `Schema.enum` |
| `Min`/`Max` | `minimum`/`maximum` (numbers), `minLength`/`maxLength` (strings) |
| `Nullable`, `ReadOnly` | `Schema.nullable`, `Schema.readOnly` (drives `READONLY_ADD`) |
| `Description` | `Schema.description` |
| `Location=response` | `responses["200"]` schema (gap-fill never reads responses; `--check-spec` only) |

Rationale: gap-fill needs names, nesting, types, required and optionally a pattern. Everything else is nice-to-have; add on request.

### 2.3 Indentation mode (default)
- depth = `cellStyle.getIndention()` + `leadingSpaces/2` (tab = 2 spaces); Key text then `trim()`med. Excel's "Increase Indent" button inserts no spaces, so reading spaces alone would silently flatten structure (the dangerous failure mode); hence both are summed.
- First data row depth 0; each row's depth <= previous + 1 (a bigger jump = `SPEC_LAYOUT_INVALID`, no repair); depth may drop by any amount; max depth 8 (`SPEC_LIMIT_EXCEEDED`). Odd space counts round down with a `WHITESPACE` warning. A parent is any row followed by a deeper row; it is an `object` (or array-of-objects per 2.5).

### 2.4 Layout-mode rule (brace mode = WP2b, implement second)
- A sheet is in BRACE mode if and only if at least one `Key` cell ends with `{` or `[`, or is exactly `{`, `}`, `[` or `]` (optionally followed by a trailing comma). Otherwise it is in INDENTATION mode. Decided per sheet, no mixing.
- Brace mode: `Name {` opens an object property, `}` closes it; `Name [` opens an array property, `]` closes it; a lone `{` inside `[ ... ]` opens the single exemplar element. Trailing commas ignored. Indentation is ignored for structure; if it disagrees with the braces, add a `WHITESPACE` warning. Unbalanced or crossed braces = `SPEC_LAYOUT_INVALID` with row numbers.
- Both modes stay in scope (user said "indentation or braces"). Build indentation first; build brace mode only after Q4 (a real sample sheet) is answered, since it may show only one style is used.

### 2.5 Arrays
| Notation | Meaning |
|---|---|
| `accounts[]` + children (or brace form) | array of objects; children describe ONE exemplar element |
| `tags[]` with `Type`/`Value`, no children | array of primitives |
| `accounts[0]` | same as `[]`; any other index = `SPEC_LAYOUT_INVALID` |
| `Type=object[]`/`string[]` | same as suffix; suffix and `Type` must agree |
| `Type=array`, no suffix/children/Value | items = `string` with a warning |

Gap-fill already maps `[*]` to `[0]` and raises `NEEDS_INDEX` (`ParameterOverrideService.java` ~`:652-672`); no navigator change.

### 2.6 Value typing
Precedence: `Type` column > container structure > cell kind of `Value`. TEXT is always `string` (leading zeros kept). NUMERIC integral = `integer` (warn "ID-like numeric; declare Type=string" for 6+ digits); fractional = `number`; BOOLEAN = `boolean`; BLANK leaf = `string` with warning. FORMULA: cached result only; none = row dropped, `FORMULA_NO_CACHE`. (Date-formatted cells to `format: date`: deferred.) `CustId` (6 digits) and `accountNum` (8 digits) must be `Type=string` + `Pattern=^\d{6}$` / `^\d{8}$`.

### 2.7 Cell-level rules
| Situation | Rule |
|---|---|
| Blank `Key` | Row ignored (warn if other cells filled). |
| Merged cells | Top-left only; vertical/data merges: `MERGED_CELL` warning. |
| Hidden sheet | Skipped (`HIDDEN_SHEET`); very-hidden always skipped. |
| Hidden/filtered rows, hidden columns | Included with `FILTERED_ROW`/warning (a spec is declarative). |
| Error cell | In Key: `CELL_ERROR`, row dropped. In Value: treated blank + warning. |
| Duplicate key, same parent | `SPEC_LAYOUT_INVALID` with both row numbers. Case-differing keys legal with warning. |
| Same leaf name in two objects | Legal; gap-fill raises `AMBIGUOUS` as for OpenAPI (~`:648-652`). |
| `Location=query/header/path` | Flat `Parameter`; nesting = `SPEC_LAYOUT_INVALID`; `path` params `required=true`. |
| Leaf with Value AND children | `SPEC_LAYOUT_INVALID`. |
| Stray `}` / `[1]` / anything unrecognised in a structural position | Error with sheet+row; whole workbook fails closed (no partial spec). |

### 2.8 Worked examples (user's real shape)
`.` = one indent level (2 spaces or Excel indent).

#### Example A: indentation mode (ships as `examples/sample_spec.xlsx`)
```
      A                 B                C        D         E
 1  operation          POST /getCustDetails
 2  operationId        getCustDetails
 3
 4  Key                Value            Type     Required  Pattern
 5  SessionContext                      object   Y
 6    BankCode         HDFC01           string   Y
 7    refid            R-1001           string   Y
 8    userid           U-77             string   Y
 9  CustId             123456           string   Y         ^\d{6}$
10  accountNum         12345678         string   Y         ^\d{8}$
```
Request exemplar: `{ "SessionContext": {"BankCode":"HDFC01","refid":"R-1001","userid":"U-77"}, "CustId":"123456", "accountNum":"12345678" }`.

In-memory model: `paths./getCustDetails.post` with `operationId: getCustDetails`, `requestBody.content[application/json].schema` = object, required `[SessionContext, CustId, accountNum]`, `SessionContext` = object (required `[BankCode, refid, userid]`, all string), `CustId`/`accountNum` = string with the patterns.

Gap-fill effect: override row `userid = U-99` against a payload lacking `SessionContext.userid` has one candidate `$.SessionContext.userid` and inserts a string (creating `SessionContext` if absent). `CustId = 12345` fails `^\d{6}$` with `CONSTRAINT_PATTERN` (policy-dependent), same as OpenAPI.

#### Example B: brace mode (WP2b), same API
```
 4  Key                  Value     Type     Pattern
 5  SessionContext {
 6    BankCode           HDFC01    string
 7    refid              R-1001    string
 8    userid             U-77      string
 9  }
10  CustId               123456    string   ^\d{6}$
11  accountNum           12345678  string   ^\d{8}$
```
Identical model to Example A (indentation cosmetic).

#### Example C: arrays
```
 Key                 Value     Type
 CustId              123456    string
 Accounts[]                    object[]       <- array of objects
   accountNum        12345678  string
   type              SAVINGS   string
 Tags[]              vip       string         <- array of primitives
```
Brace form of `Accounts[]`: `Accounts [` / `{` / `accountNum` / `type` / `}` / `]`. Gap-fill: bare `accountNum` with `Accounts` present = `NEEDS_INDEX`; with `Accounts` absent it creates element `[0]`.

### 2.9 Ambiguity rules (never guess)
Mode per 2.4; indent jump > +1 is an error; `Type` beats inference, `Type` vs suffix conflict is an error; leaf-with-children, duplicate keys, duplicate method+path, stray structural tokens, zero operations are errors; failures are fail-closed (no partial spec).

---

## 3. Detection and loading design

### 3.1 Detection
In `ProviderSpecLoader.load(Path)` after the cache check and byte read, before `RefGuard.scan`: `ExcelSpecLoader.looksLikeXlsx(path)` = name ends `.xlsx` (case-insensitive) AND first 4 bytes `PK\x03\x04`. Non-zip or OLE2 file named `.xlsx`: `WORKBOOK_UNSUPPORTED`/`WORKBOOK_ENCRYPTED` (reuse the importer's check). A `.json/.yaml/.yml` starting with `PK`: error "extension does not match content".

### 3.2 API
```java
public final class ExcelSpecLoader {
  public static ProviderSpecLoader.LoadedSpec load(Path path, byte[] bytes, String sha256) throws IOException;
  public static boolean looksLikeXlsx(Path path);
}
```
Returns the existing `LoadedSpec` (`ProviderSpecLoader.java:17`) with synthetic `openAPI`, `specVersion` "excel-1.0", `messages` = warnings. Package `spec/`.

### 3.3 Cache and sha256
Reuse `SPEC_CACHE` (`:88`) with a one-file `CacheEntry` (xlsx mtime, no missing files). sha256 via existing `computeSha256(bytes)`.

### 3.4 UI / 3.5 CLI / 3.6-3.7 suite and report
- `ParameterImportDialog` and `MainFrame`: add `xlsx` to the spec filter and title; switch `loadOpenAPI` to `load(...)` to show the generic version label, operation count and first 5 warnings.
- `Main.java --check-spec`: use `load(...)`; print format/version, operation count, per-operation `METHOD path` + leaf count, warnings; exit 1 + reason code on `SPEC_*`/`WORKBOOK_*`; update usage text.
- `TestSuite.specPath` and relative resolution (~`ParameterOverrideService.java:108-118`) are extension-agnostic: nothing to change. Report `specVersion = excel-1.0`, `specMessages` carries warnings; no new report fields.

---

## 4. Security and limits

An Excel spec is untrusted input like the override workbook.

| Control | Setting |
|---|---|
| ZIP hardening | Call `ExcelParameterImporter.configureZipSecurity()` explicitly from `ExcelSpecLoader` static init (importer's `static {}` is not guaranteed to run). Spec file cap 5 MB (`MAX_SPEC_WORKBOOK_BYTES`) -> `WORKBOOK_UNSUPPORTED`. |
| Parser | `XSSFWorkbook` from `new ByteArrayInputStream(bytes)` (bytes read once, which also removes the hash/parse TOCTOU). POI 5.5.1 (verify against `pom.xml`) hardens XML entities; proven by the existing importer XXE test, not duplicated. |
| Encrypted/legacy | Reuse `WORKBOOK_ENCRYPTED`/`WORKBOOK_UNSUPPORTED`. |
| Caps (v1) | sheets 50; rows/sheet 2,000; columns read 10; depth 8; properties/operation 1,000; key length 128; cell text 4,096; operations 50. Exceeding = `SPEC_LIMIT_EXCEEDED`. Row/col counts checked BEFORE iterating cells. |
| Formulas | Never evaluated; cached value only; no cache = row dropped + warning. |
| External links / macros | `.xlsm`/`.xlsb`/`.xls` rejected. `xl/externalLinks/*` present: load continues, warning, nothing fetched. |
| Hidden content | See 2.7. |
| Regex | `Pattern` stored verbatim, compiled by `ConstraintValidator`/`SafeRegex` (`util/SafeRegex`); at load reject `Pattern` over 512 chars or failing `Pattern.compile` (`SPEC_LAYOUT_INVALID`). |
| Keys | Reject control characters and keys over 128 chars; used only as JSON property names via the object model. |
| `$ref` | A `Key` or `Type` containing `$ref` or `#/` is rejected; Excel specs can never trigger remote/file resolution. |
| Reason codes | Reuse `WORKBOOK_UNSUPPORTED`, `WORKBOOK_ENCRYPTED`, `HIDDEN_SHEET`, `MERGED_CELL`, `FILTERED_ROW`, `WHITESPACE`, `CELL_ERROR`, `FORMULA_NO_CACHE`, `DUPLICATE` (names to be confirmed against `payload/ReasonCode.java`; `WORKBOOK_*` and `SPEC_UNAVAILABLE`/`NOT_IN_SPEC` confirmed). ADD only `SPEC_LAYOUT_INVALID` and `SPEC_LIMIT_EXCEEDED`. |
| Error surfacing | Thrown as `OverrideException(reason, msg)`/`IOException`; `process` catches and records `Spec parse warning: ...` (~`:130`) so gap-fill degrades to `SPEC_UNAVAILABLE`; CLI/UI show message + code. |

Security review by `sentinel-appsec-auditor` is a DoD item (WP6): this section is from code reading, not a PoC. (This revision was done by direct review; tech-advisor and sentinel-appsec-auditor were not invoked.)

---

## 5. Work packages (ordered)

Total about 2.5 working days for v1 core.

### WP1 - Loader skeleton and cell reader (0.5 d)
`spec/ExcelSpecLoader.java`: caps, `looksLikeXlsx`, `configureZipSecurity()`, open workbook from bytes, sheet iteration (hidden policy), prologue parser, header locator, `CellVal{text, kind}` reader (FORMULA cached only). Zero-operations check lives here. Accept: unit tests per cell kind.

### WP2 - Indentation parser and arrays (0.75 d)
Tree of `Node{name,type,children,flags}` from indentation depth (2.3), array notation (2.5), duplicate/leaf-with-children checks. Accept: Examples A and C parse; each 2.9 rule has a failing-case test with the row number in the message.

### WP2b - Brace mode (0.25 d, only after Q4)
Stack-based `{ } [ ]` parsing per 2.4. Accept: Example B equals Example A's model; unbalanced-brace test.

### WP3 - Tree to OpenAPI model (0.5 d)
`ObjectSchema`/`ArraySchema`/primitive schemas, parent `required`, `pattern`, ordered properties; `RequestBody`/`Parameter` assembly; method to `PathItem` setter (GET/POST/PUT/PATCH/DELETE). Accept: `new SpecIndex(openAPI).matchOperation("getCustDetails","POST","https://h/getCustDetails")` returns the operation; same via `{{base_url}}/getCustDetails`.

### WP4 - Wire-up (0.25 d)
`ProviderSpecLoader.load` xlsx branch (single-file `CacheEntry`, skips `RefGuard`/parser); add two `ReasonCode`s; UI filters/labels; `--check-spec`. Accept: `--check-spec examples/sample_spec.xlsx` prints `excel-1.0`, 1 operation, exit 0; a broken sheet prints `SPEC_LAYOUT_INVALID` with row, exit 1.

### WP5 - Tests, fixtures, sample (0.5 d)
- `testsupport/SpecXlsxBuilder.java` (sibling of `XlsxFixtureBuilder`, which is override-sheet specific): `.sheet(..).meta(..).header(..).row(depth,key,value,type,required,pattern)`, `.indentMode(SPACES|CELL_INDENT)`, `.hidden()`, `.formula(..)`, `.build(Path)`.
- `spec/ExcelSpecLoaderTest` (v1): `exampleA_indentSpaces_buildsExpectedSchema`, `exampleA_excelCellIndent_equalsLeadingSpaces`, `exampleC_arrays`, `exampleB_braceMode_sameModelAsIndent` (WP2b), `arrayIndexOtherThanZero_rejected`, `typeInference_textStaysString`, `idLikeNumericCell_warns`, `indentJump_rejected_withRow`, `unbalancedBrace_rejected_withRow` (WP2b), `duplicateKey_rejected`, `leafWithChildren_rejected`, `hiddenSheet_skipped`, `formulaCachedValue_used_noCacheRowDropped`, `patternInvalidOrTooLong_rejected`, `queryHeaderPathRows_becomeParameters`, `sameMethodPathOnTwoSheets_rejected`, `readmeSheet_ignored`, `zeroOperations_rejected_withOverrideWorkbookHint`, `specWorkbook_givenToOverrideImporter_failsClearly`, `operationPathMatchesBaseUrlVariableUrl`.
- Loader-specific hostile list (`spec/ExcelSpecSecurityTest`): zip named `.json`; non-zip/OLE2 named `.xlsx`; 1M-row sheet and 10k-column row capped before iteration; depth-9 chain; key `$ref`/`#/` rejected; ReDoS `(a+)+$` accepted at load but guarded at validation (no hang); `xl/externalLinks` loads with warning and no network I/O (local listener sees 0 connections). Zip-bomb, encrypted and XXE are covered by the existing `importer/ZipBombSecurityTest` and importer tests plus the shared `configureZipSecurity()`; add one assertion that the spec loader invokes it, not new copies.
- Cache: `xlsxModified_invalidatesCache`, `sameXlsx_secondLoadFromCache`, sha256 equals `computeSha256(bytes)`.
- Parity test `payload/ExcelSpecParityTest`: same suite + override workbook run with `getcustdetails.xlsx` and an equivalent `getcustdetails-oas3.yaml` (both `POST /getCustDetails`); assert equal action, resolvedPath, new value/type, reason codes, final payload (`JsonPointerDiff`). Cases: bare-name (`userid`), explicit path (`SessionContext.BankCode`), missing parent creation, `CustId=12345` pattern violation, `NEEDS_INDEX`, `NOT_IN_SPEC`, query/header/path param.
- Fixtures generated from the builder in tests (no opaque binaries), EXCEPT `examples/sample_spec.xlsx` (Example A with `POST /getCustDetails`): generated once from the builder, checked in, referenced by the README and the `--check-spec` acceptance step. A test asserts it loads (guards against rot).
- Regression: existing `GapFillTest`, `SpecIndexTest`, `SpecLoaderSecurityTest`, `ExcelParameterImporterTest`, `ZipBombSecurityTest` stay green.

### WP6 - Security review and docs (0.25 d)
`sentinel-appsec-auditor` over `ExcelSpecLoader`, the dispatch and tests. README "Excel provider spec" section (2.1-2.5, Example A, `examples/sample_spec.xlsx`), Known Limitations, S5 wording fix, CLI line. Do not edit `phase1plan.md`/`fixphase1.md` without separate approval.

### v1.1 (only on request)
Deferred columns (2.2), date format, response rows, "Save sample spec" menu item.

### Definition of Done
- [ ] `ExcelSpecLoader` output is consumed by unchanged `SpecIndex`, `SchemaNavigator`, `ConstraintValidator`, `ParameterOverrideService` (no edits in those four files).
- [ ] Examples A and C (and B if WP2b built) pass; all use `POST /getCustDetails`.
- [ ] Parity test passes for all listed cases.
- [ ] Zero-operations workbook fails with `SPEC_LAYOUT_INVALID`; spec workbook given to the override importer fails clearly (tests in WP5).
- [ ] Loader-specific hostile tests pass; no test needs network.
- [ ] `--check-spec examples/sample_spec.xlsx` works (valid and invalid); exit codes correct.
- [ ] UI picker accepts `.xlsx`; dialog shows `excel-1.0`, operation count, warnings.
- [ ] Report shows `specVersion = excel-1.0`; spec sha256 matches file bytes.
- [ ] Two new `ReasonCode`s only; caps enforced and tested; `configureZipSecurity()` invoked explicitly.
- [ ] README updated; `mvn -q test` green; `mvn package` still yields one shaded jar.
- [ ] Security review findings closed.

---

## 6. Risks, non-goals, open questions

| Risk | Mitigation |
|---|---|
| Users rely on "Increase Indent" (no spaces) | Read `getIndention()` + test; warn if every row is depth 0 yet brace-like text exists |
| Mixed/noisy layouts | Fail closed with row numbers; deterministic mode rule (2.4) |
| Numeric ID cells lose leading zeros | Text stays string; ID-like warning; `Type=string` + `Pattern` |
| Excel vs OpenAPI semantic drift | Parity test is a hard gate |
| Wrong/stale hand-made spec, or wrong workbook picked | Zero-operations error; warnings in report; sha256 + version label recorded |
| POI memory / attack surface | 5 MB cap, caps before iteration, reuse importer hardening, no formulas/refs, audit gate |

Non-goals: no `$ref`, `allOf/oneOf/anyOf`, `additionalProperties`, multiple media types, multiple response codes, XML bodies, spec export/editing; no formula evaluation, `.xls`/`.csv`/`.xlsm`, remote URLs; no change to the override workbook contract.

### Open questions (recommended defaults)
| # | Question | Recommended default |
|---|---|---|
| Q1 | Indent unit = 2 spaces / 1 tab / 1 Excel indent level? | Yes; configurable later, not now. |
| Q2 | How does a sheet name its operation? | `operation` prologue row (e.g. `POST /getCustDetails`) is the only mapping; sheet name = default `operationId`. Confirm a 2-row prologue is fine. |
| Q3 | Single request body per API; is a response needed? | Request only in v1; `Location` column optional (all body if absent); response rows deferred. |
| Q4 | Does a real sheet exist (columns/names/layout)? GATE for WP2b. | Ask for one sample; add header aliases (`Field`/`Name`, `Example`/`Sample`, `Mandatory`) (~10 lines); build brace mode only if the sample uses braces. |
| Q5 | Hidden rows skipped or included? | Included with warning. |
| Q6 | Digit lengths as `Pattern` or `Min=Max`? | `Pattern` in v1 (`Min/Max` deferred). |
| Q7 | One workbook per provider, many sheets? | Yes (max 50 operations). |
| Q8 | `key[0]` = exemplar marker only? | Yes; non-zero index is an error. |
| Q9 | Which paths accept `.xlsx`? | All, via the single `ProviderSpecLoader.load` seam. |
| Q10 | Zero operations after load? | Error `SPEC_LAYOUT_INVALID` (new). |
| Q11 | v1 column set / deferred list as in 2.2? | Yes: Key, Value, Type, Required, Pattern (+ Location). Confirm Enum/Min/Max/response can wait. |
| Q12 | Ship `examples/sample_spec.xlsx` in core? | Yes, generated once from the builder, with a loads-OK test. |

---

## 7. Implementation order recap
WP1 -> WP2 (indentation + arrays) -> WP3 -> WP4 (wire-up) -> WP5 tests/parity/sample (can start after WP3) -> WP2b brace mode (after Q4 answered) -> WP6 (audit, README) -> v1.1 only on request.
