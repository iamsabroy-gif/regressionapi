# reg.md - Robust regression setup

Status: PLAN (no code changed). Date: 2026-10-25. Package: `com.sabyasachi.apiregression` (`src/main/java/com/sabyasachi/apiregression/`).

Goal: run the same API checks again and again (many customer ids, several environments, in CI), and know quickly what changed since the last good run. Related plans: `specupdate.md` (Excel provider spec), and the workspace auto-save idea (suite, variables and spec kept between launches, with a "Clear Saved Data" item).

---

## 1. What "robust" means here

A regression setup is robust when it is:
1. **Repeatable:** same input, same verdict. No hand-edited expected values.
2. **Data-driven:** 10 or 1,000 customer ids come from one sheet, not 10 copies of a test case.
3. **Comparable over time:** each run is kept, and the report says what is new-broken, fixed, or slower than last time.
4. **Safe to automate:** a command-line run with a clear exit code and a CI-readable result file.
5. **Not flaky:** a failing test is retried and marked flaky before it is called broken.
6. **Safe with secrets:** tokens and keys never land in reports or shared files.

## 2. Where we are (checked in the code)

| Need | State today |
|---|---|
| Run a suite from the command line | `--run suite.json [--html] [--json] [--timeout --retries --threads]` exists (`Main.runHeadlessSuite`). |
| Exit code for CI | Exists: 2 when any test fails, 1 on a tool error, 0 on success (`Main.java`). |
| Token handling | Done: token provider runs first (`TokenProviderResolver`), `{{var}}` replaced in later requests. |
| Retries, timeouts, threads | Exist in `RunOptions` and `SuiteRunner`. Retries cover only 502/503/504 and IO errors on idempotent methods. |
| Volatile fields | `IgnorePathHeuristic` suggests paths to ignore (GUI only). |
| Excel overrides | Work, but **GUI only**, and **one sheet per test case**. Two sheets on one test case = CONFLICT. |
| Expected response | Typed by hand into `expectedBodyTemplate`. **There is no "record the current response as the golden one".** |
| Run history | None. Each run's results are shown once; `--json` writes one file you must name yourself. |
| Environments (dev/test/uat) | None. One `baseUrl` and one variables map per suite. |
| CI result format | HTML and JSON only. No JUnit XML. |
| Secret handling | Reports and suite files are redacted and written with 0600 (`Redactor`). Runtime token is not saved. |

The gaps are therefore: data-driven runs, golden capture, headless override apply, environments, history and compare, flaky handling, and JUnit XML.

## 3. Target design (smallest version of each piece)

### 3.1 Data-driven sheet (the "10 custids" case)  - the core change

One sheet = one API. **The sheet name is the API to execute. Each column = one parameter, each row below the header = one value of that parameter. The number of runs is the number of data rows. A parameter that is mentioned should have a value on every row; a blank cell means "leave this field as imported for that run".**

```
 Sheet name: Valid Customer        <- matches the API (see "Sheet to API" below)
 A: CustId  | B: accountNum | C: SessionContext.userid
 1: 100001  | 87654321     | U-01          <- run 1
 2: 100002  | 87654322     | U-02          <- run 2
 3: 100003  | 87654323     | U-03          <- run 3      => 3 runs
```

One parameter is fine: a sheet with only `CustId` and 10 values gives 10 runs.

**Sheet to API (matching):** the sheet name must equal the API in the imported JSON, using the matcher that exists today (`TargetMatcher`): first the test case `name`, then the `operationId` (from the test case or from the imported spec). Excel limits sheet names to 31 characters and forbids `/`, so for a longer name put `target` in A1 and the exact name in B1 and start the header on row 2 (same convention as the current importer). No match = `UNMATCHED`; two matches = `AMBIGUOUS_TARGET`; both already exist and fail only that sheet.

**Run count and blank cells (decided):**
- The number of runs `n` = the number of data rows that have at least one value. A row where every parameter cell is blank is a spacer: it is ignored (not a run) and noted in the report.
- A **blank cell** means "leave this field as imported for this run". The run still happens; that parameter is not touched in it. The report shows it as `SKIPPED` / `BLANK` ("Cell is blank; field left as imported"), the existing reason code, so nothing is silent.
- A column that has fewer values than the other columns is therefore valid: its remaining rows are blank and use the imported value. The sheet gets one warning per such column, for example `column 'accountNum': 2 of 3 runs have a value, 1 run uses the imported value (row 4)`, so a forgotten value is visible. A column with no values at all is reported as a warning and has no effect.
- To send an empty string or null, write the existing markers `<empty>` or `<null>`; a blank is never sent as a value.
- Duplicate column names in one sheet = error (same as duplicate parameters today).
- Optional reserved columns (not parameters, may have blanks): `run` (label), `expectedStatus` (blank = the test case's own expected status), `note`.

**Behaviour per run:**
- Every parameter column uses the existing parameter rules (dotted path, `body.`/`query.`/`header.`/`path.` prefix, type coercion, spec gap-fill, constraint policy). Reuse `ParameterOverrideService` row logic; do not write a second override engine. Only the listed parameters change; everything else stays as imported.
- Each row becomes a **TestRun**: a copy of the test case (from its baseline snapshot) with that row's values applied. Name: `<test case> [1]`, `[2]`, ... or `[<run label>]`. A failure points at the exact data row.
- A run whose values fail validation (type, constraint, `NOT_IN_SPEC`) is reported `ERROR` for that run only; the other runs still execute. Sheet-level problems (`UNMATCHED`, `AMBIGUOUS_TARGET`, duplicate column, row cap) stop the whole sheet.

**Compatibility:** the existing one-row-per-parameter layout (header has a `parameter` column) keeps working unchanged. A sheet is data-driven only when its header row has no `parameter` column and has at least one parameter column.

**Limits:** max 1,000 rows per sheet, 50 columns, 20,000 runs per workbook (reuse `ROW_CAP_EXCEEDED`).

**Not in v1:** formulas that depend on other rows, cross-sheet joins, random data generation, a cross product of parameters (list the combinations as rows), and a strict mode that rejects short columns (add `--strict-data` later only if forgotten values become a problem).

### 3.2 Golden (expected) capture - "Record" mode

- New run option `--record` (and a GUI "Record as expected" button on results): run the suite, and for every run that returns the expected status, save the response body as that run's golden file.
- Stored as JSON next to the suite: `<suite name>.golden/<test case>/<run label>.json`. Not inside the suite file, so the suite stays small and reviewable in git.
- Volatile fields: when recording, apply `IgnorePathHeuristic` and store the suggested ignore paths in the golden file; the user can edit them. Redact secrets with `Redactor` before writing (tokens must not be golden data).
- Compare order at run time: explicit `expectedBodyTemplate` wins; else golden file; else status-only check with a "NO_BASELINE" warning (never a silent pass).
- Updating a golden file is always an explicit action (`--record` on one test case or run). A normal run never rewrites goldens.

### 3.3 Environments

- `environments/<name>.json` = `{ "baseUrl": "...", "variables": { ... } }`. Run with `--env <name>`; the file overrides suite `baseUrl` and variables. Secrets are referenced as `${ENV_VAR}`, read from the process environment, never stored in the file.
- A golden file records which environment it came from; running against a different environment warns "golden was recorded on <env>".

### 3.4 Headless override apply

- New command: `--apply-params suite.json workbook.xlsx [--spec spec] [--dry-run] [--out applied.json]`. Calls the same `dryRun`/`apply` methods the GUI dialog uses (`ParameterOverrideService`), prints the report table, exit code 2 if any row is ERROR.
- With data-driven sheets the command expands runs in memory; with `--out` it writes the expanded suite for inspection.
- One-shot CI form: `--run suite.json --params workbook.xlsx` = apply in memory then run, so CI needs no GUI step.

### 3.5 Run history and compare

- Every `--run` writes `runs/<suite>/<yyyyMMdd-HHmmss>.json` (redacted, 0600): per run - name, status, pass/fail, latency, diff summary, golden id, environment, tool version, spec sha256, workbook sha256.
- `--compare` (or default after each run): compare with the previous run for the same suite and environment and print three lists: **newly failing**, **fixed**, **slower by more than X% (default 50%) and more than 200 ms**.
- Keep the last N runs (default 30) and delete older files. No database: JSON files, same as suites.
- `--run` exit code: 2 for any failing run that was passing before or has no history; add `--allow-known-failures <file>` later only if needed.

### 3.6 Flaky handling

- `--rerun-failed N` (default 1): a failed run is repeated N times; if it passes on a repeat it is reported `FLAKY` (counts as pass in the exit code, listed in the report and history). If it fails every time it is `FAIL`.
- Existing transient retries (502/503/504) stay as they are.

### 3.7 CI output

- `--junit report.xml`: one `<testcase>` per run, failures with the diff summary (redacted). Works with GitHub Actions, Jenkins, GitLab out of the box.
- Existing `--html` and `--json` stay. The HTML report gets a header block: environment, spec and workbook versions, counts (pass / fail / flaky / no-baseline), and failures first.

### 3.8 Reliability of the tool itself

- Per-run isolation: one data row failing (coercion error, timeout) never stops the rest; it is reported as `ERROR` on that run.
- Deterministic ordering: runs execute in sheet order; results are reported in that order even with several threads (already true for the thread pool; keep a test for it).
- Cap response bodies at 5 MB (done) and golden files at the same size.
- Mock server (`mock/mock_server.py`): keep it as the reference target for the sample suites; the tool's own tests must not depend on it (they use an in-process server).

## 4. Work packages (ordered)

| WP | Work | Depends on | Effort |
|---|---|---|---|
| WP1 | Data-driven sheet parser: new `importer/DataSheetParser` (header detection, run rows, reserved columns, limits, run counting, blank = keep-as-imported with per-column warnings) producing `RunSpec` objects. | none | 1.5 d |
| WP2 | Apply per row: extend `ParameterOverrideService` with `expandRuns(suite, workbook)` that reuses the row logic once per run on a copy of the baseline; run naming; per-run error isolation; report entries carry the run label. | WP1 | 2 d |
| WP3 | Headless commands: `--apply-params`, `--run ... --params`, `--dry-run`, `--out`; exit codes. | WP2 | 1 d |
| WP4 | Golden capture: `--record`, golden file format, redaction, compare order in `SuiteRunner`, `NO_BASELINE` warning. | WP2 | 2 d |
| WP5 | Environments: `--env`, `${ENV_VAR}` references, golden environment tag and warning. | none | 1 d |
| WP6 | History and compare: write `runs/...json`, `--compare`, retention, newly-failing / fixed / slower lists. | WP4 | 1.5 d |
| WP7 | Flaky handling: `--rerun-failed`, `FLAKY` status in results, report and history. | WP6 | 1 d |
| WP8 | CI output: `--junit`, HTML header block and failures-first ordering. | WP6, WP7 | 1 d |
| WP9 | GUI: data-driven import in the Import dialog (preview shows runs), "Record as expected", environment picker, history tab (list + compare). Do last; the CLI is the source of truth. | WP2-WP8 | 3 d |
| WP10 | Docs and samples: README "Regression setup" section; `examples/` data-driven workbook (10 ids) and a golden folder; CI example (GitHub Actions snippet). | all | 0.5 d |

Total about 14 days. Ship in this order for early value: WP1-WP3 (data-driven + headless) first, then WP4 (golden), then WP5-WP8.

## 5. Tests (named; one per behaviour)

- `DataSheetParserTest`: detects data-driven vs parameter sheet; reserved columns; duplicate header; row cap; hidden sheet; numeric ids stay strings when the cell is text.
- `RunCountAndBlankTest`: three columns with 3 values each = 3 runs; one column with n values = n runs; a blank cell leaves that field as imported in that run only (assert the other fields and other runs are unaffected, and the entry is `SKIPPED`/`BLANK`); a shorter column warns with the column name and rows; a fully blank row is ignored and not counted as a run; a column with no values warns; `<empty>`/`<null>` are sent as values; reserved columns may be blank.
- `SheetToApiTest`: sheet name matches test case name, then operationId (including one resolved from the imported spec); `target` in A1/B1 handles names over 31 characters; no match = `UNMATCHED`; two matches = `AMBIGUOUS_TARGET`.
- `ExpandRunsTest`: 10 values give 10 runs with the right `CustId`; untouched fields identical across runs (invariance helper `JsonPointerDiff`); a bad value (`12345`) gives an ERROR run and the other 9 still run; run names stable.
- `DataDrivenEndToEndTest`: in-process HTTP server + 10-row sheet + token provider; all runs pass; swap one expected value and exactly one run fails by name.
- `GoldenRecordTest`: record then replay passes; changing one response field fails only that run; secrets are redacted in the golden file; normal run never rewrites goldens.
- `EnvironmentTest`: `--env` overrides baseUrl and variables; `${ENV_VAR}` resolved; missing variable is a clear error; golden environment warning.
- `HistoryCompareTest`: newly failing / fixed / slower are reported; retention deletes old files; corrupt history file is skipped with a warning, never a crash.
- `FlakyTest`: fails once then passes = `FLAKY` and exit 0; fails always = `FAIL` and exit 2.
- `JunitReportTest`: valid XML (escape `<`, `&`, control characters), counts match, no secrets.
- `CliExitCodeTest`: 0, 1, 2 paths for `--run`, `--apply-params`, `--compare`.
- Existing suites (override, gap-fill, spec security, report redaction) stay green unchanged.

## 6. Security and limits

- Workbooks stay untrusted: reuse the importer's zip-bomb limits; cell text capped at 4,096 characters; no formulas evaluated (cached values only).
- A data row can name any parameter; the existing `NOT_IN_SPEC`, constraint policy, and body/query/header rules still apply, so a sheet cannot add fields the spec does not allow.
- Golden, history and report files are redacted and written 0600; environment files hold no secrets (only `${ENV_VAR}` references).
- Compare and history read files from the local folder only; no network, no `$ref` or path input from the workbook reaches the file system.

## 7. Definition of done

- [ ] A sheet whose parameters all have n values runs as n named runs from the command line and from the GUI (including the one-parameter case); a blank cell leaves that field as imported for that run only, with a visible warning per short column; the sample in `examples/` passes against `mock/mock_server.py`.
- [ ] `--run suite.json --params workbook.xlsx --env test --junit out.xml` works in one command; exit codes 0 / 1 / 2 as specified.
- [ ] Record then replay gives a clean pass; a changed response fails exactly the affected run.
- [ ] History shows newly failing / fixed / slower versus the previous run.
- [ ] No secrets in any report, history, golden or JUnit file (grep test over generated files).
- [ ] All tests in section 5 pass; `mvn -q test` green; fat jar runs the sample.
- [ ] README regression section and a CI example added.

## 8. Risks and what we skip on purpose

| Risk | Mitigation |
|---|---|
| Golden files go stale and hide real changes | Explicit record only; `NO_BASELINE` is a warning, not a pass; golden file stores date, environment, tool version. |
| Data sheets with thousands of rows are slow | Thread option already exists; row caps; per-run timeouts; show progress in CLI. |
| Flaky tests hide a real intermittent bug | `FLAKY` is listed in every report and history entry, never silent. |
| Environment mix-ups | Environment name printed in the report header and recorded in golden and history. |

Skipped on purpose (add only if asked): a database, scheduled runs inside the tool (use cron or CI), trend charts, random or generated test data, test-case dependencies beyond the token provider, parallel environments in one run.

## 9. Open questions (recommended defaults)

| # | Question | Default |
|---|---|---|
| Q1 | Layout. DECIDED: parameters as columns, values going down; one sheet drives one API. | - |
| Q9 | Blank cell. DECIDED: a blank cell leaves the field as imported for that run (reported as `SKIPPED`/`BLANK`), with a warning for each short column. | - |
| Q2 | Should expected values per row live in the sheet (e.g. `expect.$.Email` columns), or come from recorded goldens? | Goldens per run, plus the optional `expectedStatus` column. Add `expect.` columns later if needed. |
| Q3 | Which CI do you use (GitHub Actions, Jenkins, other)? | JUnit XML works for all; I will add a GitHub Actions example. |
| Q4 | Environments you need (dev / test / uat / prod)? Is prod ever allowed? | dev, test, uat. Refuse `prod` unless `--allow-prod` is given. |
| Q5 | How many runs per workbook do you expect? | Up to 1,000 per sheet; tell me if you need more. |
| Q6 | Retention for history? | Last 30 runs per suite and environment. |
| Q7 | Slow-down threshold for "slower than last run"? | 50% and at least 200 ms. |
| Q8 | Start with the command line only, GUI later? | Yes (WP1-WP8 first, WP9 last). |
