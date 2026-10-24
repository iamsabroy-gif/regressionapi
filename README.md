# API Regression Tool — Phase 1

A lightweight, standalone desktop application for automated API regression testing, Postman collection migration, and dynamic Authman token provider workflows.

Built to run entirely on a standard office laptop with a JDK installed (no external dependencies, Node.js, Python, or database required).

---

## Key Features

1. **Standalone Runnable JAR**: Packaged as a single fat/shaded executable: `java -jar target/api-regression.jar`.
2. **Native Desktop GUI**: Modern Java Swing interface with suite editor, token wiring, volatile path detector, and execution result diff viewer.
3. **Authman / Token-Provider Support**: Designate any API as a token generator. The tool executes it first, extracts the token (via JSONPath or response header), and injects it into dependent test cases (e.g. `Authorization: Bearer {{authToken}}`).
4. **Postman Collection & Environment Importer**: Recursively imports Postman Collections (v2.1 JSON) and companion environments, mapping folders to categories, query parameters, headers, authentication, raw JSON bodies, and sample responses.
5. **Structural JSON Diff & Ignore Paths**: Uses `json-unit` to perform deep JSON comparisons while ignoring volatile fields (`id`, `createdAt`, `uuid`, etc.) with one-click heuristic detection.
6. **Excel Parameter Overrides & Spec Gap-Fill**: Import `.xlsx` parameter workbooks where each sheet targets an API/TestCase. Only listed parameters are modified (untouched fields remain strictly invariant). Missing parameters are auto-discovered in the attached OpenAPI 3.x or Swagger 2.0 spec and inserted at their schema location with spec-correct types.
7. **Idempotency & Baseline Snapshots**: Overrides are always computed against an immutable `baseline` snapshot; re-importing the same workbook is completely idempotent, and a new workbook cleanly replaces prior overrides without stacking. Supports Revert/Promote to baseline.
8. **Detailed Reporting & Badges**: Exports rich HTML dashboards, JSON test results, and parameter override audit reports (`MODIFIED`, `ADDED`, `SKIPPED`, `ERROR`). Test cases with errors display a color-coded `PARTIAL OVERRIDE` badge in the UI.
9. **Headless / CLI Mode**: Execute any test suite from the terminal (`--run`) or diagnose specifications (`--check-spec <file>`).

---

## Tech Stack

| Component | Choice | Reason |
|---|---|---|
| **Language & Runtime** | Java 17+ | Standard JDK with built-in `java.net.http.HttpClient` |
| **Build System** | Maven (`maven-shade-plugin`) | Reproducible build; produces single fat JAR with cached dependencies |
| **GUI Framework** | Swing | Bundled with JDK; zero external UI installation |
| **JSON Serialization** | Jackson (`jackson-databind`, `jsr310`) | De-facto standard for JSON handling |
| **JSON Diff & Assertions** | `net.javacrumbs.json-unit:json-unit-core` | Deep structural comparison and path-based ignores |
| **JSONPath Extraction** | `com.jayway.jsonpath:json-path` | Pulls access tokens out of arbitrary response trees |

---

## Project Structure

```
ApiTesting/
├── pom.xml                                   # Project Maven POM with shade configuration
├── phase1plan.md                             # Phase 1 design specification
├── README.md                                 # Documentation & usage guide
├── src/
│   ├── main/java/com/sabyasachi/apiregression/
│   │   ├── Main.java                         # Application entry point (GUI & CLI)
│   │   ├── model/                            # Data models
│   │   │   ├── Auth.java                     # Bearer, Basic, ApiKey, None
│   │   │   ├── TestCase.java                 # Individual API definition & token settings
│   │   │   ├── TestSuite.java                # Suite container, baseUrl, and variables
│   │   │   └── TestResult.java               # Pass/fail, latency, actual response, diff
│   │   ├── client/
│   │   │   ├── HttpRunner.java               # java.net.http execution engine
│   │   │   └── Substitutor.java              # {{var}} placeholder substitution
│   │   ├── compare/
│   │   │   ├── JsonComparator.java           # json-unit comparison wrapper
│   │   │   └── IgnorePathHeuristic.java      # Auto-detects volatile JSON fields
│   │   ├── store/
│   │   │   └── SuiteRepository.java          # JSON persistence for TestSuite files
│   │   ├── importer/
│   │   │   ├── PostmanCollectionImporter.java # Postman v2.1 recursive parser
│   │   │   ├── PostmanEnvironmentImporter.java # Postman environment values parser
│   │   │   └── ExcelParameterImporter.java   # Excel .xlsx parameter sheet parser
│   │   ├── runner/
│   │   │   ├── TokenProviderResolver.java    # Resolves Authman token providers first
│   │   │   ├── SuiteRunner.java              # Suite execution orchestrator
│   │   │   └── ReportWriter.java             # Generates HTML and JSON test reports
│   │   └── ui/
│   │       ├── MainFrame.java                # Desktop main window & menu bar
│   │       ├── SuiteEditorPanel.java         # Suite variables & TestCase table editor
│   │       ├── TestCaseEditDialog.java       # Full request, header, auth, body & token dialog
│   │       ├── RunResultsPanel.java          # Execution progress & diff inspection panel
│   │       └── ImportDialog.java             # Postman collection & environment picker
│   └── test/java/com/sabyasachi/apiregression/ # Comprehensive unit & integration tests
│       ├── TraceabilityCheckTest.java        # Full traceability coverage (H1-H6, M1-M11, Low1-Low2, S1-S6)
│       ├── client/HttpRunnerTest.java
│       ├── client/SubstitutorTest.java
│       ├── compare/JsonComparatorTest.java
│       ├── importer/PostmanImporterTest.java
│       ├── runner/SuiteRunnerTest.java
│       └── store/SuiteRepositoryTest.java
└── examples/
    ├── sample_suite.json                     # Complete working demo suite
    ├── sample_postman_collection.json        # Sample Postman v2.1 collection
    └── sample_postman_environment.json       # Sample Postman companion environment
```

---

## Build Instructions

To clean and compile the project into the single runnable fat JAR:

```bash
mvn clean package
```

The shaded fat JAR will be generated in `target/`:
- `target/api-regression.jar`

To run the full test suite:

```bash
mvn test
```

---

## Running the Application

### 1. Desktop GUI Mode
Launch the application window with:

```bash
java -jar target/api-regression.jar
```

In the GUI:
- **File Menu**: Create a new suite, open an existing `.json` suite, save, or save as.
- **Import Menu**: Open **Import Postman Collection...**, select a v2.1 collection JSON and optional environment JSON to import all folders and endpoints into your suite.
- **Suite Editor Tab**:
  - Configure `Suite Name`, `Base URL`, and shared variables (e.g. `tenantId`, `version`).
  - Add, edit, duplicate, reorder, or delete TestCases.
  - Double-click any row to edit URLs, headers, auth, request bodies, expected status, and expected JSON bodies.
  - Click **Guess Volatile Fields** to automatically add fields like `id`, `createdAt`, `uuid`, or `timestamp` to the ignore list.
  - In the **Token Provider (Authman)** tab, check *"This API generates an authentication token"* and define the token variable name (e.g. `authToken`) and response JSONPath (e.g. `$.access_token`).
  - In dependent TestCases, select the required token and click *"Inject into Header"* to wire `Authorization: Bearer {{authToken}}`.
- **Run & Results Tab**:
  - Click **▶ Run Test Suite** to run all tests in the background with a live progress bar.
  - View individual status (`PASS` / `FAIL`), role (`TOKEN` / `STANDARD`), status code, and latency.
  - Select any test to view color-coded diff details, actual response body, response headers, and executed URL.
  - Click **Export HTML Report...** or **Export JSON Report...** to generate report files.

### 2. Headless CLI Mode
You can run suites directly in the terminal, configure execution options, or validate specifications:

```bash
# Check version
java -jar target/api-regression.jar --version

# Validate an OpenAPI 3.x, OpenAPI 3.1, or Swagger 2.0 specification
java -jar target/api-regression.jar --check-spec examples/sample_openapi.json
java -jar target/api-regression.jar --check-spec examples/sample_swagger2.json

# Run a suite and export HTML + JSON reports
java -jar target/api-regression.jar --run examples/sample_suite.json --html target/demo_report.html --json target/demo_report.json

# Run a suite with custom timeout, idempotent retries, and parallel worker threads
java -jar target/api-regression.jar --run examples/sample_suite.json --timeout 15 --retries 2 --threads 4
```

#### CLI Execution Options (Step 8)
- `--timeout <sec>`: HTTP socket and connection timeout (default: 30s).
- `--retries <n>`: Number of retries for idempotent HTTP methods (`GET, HEAD, PUT, DELETE, OPTIONS`) on network `IOException` or 502/503/504 gateway errors with exponential backoff (default: 0). Non-idempotent methods (`POST, PATCH`) are never retried.
- `--threads <n>`: Number of parallel execution threads (default: 1). Original test case ordering in results and reports is strictly preserved.

---

## Security Hardening (S1–S6)

The application incorporates multi-layer security protections across spec loading, HTTP execution, secret management, and Excel parsing:

1. **S1: Spec Loader Hardening (`RefGuard`)**:
   - Custom AST pre-scanner inspects all `$ref` pointers before parser resolution.
   - Prohibits remote URI schemes (`http:`, `https:`), cloud-metadata endpoints (`169.254.169.254`), and path traversal beyond the specification root directory.
   - Detects circular reference loops and limits `$ref` traversal depth to 20.
2. **S2 & S6: Secrets Redaction, Canary Protection, and Report Sanitization**:
   - Authorization headers (`Authorization`, `Proxy-Authorization`), tokens, and sensitive query parameters (`api_key`, `secret`, `token`, `password`, `access_token`) are automatically redacted in HTML and JSON test reports as well as serialized test results (`***REDACTED***`).
   - Token-provider response bodies are never retained in serialized results.
   - Postman secrets are converted to environment references (`{{env:var}}`) upon import.
   - Suite files are written atomically with restricted file permissions (`0600` on POSIX systems).
   - Contextual HTML escaping prevents XSS across all test names, URLs, headers, error messages, and diff views.
3. **S3: Safe HTTP Redirect Policy**:
   - Redirects are followed safely up to a maximum of 5 hops.
   - Sensitive headers (`Authorization`, `Cookie`, API keys) are stripped upon cross-origin redirects.
   - Strict protocol downgrade prevention: HTTPS-to-HTTP redirects are immediately refused.
4. **S4: Safe Regular Expression & ReDoS Guard**:
   - Safe regex engine applies a 100ms per-match budget and a 4,096-character input length cap.
   - ReDoS timeouts degrade gracefully to non-blocking schema validation warnings, never halting suite execution.
5. **S5: Zip-Bomb & POI Excel Hardening**:
   - Strict Apache POI inflate limits: max 100MB uncompressed size, 100:1 compression ratio cap.
   - Workbook caps: max 50 sheets, max 5,000 rows per sheet, max 50 columns per row.
   - Encrypted, password-protected, or unsupported legacy binary workbooks are rejected with explicit reason codes (`ENCRYPTED_WORKBOOK`, `WORKBOOK_UNSUPPORTED`).

---

## Known Limitations & Design Deviations

- **Strict Excel Column Contract**: Parameter sheets must follow the contract header names: `parameter`, `value`, `type`, `location`, `enabled`, `notes`. Legacy or non-standard headers (`op`, `path`, `param`, `action`) are rejected with `WORKBOOK_UNSUPPORTED` to eliminate ambiguity.
- **Null Value Semantics**: `<null>` is permitted only for JSON body fields that are explicitly declared nullable or optional in the specification. Null values are strictly forbidden for query, header, and path parameters (`NULL_NOT_ALLOWED_HERE`). Use `<empty>` to specify an empty string.
- **Spec Loader Resolution & Two-Pass Architecture**: Rather than relying solely on third-party parser settings, `ProviderSpecLoader` executes a strict pre-pass via `RefGuard` before invoking the Swagger/OpenAPI parser to ensure malicious remote `$ref` URIs, cloud-metadata IP addresses, path traversals, and recursion loops are rejected before any network socket is opened. The parser subsequently resolves the specification via `readLocation` to ensure correct relative base-URI resolution across directories. A time-of-check to time-of-use (TOCTOU) window between the guard scan and parser read is an accepted low-risk trade-off for a local, desktop application where the user selects their own workspace specifications; as defense-in-depth, modification timestamps of all scanned files are verified post-parse.

---

## Definition of Done Verification

- [x] `mvn clean package` produces one runnable fat JAR in `target/api-regression.jar`.
- [x] Single root `pom.xml` build; no stale binary JARs committed in the repository tree.
- [x] GUI can create a suite by hand, import Postman collections and environments, save/load suite JSON, run the suite, view pass/fail + diffs per TestCase, and export HTML/JSON reports.
- [x] GUI designates token-provider APIs, automatically extracts tokens from responses (via JSONPath or header), and injects them into dependent APIs.
- [x] Excel parameter override (§3a):
  - [x] GUI can attach a local provider spec (Swagger 2.0, OpenAPI 3.0, OpenAPI 3.1; JSON or YAML) and import an `.xlsx` workbook; dry run report always shown before apply.
  - [x] Only listed parameters change; untouched-fields invariance and round-trip byte idempotency verified; no field is ever deleted.
  - [x] Missing parameters are added from spec at the correct body path / query / path / header with spec-correct type; undeclared, ambiguous, or uncoercible parameters produce audit ERROR entries with candidate suggestions.
  - [x] Unmatched / ambiguous / conflicting sheets are reported and not applied; spec missing or operation not found still applies modifications to existing parameters.
  - [x] Override report exports to JSON and HTML with per-sheet counts, action ranking, and spec messages.
  - [x] Revert to Baseline and Promote to Baseline cleanly clear override provenance and manual edit warnings.
  - [x] The shaded fat JAR parses Swagger 2.0 via `--check-spec` using ServiceLoader SPI discovery and reads Excel parameter workbooks without logging warnings or Log4j noise.
- [x] Automated test suite: **189/189 tests passing** with 100% pass rate across unit, integration, security, and full traceability matrix (H1–H6, M1–M11, Low1–Low2, S1–S6).

