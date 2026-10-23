# Phase 1 Implementation Plan — API Regression Tool

## 0. Goal & Constraints
- Local desktop app, runs on a JDK-only office laptop (no Python/Node, no other SDK installs).
- Build with Maven (dependency download via Central/internal mirror is allowed; after first `mvn package` everything is cached in `~/.m2`, so later builds work offline).
- Ship as a single runnable fat/shaded JAR: `java -jar api-regression.jar`.
- UI: Swing (bundled in JDK, no JavaFX needed).
- Core capability: define one or more API calls with user-supplied parameters/payloads, run them as a suite, diff actual vs expected JSON response, report pass/fail.
- Secondary capability: import a Postman Collection (v2.1 JSON) [+ optional Postman Environment JSON] as a starting point for test cases.
- Tertiary capability: designate one (or more) APIs in a suite as a token-generation API (e.g. Authman), auto-extract the issued token from its response, and inject it into the headers of any other API in the suite that needs it.
- Quaternary capability: import an Excel workbook (`.xlsx`) of user-defined parameter values — one sheet per API (TestCase). Only the parameters listed in a sheet are changed in that TestCase's imported payload/URL/headers; everything else stays exactly as imported. A listed parameter that is not present in the payload is looked up in the provider's OpenAPI/Swagger spec and inserted at its spec-defined location with a spec-correct type. Every row yields an auditable report entry (MODIFIED / ADDED / SKIPPED / ERROR) — nothing is guessed, nothing is silently dropped. Contract and rules: §3a.

## 1. Tech Stack
| Concern | Choice | Why |
|---|---|---|
| Language/runtime | Java 11+ | HttpClient built in, no extra runtime install |
| Build | Maven + `maven-shade-plugin` | single fat jar output |
| HTTP client | `java.net.http.HttpClient` | built into JDK, no dependency |
| JSON parse/serialize | `com.fasterxml.jackson` (core + databind) | de facto standard, handles arbitrary Postman/response JSON |
| JSON diff/assertions | `net.javacrumbs.json-unit:json-unit-core` | structural diff, path-based "ignore" support, partial match |
| JSONPath extraction | `com.jayway.jsonpath:json-path` | pull token (or any field) out of a response body by path, used for Authman-style token extraction |
| GUI | Swing | bundled in JDK |
| Persistence | Flat JSON files (via Jackson) on local disk | no DB needed, git-friendly, human-editable |
| Excel read | Apache POI `org.apache.poi:poi-ooxml` **5.5.1** (pinned; 6.0.0 shipped 2026-10-19 — too fresh, revisit after it has a patch release) | only mature pure-Java OOXML reader; exposes cell types, date formats, cached formula results. **`.xlsx` only** — no `poi` HSSF `.xls` path (see §5, Open Q 8) |
| Provider spec parsing | `io.swagger.parser.v3:swagger-parser` **2.1.48** | one code path for Swagger 2.0 (auto-converted to the OAS3 model) + OpenAPI 3.0/3.1, JSON + YAML, resolves internal and relative-file `$ref`s |
| Log bridge | `org.apache.logging.log4j:log4j-to-slf4j` 2.24.3 (matches POI's log4j-bom) | POI logs via log4j-api; route it into the existing slf4j-simple instead of a "no Log4j provider" warning at startup |

Spec-parser alternatives considered:
- **Jackson-only hand-rolled parser** (JsonNode + jackson-dataformat-yaml, own `$ref`/`allOf` resolver): +~0.5 MB, but ~500–800 LOC of resolver code (internal + relative-file `$ref`, cycles, `allOf` merge, Swagger 2 `definitions` / `in: body` vs OAS3 `components` / `requestBody`). That is exactly where the bugs would live — not chosen.
- **`swagger-parser-v3` only** (OAS3 only, ~+3 MB): fallback if the provider confirms OAS3 (Open Q 1). Drops the v2 converter's ~8 MB tail (guava, rhino, json-schema-validator, libphonenumber, httpclient 4).
- Parse options: `resolve=true`, `resolveFully=true`, `resolveCombinators=false` — we merge `allOf` ourselves in `SchemaNavigator` so `oneOf`/`anyOf` stay visible for ambiguity detection. Remote (`http/https`) `$ref`s are **not** fetched (offline laptop) and are reported as spec warnings (confirm the safe-URL / remote allow-list option names against 2.1.48 when wiring).

Build / shade notes for the new deps:
- **Jackson alignment (must fix):** swagger-core 2.2.53 is built on Jackson 2.22.x; the pom pins 2.18.2 directly, so nearest-wins gives mixed `jackson-core/databind` 2.18 + `dataformat-yaml` 2.22 at runtime → `NoSuchMethodError`. Import `com.fasterxml.jackson:jackson-bom` and bump `jackson.version` to 2.22.1; re-run all existing tests.
- **`ServicesResourceTransformer` (must add):** Swagger 2.0 support is discovered via `META-INF/services/io.swagger.v3.parser.core.extensions.SwaggerParserExtension`. Without the merge, 2.0 specs parse in the IDE/`mvn test` but fail inside the fat jar. Smoke-test the jar, not just the tests (Step 7c Verify).
- Also: exclude `module-info.class` / `META-INF/versions/*/module-info.class`, set `Multi-Release: true` in the manifest (Jackson/log4j are MR jars), keep the existing `*.SF/*.DSA/*.RSA` excludes. Do **not** enable `minimizeJar` (POI/XMLBeans load schema classes reflectively).
- Jar size: 3.5 MB today → ~23 MB with POI (tree ≈ 19 MB: ooxml-lite 6 MB, poi 3 MB, xmlbeans 2.2 MB, commons-math3 2.2 MB, …) → ~30 MB with full swagger-parser. Fine for a local desktop tool.

## 2. Module / Package Layout
```
api-regression/
  pom.xml
  src/main/java/com/sabyasachi/apiregression/
    model/
      TestCase.java          # one API call definition
      TestSuite.java         # ordered list of TestCase + shared vars/defaults
      TestResult.java        # outcome of running one TestCase
      Auth.java              # bearer/basic/apikey/none
    client/
      HttpRunner.java        # builds + fires HttpRequest, returns raw response
      Substitutor.java       # {{var}} placeholder resolution (url/headers/body)
    compare/
      JsonComparator.java    # json-unit wrapper, ignore-path support, diff report
    store/
      SuiteRepository.java   # load/save TestSuite as JSON file
    importer/
      PostmanCollectionImporter.java
      PostmanEnvironmentImporter.java
      ExcelParameterImporter.java # NEW: .xlsx -> List<SheetParams>; cell rules §3a.2; no JSON/spec knowledge
      SheetParams.java / ParamRow.java # NEW: sheet name, target, metadata; row#, parameter, raw value, cell kind, type, location, enabled, notes
    spec/                     # NEW
      ProviderSpecLoader.java # swagger-parser wrapper: local JSON/YAML -> OpenAPI model + messages; cache by path+mtime
      SpecIndex.java          # operation lookup by operationId or method + path template (basePath/servers aware); merged path+op parameters
      SchemaNavigator.java    # $ref-resolved walk, allOf merge, items, oneOf/anyOf+discriminator, cycle guard; leaf-name -> candidate paths
      ConstraintValidator.java # enum/format/length/range/pattern/nullable/readOnly checks -> warnings or errors per policy
    payload/                  # NEW
      ParamPath.java          # parse `$.a.b[0].c`, `a.b[0].c`, `$['odd.key']`, bare name; rejects `*`, `..`, `?()`
      JsonTemplateCodec.java  # placeholder masking + order-preserving, exact-decimal parse/serialize of bodyTemplate
      TypeCoercer.java        # raw cell value -> JsonNode for target type (type col > spec > existing node > cell type)
      PayloadOverrider.java   # applies body rows to ObjectNode: modify existing / create intermediates + add
      RequestPartOverrider.java # query (url), path segment (via spec template), header rows
      ParameterOverrideService.java # UI-free orchestrator: dryRun()/apply() -> OverrideReport (headless entry point)
      OverrideReport.java / OverrideEntry.java / SheetSummary.java / OverrideOptions.java
    runner/
      SuiteRunner.java        # orchestrates: resolve token providers -> substitute -> call -> compare -> TestResult
      TokenProviderResolver.java # runs designated token/Authman TestCases first, extracts token, feeds it into variables
      ReportWriter.java       # export results to HTML/JSON report file; + writeOverrideJson/writeOverrideHtml(OverrideReport)
    ui/
      MainFrame.java          # + menu: "Attach Provider Spec…", "Import Parameter Sheet…"
      SuiteEditorPanel.java   # add/edit/delete TestCase forms
      RunResultsPanel.java    # table of pass/fail + diff viewer; + "PARTIAL OVERRIDE" badge
      ImportDialog.java       # file picker for Postman collection/env
      TestCaseEditDialog.java # + operationId field, "Revert to baseline", "Promote to baseline"
      ParameterImportDialog.java # NEW: .xlsx picker, spec picker (pre-filled from suite), policy options -> dry run
      OverridePreviewPanel.java  # NEW: per-sheet summary + entries table, filter by action, Apply / Export report
    Main.java                 # + `--check-spec <file>` diagnostic (prints spec version + operation count, exits)
  src/test/java/...           # JUnit tests per module
```

## 3. Data Model (first cut)
```java
TestCase {
  String name;
  String method;              // GET/POST/PUT/PATCH/DELETE
  String url;                 // may contain {{var}}
  Map<String,String> headers;
  Auth auth;                   // optional
  String bodyTemplate;         // raw JSON, may contain {{var}}
  int expectedStatus;
  String expectedBodyTemplate; // raw JSON (full or partial)
  List<String> ignorePaths;    // e.g. "$.createdAt", "$.id"
  String category;             // folder path, from Postman import or manual grouping

  // --- token-provider (Authman) support ---
  boolean tokenProvider;        // true if this TestCase is a token-generation API
  String tokenJsonPath;         // e.g. "$.access_token", "$.data.token" — where to pull the token from the response body
  String tokenHeaderSource;     // optional: name of a RESPONSE header to pull the token from instead of the body (if set, takes precedence over tokenJsonPath)
  String tokenVariableName;     // variable name this token is stored under, e.g. "authToken" -> referenced elsewhere as {{authToken}}
  List<String> usesTokenFrom;   // for NON-provider TestCases: list of tokenVariableName values this request depends on (used to enforce run order + header injection, typically wired into a header value like "Bearer {{authToken}}")

  // --- Excel parameter override / provider spec support (all nullable; old suite files load unchanged) ---
  String operationId;           // optional explicit link to a spec operation; auto-filled on first successful method+path match
  RequestSnapshot baseline;     // {url, headers, bodyTemplate} as imported, captured before the FIRST override;
                                // every apply starts from this (never from an already-overridden copy) -> idempotent re-import
  OverrideProvenance lastOverride; // {workbookFile, workbookSha256, sheet, specSha256, appliedAt, modified, added, errors}
}

TestSuite {
  String name;
  String baseUrl;
  Map<String,String> variables;   // shared {{var}} values (env-like)
  List<TestCase> testCases;
  String specPath;                // NEW: provider spec file (JSON/YAML), stored relative to the suite JSON file
  OverrideOptions overrideOptions; // NEW: {constraintPolicy WARN|STRICT = WARN, skipHiddenSheets = true,
                                  //       maxRowsPerSheet = 5000, applyWithErrors = false}
}

enum OverrideAction   { MODIFIED, ADDED, SKIPPED, ERROR }
enum ParamLocation    { BODY, QUERY, PATH, HEADER }

OverrideEntry {
  String sheet; int row;         // 1-based Excel row, as the user sees it
  String parameter;              // as written in the sheet
  String resolvedPath;           // JSON pointer / query name / header name actually touched (null if unresolved)
  ParamLocation location;
  String rawValue;               // cell text as read
  JsonNode oldValue;             // null if ADDED / unresolved
  JsonNode coercedValue;
  OverrideAction action;
  String reasonCode;             // e.g. BLANK, DISABLED, DUPLICATE, NOT_IN_SPEC, AMBIGUOUS, TYPE_UNCOERCIBLE, SPEC_UNAVAILABLE
  String reason;                 // human-readable
  String specPointer;            // e.g. #/components/schemas/Address/properties/city (ADDED rows)
  List<String> candidates;       // AMBIGUOUS / did-you-mean suggestions
  List<String> warnings;         // constraint WARNs, TYPE_CHANGED, REQUIRED_SIBLINGS_MISSING, ...
}

SheetSummary { String sheet; String targetTestCase; String matchedBy; // NAME | OPERATION_ID | METHOD_PATH
               int modified, added, skipped, errors, warnings;
               String status; }                                   // APPLIED | PARTIAL | NOT_APPLIED | UNMATCHED | CONFLICT
OverrideReport { String workbook, workbookSha256, specPath, specVersion, specSha256;
                 ConstraintPolicy policy; boolean dryRun; Instant createdAt;
                 List<SheetSummary> sheets; List<OverrideEntry> entries; List<String> specMessages; }

TestResult {
  String testCaseName;
  boolean statusPassed;
  boolean bodyPassed;
  int actualStatus;
  long latencyMs;
  String diffSummary;          // human-readable json-unit diff
  Instant executedAt;
}
```

## 3a. Excel Parameter Override — Contract & Rules

### 3a.1 Workbook contract
- `.xlsx` only. **One sheet = one API = one TestCase**, with one or more parameter rows.
- Excel sheet names are max 31 chars and forbid `/ \ ? * [ ] :` — so `POST /customers` can never be a sheet name. Hence an optional target cell:
  - If `A1` = `target` (case-insensitive), `B1` is the target; otherwise the sheet name is the target.
  - Target matched, first hit wins, **no fuzzy matching**: exact `TestCase.name` → `operationId` (explicit on the TestCase, or resolved via spec) → `METHOD /path-template` (e.g. `POST /customers/{id}`) against each TestCase's spec-matched template.
  - 0 matches → sheet `UNMATCHED` (reported, nothing applied). >1 TestCases match → `AMBIGUOUS_TARGET` with candidate names (use the exact name). Two sheets → same TestCase → second sheet `CONFLICT`, not applied.
- Optional metadata rows above the header (key in column A, value in B): `target`, `location` (sheet default location), `notes`. Header row = first row with a cell equal to `parameter`.
- Columns (header names case-insensitive, any order; unknown columns ignored with a warning):

| Column | Req | Meaning |
|---|---|---|
| `parameter` | Y | `$.customer.address[0].city`; `customer.address[0].city` (`$.` implied); `$['odd.key']`; or bare name `city` (resolved by lookup, §3a.4). Wildcards / recursive descent / filters (`*`, `..`, `?()`) → ERROR — one row must target exactly one location |
| `value` | Y | see §3a.2 |
| `type` | N | `string \| integer \| number \| boolean \| json \| date \| date-time` — forces the coercion target |
| `location` | N | `body \| query \| path \| header`. Default: `body` if the parameter is a path (`$`, `.`, `[`); bare names resolved via payload then spec |
| `enabled` | N | `N/no/false/0` → SKIPPED (`DISABLED`); blank = enabled |
| `notes` | N | copied to report only |

### 3a.2 Cell value rules
| Cell content | Result |
|---|---|
| Blank / empty cell | SKIPPED (`BLANK`) — field left as imported. Blank never means null or `""` |
| `<null>` | JSON `null` (constraint check: nullable, §3a.5) |
| `<empty>` | `""` |
| Text | as-is, no trim (warning if leading/trailing whitespace); leading zeros kept (`00123`) |
| Numeric | `BigDecimal` via `Double.toString`, integral values lose `.0`. \|value\| ≥ 1e15 → ERROR: Excel keeps 15 significant digits, so PANs/account numbers must be entered as Text cells |
| Numeric + date format (`DateUtil.isCellDateFormatted`) | `yyyy-MM-dd` if target format is `date`, else `yyyy-MM-ddTHH:mm:ss` (no zone). Zoned timestamps: use Text cells |
| Boolean cell | `true/false`. Text `true/false` (case-insensitive) accepted for boolean targets; `Y/N/1/0` not auto-converted |
| Formula | **cached result only** (`getCachedFormulaResultType`); never evaluated. Cached error (`#N/A`, `#REF!`) or no cached value → ERROR |
| `{…}` / `[…]` | parsed as JSON only when `type=json` or the target (spec/existing node) is object/array; otherwise a plain string. Invalid JSON → ERROR |
| Contains `{{var}}` | passed through verbatim, resolved by `Substitutor` at run time. A whole-value placeholder into a number/boolean target is written unquoted (`"amount": {{amt}}`) |
| Merged cells | only top-left holds the value; the rest treated blank + warning |
| Same `parameter` twice in a sheet | last row wins; earlier rows SKIPPED (`DUPLICATE`) + warning naming the winning row |
| Hidden sheet | SKIPPED (`HIDDEN_SHEET`) unless `skipHiddenSheets=false`; very-hidden always skipped |
| Hidden / filtered rows | processed (hiding ≠ disabling — use `enabled`) + warning |
| Limits | > `maxRowsPerSheet` (5,000) → sheet NOT_APPLIED; workbook > 20 MB rejected; POI `ZipSecureFile` defaults kept (zip-bomb guard) |

Coercion target precedence: `type` column > spec schema type/format > existing JSON node type > raw cell type. Text `00123` into spec `string` stays `"00123"`; into spec `integer` → `123` + warning `LEADING_ZEROS_DROPPED`.

### 3a.3 Override-only semantics (invariants)
- Overrides act on the **templates** (`bodyTemplate`, `url`, `headers`) starting from `baseline`, never on substituted runtime values.
- Only each row's target node changes. **No deletions, no reordering, no unrequested siblings** (not even spec-`required` ones — warn instead).
- `JsonTemplateCodec`:
  - masks unquoted placeholders (`"amount": {{amt}}` is not valid JSON) to sentinel strings before parsing and restores them after serializing; quoted placeholders are untouched.
  - Jackson `ObjectNode` (insertion-ordered → key order preserved), `JsonNodeFactory.withExactBigDecimals(true)` + `USE_BIG_DECIMAL_FOR_FLOATS` (`10.50` stays `10.50`), `STRICT_DUPLICATE_DETECTION` (duplicate keys in the source body → TestCase-level ERROR instead of Jackson silently keeping the last). Writes via `JsonPointer`.
  - Guarantee is **structural, not byte-level**: whitespace is re-emitted with the default pretty printer on first override (one-time formatting churn). Tested invariant: baseline with overridden pointers removed == result with the same pointers removed.
- Type mismatch (existing node type ≠ value type): with explicit `type` column → apply + warning `TYPE_CHANGED number→string`; without → coerce to existing type (or spec type if known); failure → ERROR, field unchanged.
- Arrays: `[i]` with i < size → modify; i == size → append (ADDED); i > size → ERROR (no sparse fill).
- Body not JSON (form/XML) → body rows ERROR `BODY_NOT_JSON`. Empty body + spec defines a JSON `requestBody` → start from `{}`.
- Query: parsed from `url`; other params' order and encoding preserved; new value URL-encoded (placeholders left raw); repeated query key → ERROR (ambiguous). Path: segment located via the spec path template (`/customers/{id}` ↔ `/customers/123`, `/customers/{{custId}}`, `/customers/:id`) and replaced. Header: case-insensitive match, original header-name casing kept; added only if absent and declared in spec.

### 3a.4 Gap-fill algorithm (missing parameter → provider spec)
```
1. Payload lookup
   explicit path -> exists? MODIFY
   bare name     -> search body leaf keys + query/path/header names
                    1 hit -> MODIFY | >1 -> ERROR AMBIGUOUS(candidates) | 0 -> step 2
2. Resolve operation (once per sheet)
   TestCase.operationId -> spec op; else method + url vs spec path templates
   (strip scheme/host/{{baseUrl}} and spec basePath / servers[].url path; literal segments must match,
    {p} matches any one segment; most literal segments wins; tie -> ERROR AMBIGUOUS_OPERATION)
   no spec / unparseable / op not found -> gap-fill rows ERROR (SPEC_UNAVAILABLE | OPERATION_NOT_FOUND);
   MODIFY rows on the same sheet are still applied
3. Find parameter in the operation
   a. parameters: path-level + op-level merged (op wins on name+in), $ref resolved
      -> QUERY / PATH / HEADER (applied to url/headers, never body); cookie -> ERROR unsupported
   b. requestBody: content application/json or */*+json (else ERROR BODY_MEDIA_UNSUPPORTED)
      walk schema: $ref resolved, allOf merged, properties, array items; depth cap 10, cycle guard
      explicit path -> each segment validated against the schema
      bare name     -> all schema paths whose leaf == name
                       0 -> step 4
                       1 -> use; if it passes through an array -> ERROR NEEDS_INDEX
                            (unless that array is absent: then create it with element [0])
                       >1 -> ERROR AMBIGUOUS(candidate paths) — never "first match"
      bare name in both parameters and body -> ERROR AMBIGUOUS_LOCATION (fill `location` column)
   c. oneOf/anyOf on the path: discriminator present and its value already in payload -> that branch;
      else property exists in exactly one branch (or with same type in all) -> use;
      else ERROR AMBIGUOUS_POLYMORPHIC (give explicit path / set the discriminator first)
4. Not declared
   parent additionalProperties explicitly true/schema AND explicit full path -> ADDED + warning UNDECLARED
   else ERROR NOT_IN_SPEC (+ did-you-mean: case-insensitive / edit distance <= 2). Never silently dropped.
5. Apply
   create missing intermediate objects/arrays per schema type, set coerced value, validate (3a.5),
   record ADDED with specPointer. New intermediate object with unset required children
   -> warning REQUIRED_SIBLINGS_MISSING [street, zip]
```

### 3a.5 Validation policy (`OverrideOptions.constraintPolicy`)
| Check | WARN (default) | STRICT |
|---|---|---|
| Value not coercible to target type | ERROR | ERROR |
| `enum`, `format` (date, date-time, uuid, email, int32/int64 range), `minLength/maxLength`, `minimum/maximum` (+exclusive), `pattern` | warning | ERROR |
| `<null>` on non-nullable (OAS 3.0 `nullable`, 3.1 `type: [..., "null"]`) | warning | ERROR |
| `readOnly` property — MODIFY existing | warning | ERROR |
| `readOnly` property — ADD from spec | ERROR | ERROR |
| `pattern` not compilable by `java.util.regex` (ECMA dialect differences) | warning, check skipped | warning, check skipped |

Values containing `{{var}}` skip constraint checks (unknown until run time); noted on the entry. `writeOnly` is normal for requests.

### 3a.6 Workflow
- GUI: open suite → **Attach Provider Spec…** (local `.json/.yaml/.yml`; saved as `TestSuite.specPath`; remote URL deferred) → **Import Parameter Sheet…** (pick `.xlsx`, policy options) → **dry run always first** → `OverridePreviewPanel` (per-sheet summary + entries; filter by action; row shows old → new + reason) → **Apply** (disabled while ERRORs exist unless "apply despite errors" is ticked) → TestCases' `bodyTemplate` / `url` / `headers` updated, `baseline` captured if null, `lastOverride` set → suite marked dirty → Save → Export report.
- Headless: `ParameterOverrideService.dryRun(suite, xlsxPath, specPath, options)` / `.apply(...)` → `OverrideReport`. UI-free, so fully JUnit-testable; a CLI wrapper ships with the Phase 2 headless runner.
- Idempotency: apply always starts from `baseline` → re-importing the same workbook yields byte-identical `url` / `headers` / `bodyTemplate` (only `lastOverride.appliedAt` changes); a changed workbook **replaces** prior overrides (no stacking). If the current request differs from what `lastOverride` produced (hand edits after import), the dry run warns "manual edits since last import will be discarded". `TestCaseEditDialog`: **Revert to baseline**, **Promote to baseline** (accept current as the new imported state).

### 3a.7 Edge cases (beyond the tables above)
- GET / no-body TestCase with body rows and no spec `requestBody` → ERROR `BODY_NOT_APPLICABLE`.
- Token-provider (Authman) TestCases can be targeted like any other (e.g. override `client_id`).
- Encrypted / password-protected workbook → ERROR "save an unprotected copy" (no password prompt in Phase 1). `.xls` / `.csv` picked → "Save As .xlsx" message.
- Workbook still open in Excel: read via `InputStream` (read-only), not `OPCPackage.open(File)`, so Windows file locks don't block import.
- Keys containing dots or brackets → `$['a.b']` bracket notation; Unicode keys supported.
- Spec edited between imports → report records `specSha256`; re-import re-evaluates against the new spec.
- Large spec parsed once per import, cached by path + mtime.
- Spec-level problems (unresolved `$ref`, remote `$ref`, parse warnings) → `OverrideReport.specMessages`, shown above the entries table.

### 3a.8 Reporting
- In-UI `OverridePreviewPanel`: summary table (sheet · target TestCase · matched by · modified · added · skipped · errors · warnings · status), entries table (sheet · row · parameter · location · old → new · action · reason), errors sorted first.
- `ReportWriter.writeOverrideJson(OverrideReport, Path)` / `.writeOverrideHtml(...)`: header carries workbook name + sha256, spec path + version + sha256, policy, dry-run vs applied, timestamp; per-sheet counts of MODIFIED / ADDED / SKIPPED / ERROR.
- `RunResultsPanel`: TestCases whose `lastOverride.errors > 0` show a **PARTIAL OVERRIDE** badge so a run result is never read without knowing its inputs were incomplete.

## 4. Build Order (do in this sequence)

### Step 1 — Project skeleton
- `mvn archetype` or hand-written `pom.xml` with Jackson, json-unit-core, shade plugin.
- `Main.java` that just opens an empty Swing `MainFrame`.
- Verify: `mvn clean package` produces a runnable jar; `java -jar` opens a blank window.

### Step 2 — Model + Persistence
- Implement `TestCase`, `TestSuite`, `Auth`, `TestResult`.
- Implement `SuiteRepository.save(TestSuite, Path)` / `.load(Path)` using Jackson.
- Verify: unit test round-trips a hand-built `TestSuite` to JSON and back, fields match.

### Step 3 — HTTP execution
- Implement `Substitutor` (simple `{{key}}` -> value string replace across url/headers/body; unresolved placeholders collected as warnings).
- Implement `HttpRunner.execute(TestCase, Map<String,String> resolvedVars)` using `HttpClient` — builds `HttpRequest`, sends, returns status/headers/body/latency.
- Verify: unit test against a local mock (e.g. `com.sun.net.httpserver.HttpServer`, which is JDK-bundled — no extra dependency) covering GET/POST with headers and body.

### Step 4 — Comparison engine
- Implement `JsonComparator.compare(expectedJson, actualJson, ignorePaths)` wrapping json-unit; returns pass/fail + diff text.
- Verify: unit tests for exact match, partial match (ignored volatile fields), and an intentional mismatch producing a readable diff.

### Step 5 — Suite runner + reporting
- Implement `TokenProviderResolver.resolve(TestSuite)`:
  - Find all TestCases with `tokenProvider == true`.
  - Execute each one immediately (via HttpRunner), before the rest of the suite.
  - Extract the token: via `tokenHeaderSource` (response header) if set, else via `tokenJsonPath` (JSONPath against response body using json-path).
  - Store the extracted value into `TestSuite.variables` (or a per-run context map) under `tokenVariableName`, so it resolves the same way any other `{{var}}` does.
  - If a token-provider call itself fails (non-2xx, or token not found at the given path), mark every dependent TestCase (those with matching `usesTokenFrom`) as a hard failure with a clear "token resolution failed" reason rather than letting them fail generically on a 401.
- Implement `SuiteRunner.run(TestSuite)`:
  1. Call `TokenProviderResolver.resolve(suite)` first.
  2. For remaining (non-token-provider) TestCases, in suite order: Substitutor -> HttpRunner -> JsonComparator -> TestResult.
  - A TestCase typically references the token via its own header value, e.g. `Authorization: Bearer {{authToken}}` — no special header-mapping code needed beyond normal `{{var}}` substitution, since the resolved token is just another suite variable by the time regular TestCases run.
- Implement `ReportWriter.writeHtml(List<TestResult>, Path)` and `.writeJson(...)`.
- Verify: build a suite with one `tokenProvider` TestCase hitting a mock Authman-style endpoint that returns `{ "access_token": "abc123" }`, and one dependent TestCase whose header is `Authorization: Bearer {{authToken}}`; confirm the dependent call actually receives `Bearer abc123` on the wire (assert on the mock server's received request), and confirm a broken token endpoint correctly fails dependents with a clear reason instead of a confusing 401.

### Step 6 — Swing GUI (minimum viable)
- `MainFrame`: menu bar (New/Open/Save Suite, Import Postman, Run Suite, Export Report).
- `SuiteEditorPanel`: table of TestCases with add/edit/delete; edit dialog exposes all `TestCase` fields (method/url/headers/body/expected/ignorePaths), plus:
  - a "This API generates a token (Authman)" checkbox, which when checked reveals `tokenJsonPath` / `tokenHeaderSource` and `tokenVariableName` fields instead of the usual expected-response fields.
  - for regular TestCases, a dropdown/multi-select of currently-defined `tokenVariableName`s from other TestCases in the suite, so the user can pick "uses token from: authToken" instead of hand-typing `{{authToken}}" into a header — the UI still writes it into the header value for them.
- `RunResultsPanel`: table of results (name, status pass/fail, body pass/fail, latency), row click shows diff detail in a text pane; token-provider rows are shown distinctly (e.g. "TOKEN" badge) and dependent-failure rows show the "token resolution failed" reason instead of a raw diff.
- Verify: manually build a 2-request suite in the GUI against a public test API (e.g. httpbin or a local mock server), run it, see results and a saved suite JSON file on disk.

### Step 7 — Postman import
- `PostmanCollectionImporter.parse(Path collectionJson)`:
  - Walk `item[]` recursively (folders vs requests) via Jackson `JsonNode` (loose parsing, not strict POJO — Postman schema varies by Postman version/export).
  - Map method, `url.raw`, headers (skip `disabled:true`), `body.raw` for JSON bodies, `auth` block.
  - If `item.response[]` present, seed `expectedBodyTemplate` from the first example's body.
  - Record folder path as `category`.
  - Collect any `{{var}}` found that isn't in the companion environment as an "unresolved variable" warning list, surfaced in the import dialog.
- `PostmanEnvironmentImporter.parse(Path environmentJson)`: reads `values[]` (key/value/enabled) into `TestSuite.variables`.
- `ImportDialog` in GUI: pick collection file (+ optional environment file) -> preview imported TestCase count/warnings -> append to current suite.
- Verify: export a real Postman collection you use today, import it, confirm generated TestCases look right in `SuiteEditorPanel`, fix any mismapped fields.

### Step 7b — Excel import + modify-only overrides (est. 3–4 days)
- pom: `poi-ooxml` 5.5.1, `log4j-to-slf4j` 2.24.3; shade: `ServicesResourceTransformer`, `module-info.class` excludes, `Multi-Release: true`.
- `ExcelParameterImporter` → `List<SheetParams>` implementing §3a.1–3a.2 (target cell, header detection, columns, cell rules, limits).
- `ParamPath`, `JsonTemplateCodec`, `TypeCoercer`, `PayloadOverrider` (modify existing + array append), `RequestPartOverrider` (query + header; path params need the spec → 7c).
- `ParameterOverrideService.dryRun/apply` without spec: existing params modified; missing params → ERROR `SPEC_UNAVAILABLE`.
- `TestCase.baseline` / `lastOverride`, `TestSuite.overrideOptions`; `SuiteRepository` round-trip incl. old suite files without the new fields.
- Verify: JUnit tests below (7b set) green; `java -jar api-regression.jar` + import `examples/sample_params.xlsx` from the shaded jar works with no log4j warning on stderr.

### Step 7c — Provider spec loader + gap-fill (est. 4–5 days)
- pom: `swagger-parser` 2.1.48; `jackson-bom` + bump `jackson.version` to 2.22.1; re-run all Step 1–7 tests.
- `ProviderSpecLoader` (local JSON/YAML, Swagger 2.0 + OAS 3.0/3.1, remote `$ref` blocked), `SpecIndex` (operationId / method + template, basePath/servers), `SchemaNavigator` (§3a.4), `ConstraintValidator` (§3a.5); path-param overrides in `RequestPartOverrider`.
- `Main --check-spec <file>` diagnostic.
- Verify: JUnit tests below (7c set) green against both Swagger 2.0 and OAS3 fixtures; `java -jar api-regression.jar --check-spec examples/sample_swagger2.json` prints the operation count **from the fat jar** (proves the ServiceLoader merge).

### Step 7d — GUI + override reporting (est. 2–3 days)
- `MainFrame` menu items; `ParameterImportDialog`; `OverridePreviewPanel` (dry run → Apply / Export); `TestCaseEditDialog` operationId + Revert / Promote baseline; PARTIAL OVERRIDE badge in `RunResultsPanel`; `ReportWriter.writeOverrideJson/Html`.
- Stretch: "Export parameter template" (one sheet per TestCase, current leaf values) — see Open Q 9.
- Verify: with the user's real provider spec + a real workbook: dry run shows expected MODIFIED/ADDED/ERROR counts, apply, save, reopen suite (overrides persisted), run suite against target API, export override report (JSON + HTML); re-import same workbook → suite JSON diff shows only `lastOverride.appliedAt`.

### Test plan — Steps 7b–7d (JUnit 5)
xlsx fixtures are built programmatically in `@TempDir` by a test-scope `XlsxFixtureBuilder` (POI) — no binary fixtures under `src/test`. Spec fixtures: `src/test/resources/spec/customers-oas3.yaml`, `customers-swagger2.json`, `customers-oas31.yaml` (same API; exercise `$ref`, `allOf`, `oneOf`+discriminator, a recursive schema, query/header/path params, a `readOnly` field, enum/pattern constraints).

| Test class | Cases |
|---|---|
| `ExcelParameterImporterTest` (7b) | blank vs `<null>` vs `<empty>`; integral numeric strips `.0`; ≥1e15 numeric → ERROR; date cell; boolean cell; formula cached value + cached error; text leading zeros kept; `A1=target` + metadata rows; header in any column order; unknown column warning; `enabled=N`; duplicate rows last-wins + warning; hidden sheet skipped; merged cells; row cap; `.xls` / encrypted rejected |
| `JsonTemplateCodecTest` (7b) | unquoted `{{var}}` round-trip; key order preserved; `10.50` preserved; duplicate keys → ERROR |
| `PayloadOverriderTest` (7b) | override existing scalar; nested object; array index modify / append / out-of-range ERROR; **untouched-fields invariance** (all non-target pointers equal before/after); type change with and without `type` column; JSON literal object/array; nothing deleted |
| `RequestPartOverriderTest` (7b/7c) | query replace keeps others' order; header case-insensitive match; path segment via template (literal, `{{var}}`, `:id`); add missing query/header/path param from spec |
| `SpecIndexTest` (7c) | Swagger 2.0, OAS 3.0, OAS 3.1 fixtures give identical answers; match by template, basePath/servers, operationId; ambiguous operation; `$ref` + `allOf` merge; `oneOf` with discriminator; recursive schema terminates |
| `GapFillTest` (7c) | add missing nested body field (intermediates created); `NEEDS_INDEX` vs absent-array create `[0]`; not-in-spec ERROR + did-you-mean; ambiguous leaf name lists candidates; ambiguous location; `readOnly` add → ERROR; `additionalProperties`; WARN vs STRICT; required-siblings warning; spec missing → modifications still applied |
| `ParameterOverrideServiceTest` (7b/7c) | unmatched sheet; ambiguous target; two sheets → same TestCase (CONFLICT); dry run never mutates suite; **round-trip idempotency** (apply twice → identical `url`/`headers`/`bodyTemplate`); changed workbook replaces prior overrides; apply blocked on errors unless `applyWithErrors` |
| `ReportWriterTest` (7d) | override JSON has per-sheet counts + entries; HTML contains summary table and error rows |

Sample files (for manual testing + handoff):
- `examples/sample_openapi.json` — OAS3: `POST /customers` (`Customer` = `allOf[Base, {address: $ref Address}]`), query `channel`, header `X-Request-Id`, `GET /customers/{id}`.
- `examples/sample_swagger2.json` — same API in Swagger 2.0 (`--check-spec` smoke test).
- `examples/sample_params.xlsx` — generated by `XlsxFixtureBuilder.main(...)` and committed; one sheet modifying existing fields, one adding a nested field + a query param from spec, one deliberate NOT_IN_SPEC row so the report shows an ERROR.
- `examples/sample_suite.json` — add `specPath: "sample_openapi.json"`.

### Step 8 — Polish / hardening
- Ignore-path heuristic helper: on import or manual add, offer a "guess volatile fields" button that flags keys matching `id`, `*At`, `*Date`, `timestamp`, `uuid` (case-insensitive) as suggested ignore paths.
- Config for request timeout, retry count (optional), and whether to run suite sequentially or in a small thread pool.
- Basic validation before run: flag TestCases with unresolved `{{var}}` placeholders instead of silently sending literal `{{var}}` strings.
- README with build/run instructions for handoff.

## 5. Out of Scope for Phase 1 (explicitly deferred)
- Postman pre-request/test JS scripts — not executed; any needed logic re-expressed manually in TestCase config.
- Dynamic Postman variables (`{{$timestamp}}`, `{{$guid}}`) — flagged as unresolved at import, not auto-generated (candidate for Phase 2).
- Parallel/load-style execution, CI integration (headless/CLI run mode, incl. a CLI wrapper around `ParameterOverrideService`) — candidates for Phase 2.
- Excel **parameter-override** import is now Phase 1 (§3a). Still Phase 2: CSV import, legacy `.xls`, remote spec URLs and remote `$ref`s, GraphQL / SOAP / WSDL specs, multi-environment value columns (DEV/UAT/PROD per row), data-driven iterations (one table generating many TestCases), creating a new TestCase from a sheet with no existing payload, deleting fields via the sheet, overriding expected responses or suite variables from Excel, password-protected workbooks.
- Auth flows requiring live OAuth2 grants (client-credentials, authorization-code with browser redirect, refresh-token rotation) — Phase 1's token support covers the common "call an endpoint, get a token back in body/header, reuse it" (Authman-style) pattern only; full OAuth2 dance is Phase 2.
- Token caching/expiry across multiple suite runs — Phase 1 re-fetches the token once per suite execution and holds it in-memory for that run only; persistent caching with TTL/refresh is Phase 2.

## 6. Definition of Done (Phase 1)
- `mvn clean package` produces one runnable jar.
- GUI can: create a suite by hand, import a Postman collection (+ optional environment) into a suite, save/load suite JSON, run the suite, view pass/fail + diffs per TestCase, export an HTML/JSON report.
- GUI can designate one or more TestCases as token-provider (Authman-style) APIs, and other TestCases can reference the resulting token in their headers; a suite run resolves tokens first, then runs the rest with the token correctly injected — verified against at least one real Authman-style endpoint.
- At least one real-world Postman collection from the user has been imported and successfully run end-to-end against its target API.
- Excel parameter override (§3a):
  - GUI can attach a local provider spec (Swagger 2.0 or OpenAPI 3.x, JSON or YAML) and import an `.xlsx` workbook; a dry-run report is always shown before apply.
  - Only listed parameters change; untouched-fields invariance and round-trip idempotency tests pass; no field is ever deleted.
  - Missing parameters are added from the spec at the correct body path / query / path / header with spec-correct type; undeclared, ambiguous or uncoercible parameters produce ERROR entries with a reason (and candidates where applicable), never a silent drop or a guess.
  - Unmatched / ambiguous / conflicting sheets are reported and not applied; spec missing or operation not found still applies modifications to existing params.
  - Override report exports to JSON and HTML with per-sheet MODIFIED / ADDED / SKIPPED / ERROR counts.
  - The shaded jar parses a Swagger 2.0 sample via `--check-spec` and reads `examples/sample_params.xlsx` (no ServiceLoader / logging regressions).
  - At least one real provider spec + user workbook has been applied to an imported Postman suite and the suite run end-to-end against its target API.

## 7. Open Questions (recommended defaults in bold — build proceeds on these unless told otherwise)
| # | Question | Recommended default |
|---|---|---|
| 1 | Which spec format/version does the provider publish (Swagger 2.0, OAS 3.0, 3.1)? Single file or split with relative `$ref`s? | **Support all via full `swagger-parser`.** If OAS3-only is confirmed, switch to `swagger-parser-v3` and save ~8 MB |
| 2 | Sheet → API mapping key | **`A1=target` with exact TestCase name, else sheet name; `operationId` and `METHOD /path` also accepted; no fuzzy match** |
| 3 | Same API used by several TestCases (positive/negative variants)? | **Target by exact TestCase name; one sheet per TestCase; `METHOD /path` matching several → AMBIGUOUS_TARGET** |
| 4 | May values reference `{{vars}}`? | **Yes, pass-through to `Substitutor`; constraint checks skipped for those cells** |
| 5 | Sheet with no matching TestCase / no payload — create a new TestCase from the spec? | **No: report UNMATCHED (creation is Phase 2)** |
| 6 | Constraint-violation policy | **WARN by default, STRICT toggle; uncoercible type always ERROR** |
| 7 | Ambiguity policy (same leaf name at several nested locations / in params and body) | **ERROR with candidate list; user supplies full path or `location`; never first-match** |
| 8 | Is `.xls` (Excel 97-2003) needed? | **No — `.xlsx` only; users Save As `.xlsx`** |
| 9 | Excel template download from the app (sheet per TestCase, prefilled current values)? | **Stretch goal in Step 7d, not in DoD** |
| 10 | Apply when some rows are ERROR? | **Blocked unless "apply despite errors" is ticked; TestCase gets PARTIAL OVERRIDE badge** |
| 11 | Re-import semantics: replace or stack on prior overrides? | **Replace — always re-apply from `baseline`** |
| 12 | Headless/CLI import in Phase 1? | **No — UI-free `ParameterOverrideService` only; CLI wrapper with Phase 2 headless runner** |
| 13 | Timezone convention for Excel date cells | **No zone emitted; zoned timestamps entered as Text cells** |
