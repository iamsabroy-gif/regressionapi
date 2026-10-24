# imp.md - Implementation plan for reg.md (data-driven runs + headless)

Status: PLAN (no code changed). Date: 2026-10-25. Package root: `src/main/java/com/sabyasachi/apiregression/` (shortened to `R/` below).
Scope: reg.md work packages WP1 (sheet parser), WP2 (apply per run) and WP3 (headless commands). Golden capture, environments, history, flaky handling, JUnit XML and the GUI screens (reg.md WP4-WP9) are separate follow-ups and are not touched here.

Decisions already made (reg.md section 3.1): one sheet = one API (sheet name matches test case name, then operationId, or `target` in A1/B1); columns = parameters, values go down; runs = data rows; blank cell = leave the field as imported for that run (reported `SKIPPED`/`BLANK`); a short column only warns; a fully blank row is ignored; `<empty>`/`<null>` are real values.

---

## 1. The idea in one paragraph (why this is small)

The importer **pivots** a data-driven sheet into N ordinary `SheetParams` objects, one per run, where each run is just the familiar list of `ParamRow` (parameter name + this row's cell value). `ParameterOverrideService.process` already knows how to apply one `SheetParams` to one test case (match, baseline, spec gap-fill, coercion, constraints, per-row errors, commit). So the override engine is reused unchanged. The only new behaviour is: **each run is applied to its own copy of the test case**, and the copies are kept in the suite next to the original, which becomes a template that the runner skips.

## 2. Verified starting points (read in the current code)

| Fact | Where |
|---|---|
| Header search loop: scans rows 0..50 for a `parameter` column, treats `target`/`location` in A/B as sheet metadata, else warns "Header row with 'parameter' column was not found" | `R/importer/ExcelParameterImporter.java` `parseSheet` (~`:150-215`) |
| Rejected headers `op, path, param, action, operation` | same file `:79` |
| Per-cell reading, merged-cell and formula-cache handling, `CellKind` | `extractCellValue` (~`:333`) and `ParamRow.CellKind` |
| `SheetParams` holds sheet name, target, status/skipReason, rows, warnings; `ParamRow` holds row number, parameter, rawValue, cellKind, explicit type/location, enabled, warnings | `R/importer/SheetParams.java`, `R/importer/ParamRow.java` |
| Match order: test case name, then operationId (including one resolved from the spec), then `METHOD /path` | `R/payload/TargetMatcher.java` `match` |
| `process(...)` loop: per sheet -> match -> `CONFLICT` check with an identity set -> baseline -> per row apply on copy -> commit to `targetTc` | `R/payload/ParameterOverrideService.java` (~`:140-480`) |
| Blank cell already becomes `SKIPPED`/`BLANK`; `<empty>`/`<null>` markers already handled | same file, row loop |
| Runner skips token providers and runs the rest | `R/runner/SuiteRunner.java` (`regularCases` filter, ~`:75-80`) |
| `--run` option parsing and exit codes (0 / 1 / 2) | `R/Main.java` `runHeadlessSuite` |
| `TestCase` is `@JsonIgnoreProperties(ignoreUnknown = true)` and `NON_NULL` | `R/model/TestCase.java:6-8` |

Not yet verified (check first, step 0): the exact setters on `OverrideEntry`/`SheetSummary` for a run label, whether `TestCase.equals/hashCode` lists every field (add new fields if so), and how `OverridePreviewPanel` lists summaries.

---

## 3. Work items (in order)

### Step 0 - Confirm assumptions (0.25 d)
- Read `OverrideEntry`, `SheetSummary`, `TestCase.equals/hashCode`, `OverridePreviewPanel` constructor. Record any surprise at the top of this file before coding.
- Run `mvn -q test` once to have a green baseline (189 tests at last count).

### Step 1 - Model fields (0.25 d) - `R/model/TestCase.java`
Add three fields (all optional, omitted from JSON when null/false, old files stay valid):
- `boolean template` - true on the original test case after a data sheet expanded it; the runner skips it.
- `String derivedFrom` - on a generated run: the template's name.
- `String runLabel` - the run's label (`1`, `2`, ... or the `run` column text).
Add them to `equals/hashCode` only if those methods are value-based on all fields. No other model change.

### Step 2 - Parser: data-driven sheets (1.5 d) - `R/importer/ExcelParameterImporter.java` (+ small `R/importer/DataSheetPivot.java` if the method would pass about 60 lines)
1. **Detect.** In `parseSheet`, after the existing header scan, if no `parameter` column was found, try data-driven mode instead of warning. Header row = the first non-empty row after an optional `target` row (A1 `target`, B1 name; `location` meta row also kept). Reserved header names (case-insensitive): `run`, `expectedstatus`, `note`. Every other non-empty header cell is a parameter column (name trimmed; `body.`/`query.`/`header.`/`path.` prefixes allowed; explicit `type`/`location` columns do not exist in this layout, types come from spec or the cell).
2. **Guards (before reading cells):** at most 50 parameter columns, 1,000 data rows, header cells at most 128 characters; over the cap = whole sheet `NOT_APPLIED` with the existing `ROW_CAP_EXCEEDED`. Duplicate parameter column (case-insensitive) = whole sheet `NOT_APPLIED` with `DUPLICATE`. A header row with no parameter columns = existing "header not found" warning.
3. **Read data rows.** For each row below the header: read each parameter cell with the existing `extractCellValue` (so merged cells, formulas, errors, dates, and 15+ digit numbers behave exactly as today). A row whose parameter cells are all blank is a spacer: skipped and noted in the sheet warnings (row number).
4. **Pivot.** For each kept row create one `SheetParams` (`sheetName` = sheet name, `target` = the same target, `runIndex` = 1-based count of kept rows, `runLabel` = `run` cell text or the index, `runExpectedStatus` = `expectedStatus` cell if numeric). Its `rows` = one `ParamRow` per parameter column: parameter name, rawValue and `cellKind` from the cell, `row` = the Excel row number, blank cell = `rawValue null` + `CellKind.BLANK` (the service already turns this into `SKIPPED`/`BLANK`).
5. **Short-column warning.** After pivoting, for each parameter column with fewer non-blank values than the number of runs, add one sheet warning to the first run: `column 'accountNum': 2 of 3 runs have a value, 1 run uses the imported value (row 4)`. A column with zero values: warning `column 'x' has no values; ignored`.
6. **Return shape.** `parse` returns the existing `List<SheetParams>`; a data-driven sheet contributes N entries sharing the same `sheetName` and `target`. Add three nullable fields to `SheetParams`: `Integer runIndex`, `String runLabel`, `Integer runExpectedStatus`. A normal sheet leaves them null, so every existing test is unchanged.
- Keep `REJECTED_HEADERS` as is; `run`, `expectedStatus`, `note` are not in it.

### Step 3 - Apply per run (2 d) - `R/payload/ParameterOverrideService.java`
Change only the part of `process` between "match" and "commit":
1. **Match once per sheet, not per run.** Call `TargetMatcher.match` as today for the first run of a sheet; later runs of the same sheet reuse the result (cache by `sheetName`). `UNMATCHED`/`AMBIGUOUS_TARGET` produce one error entry for the sheet and skip all its runs (do not emit N copies).
2. **Conflict check by sheet, not by run.** Replace the identity set with an identity map `TestCase -> sheetName`. A second run from the same sheet is fine; a different sheet on the same template is `CONFLICT` as today.
3. **Template and derived copy.** For a run (`runIndex != null`):
   - `template = matched test case`; ensure its baseline exists (existing `promoteToBaseline` call).
   - On the **first run of the sheet in apply mode**: remove every existing case in the suite with `derivedFrom == template.getName()` (this is what makes re-import idempotent), set `template.template = true`.
   - Build `derived` = copy of the template (use `RequestSnapshot`/the existing copy path; do not share the headers map or the baseline object), `name = template.name + " [" + runLabel + "]"` (if two labels collide, append `#<n>`), `derivedFrom`, `runLabel`, `template=false`, `expectedStatus = runExpectedStatus` if given. Insert it right after the template (or after the previous derived case) to keep sheet order.
   - Run the **existing** row loop and commit step against `derived` instead of the matched case. The rest of the code (baseline reset, working copy, spec gap-fill, coercion, constraint policy, per-row `ERROR`, provenance, fingerprint) is untouched.
4. **Dry run** does everything except mutate the suite: derived copies exist only in memory for the report.
5. **Summaries and entries.** For a run, `SheetSummary` name = `<sheet> [<runLabel>]` and `targetTestCase` = derived name; add the sheet-level warnings (short column etc.) to the first run's summary only. `OverrideEntry.row` already carries the Excel row.
6. **Status.** A run with any `ERROR` entry follows the existing `applyWithErrors` rule (default: that run is not committed, others still are). Result: one bad value never blocks the other runs, as reg.md requires.
7. **Provenance** (`OverrideProvenance`) gets `sheet` = `Valid Customer [2]`; no schema change.

### Step 4 - Runner skips templates (0.25 d) - `R/runner/SuiteRunner.java`
- Add `&& !tc.isTemplate()` to the `regularCases` filter and exclude templates from `totalCount`. Token providers are never templates.
- GUI/report: show `[template]` next to the name in `SuiteEditorPanel` (one label change) so users are not surprised by a case that never runs.

### Step 5 - Headless commands (1 d) - `R/Main.java`
- `--run <suite> --params <workbook.xlsx> [--spec <file>] [--env ...]`: after loading the suite (and `setFilePath`), call `dryRun` then `apply` (in memory only, nothing saved), print the report table (sheet/run, parameter, action, reason), then run the suite. If any entry is `ERROR`, print the count and still run the remaining runs; exit code 2 if any run fails or any override entry is an error, 1 on tool errors, 0 otherwise (unchanged contract).
- `--apply-params <suite> <workbook.xlsx> [--spec <file>] [--dry-run] [--out <suite.json>]`: prints the same table; without `--dry-run` writes the expanded suite to `--out` (default: refuses to overwrite the input unless `--out` equals it explicitly). Exit 2 when any row is `ERROR`.
- Update `printHelp()` and README usage. Parsing stays in the existing style (simple loop over `args`); no CLI library.

### Step 6 - Sample, docs (0.5 d)
- `examples/mock_runs_params.xlsx`: sheet `Valid Customer` with columns `CustId`, `accountNum`, `SessionContext.userid` and 3 data rows, one blank cell, plus a one-column sheet `Bad CustId` with 3 ids. Generate it with a small Java generator in `src/test` (same style as `SampleParamsGenerator`) and check the xlsx in once.
- README: "Data-driven sheets" section (layout, blank rule, naming, limits, CLI examples). Update `reg.md` section 3.1 only if behaviour differs from what is written there.

---

## 4. Tests (written with each step, not after)

| Test class | Cases |
|---|---|
| `DataSheetParserTest` (Step 2) | header detection with and without a `target` row; reserved columns; 3 columns x 3 rows = 3 `SheetParams` with the right values; one column x 10 rows = 10; blank cell becomes `BLANK`; short column warns with column name and rows; column with no values warns; fully blank row ignored and not counted; duplicate column = sheet not applied; over row/column cap = not applied; `<empty>`/`<null>` kept as values; numeric 123456 typed as text stays a string; old `parameter` sheets parse exactly as before (reuse the existing importer test fixtures). |
| `ExpandRunsTest` (Step 3) | 10 values -> 10 derived cases named `[1]`..`[10]` with the right `CustId`; untouched fields identical across runs and to the baseline (`JsonPointerDiff`); blank cell leaves that field as imported in that run only; a bad value (`12345` with a 6-digit pattern, STRICT policy) -> that run ERROR, other runs applied; re-import twice -> same suite JSON (idempotent, no duplicate derived cases); re-import with fewer rows removes stale derived cases; template flagged and kept; two different sheets on one template -> `CONFLICT`; unmatched sheet -> one error entry, not N; dry run leaves the suite byte-identical. |
| `SheetToApiTest` (Step 3) | sheet name = test case name; = operationId; = operationId from the spec only; `target` in A1/B1 for a long name; no match; ambiguous. |
| `RunnerTemplateTest` (Step 4) | template is never executed; derived cases are; counts in progress and report exclude the template. |
| `HeadlessParamsTest` (Step 5) | in-process HTTP server (no external mock): `--run --params` with 3 runs gives 3 results and exit 0; one failing expected body -> exactly that run fails by name, exit 2; `--apply-params --dry-run` leaves the file unchanged; error row -> exit 2; missing workbook -> exit 1. |
| End to end (Step 6) | `examples/mock_runs_params.xlsx` + `examples/mock_suite.json` + `examples/mock_openapi.json` against an in-process copy of the mock's behaviour; all runs pass. |
| Regression | all 189 existing tests stay green and unchanged. |

## 5. Definition of done

- [ ] A sheet with 3 parameters x 10 rows runs as 10 named runs from the command line; one-column sheet with n values runs as n runs.
- [ ] Blank cell keeps the imported value for that run only, shown as `SKIPPED`/`BLANK`; short column and empty column produce one visible warning each; spacer row ignored.
- [ ] Re-importing the same workbook gives a byte-identical suite; re-importing with fewer rows leaves no stale runs.
- [ ] A bad value fails only its own run; the other runs execute; exit code 2.
- [ ] Old workbooks (one row per parameter) behave exactly as before (existing tests unchanged).
- [ ] Template cases never execute; GUI shows them as templates.
- [ ] `--run --params` and `--apply-params` work with the exit codes above; README updated; sample workbook checked in.
- [ ] `mvn -q test` green; fat jar runs the sample against `mock/mock_server.py`.

## 6. Risks and how they are handled

| Risk | Handling |
|---|---|
| Re-import creates duplicate or stale runs | Derived cases are removed by `derivedFrom` before regeneration; tests above. |
| Users edit a derived case by hand, then re-import | Existing `MANUAL_EDITS_DISCARDED` fingerprint warning applies per derived case; derived cases are regenerated from the template baseline. |
| A name collision between a derived case and a real test case | Derived name gets `#<n>` suffix; collision with a non-derived case is reported as a sheet warning. |
| Large sheets slow the preview | Caps (1,000 rows, 20,000 runs per workbook) checked before reading cells; dry run builds copies in memory only. |
| Forgotten value runs with the imported value | Visible per-column warning in the report; strict mode (`--strict-data`) is listed as a possible follow-up, not built now. |
| Template flag hides a test the user wanted to run | `[template]` label in the editor; report header says how many templates were skipped. |

## 7. Open points (defaults, change if you disagree)

| # | Point | Default |
|---|---|---|
| 1 | Run name format | `<test case> [<label>]`, label = `run` column text or 1, 2, 3 |
| 2 | Where derived cases appear | Right after their template, in sheet order |
| 3 | `--apply-params` overwriting the input suite | Refused unless `--out` names it explicitly |
| 4 | Row cap per sheet | 1,000 rows; 20,000 runs per workbook |
| 5 | `expectedStatus` column | Optional; blank = the test case's own expected status |

## 8. Effort

Step 0 0.25 d, Step 1 0.25 d, Step 2 1.5 d, Step 3 2 d, Step 4 0.25 d, Step 5 1 d, Step 6 0.5 d = about 6 days. After this, reg.md WP4 (golden capture) is the next package.
