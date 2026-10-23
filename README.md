# API Regression Tool — Phase 1

A lightweight, standalone desktop application for automated API regression testing, Postman collection migration, and dynamic Authman token provider workflows.

Built to run entirely on a standard office laptop with a JDK installed (no external dependencies, Node.js, Python, or database required).

---

## Key Features

1. **Standalone Runnable JAR**: Packaged as a single fat/shaded executable: `java -jar api-regression.jar`.
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
├── pom.xml                                   # Root Maven parent POM
├── api-regression.jar                         # Pre-built runnable shaded fat JAR
├── phase1plan.md                             # Phase 1 design specification
├── README.md                                 # Documentation & usage guide
├── api-regression/
│   ├── pom.xml                               # Module POM with shade configuration
│   └── src/
│       ├── main/java/com/sabyasachi/apiregression/
│       │   ├── Main.java                     # Application entry point (GUI & CLI)
│       │   ├── model/                        # Data models
│       │   │   ├── Auth.java                 # Bearer, Basic, ApiKey, None
│       │   │   ├── TestCase.java             # Individual API definition & token settings
│       │   │   ├── TestSuite.java            # Suite container, baseUrl, and variables
│       │   │   └── TestResult.java           # Pass/fail, latency, actual response, diff
│       │   ├── client/
│       │   │   ├── HttpRunner.java           # java.net.http execution engine
│       │   │   └── Substitutor.java          # {{var}} placeholder substitution
│       │   ├── compare/
│       │   │   ├── JsonComparator.java       # json-unit comparison wrapper
│       │   │   └── IgnorePathHeuristic.java  # Auto-detects volatile JSON fields
│       │   ├── store/
│       │   │   └── SuiteRepository.java      # JSON persistence for TestSuite files
│       │   ├── importer/
│       │   │   ├── PostmanCollectionImporter.java # Postman v2.1 recursive parser
│       │   │   └── PostmanEnvironmentImporter.java # Postman environment values parser
│       │   ├── runner/
│       │   │   ├── TokenProviderResolver.java # Resolves Authman token providers first
│       │   │   ├── SuiteRunner.java          # Suite execution orchestrator
│       │   │   └── ReportWriter.java         # Generates HTML and JSON test reports
│       │   └── ui/
│       │       ├── MainFrame.java            # Desktop main window & menu bar
│       │       ├── SuiteEditorPanel.java     # Suite variables & TestCase table editor
│       │       ├── TestCaseEditDialog.java   # Full request, header, auth, body & token dialog
│       │       ├── RunResultsPanel.java      # Execution progress & diff inspection panel
│       │       └── ImportDialog.java         # Postman collection & environment picker
│       └── test/java/com/sabyasachi/apiregression/ # Comprehensive unit & integration tests
│           ├── client/HttpRunnerTest.java
│           ├── client/SubstitutorTest.java
│           ├── compare/JsonComparatorTest.java
│           ├── importer/PostmanImporterTest.java
│           ├── runner/SuiteRunnerTest.java
│           └── store/SuiteRepositoryTest.java
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

The shaded fat JAR will be generated at:
- `api-regression/target/api-regression.jar` (and mirrored to `./api-regression.jar` in the root).

To run the full test suite:

```bash
mvn test
```

---

## Running the Application

### 1. Desktop GUI Mode
Launch the application window with:

```bash
java -jar api-regression.jar
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
You can also run suites directly in the terminal or validate specifications:

```bash
# Check version
java -jar api-regression.jar --version

# Validate an OpenAPI 3.x or Swagger 2.0 specification
java -jar api-regression.jar --check-spec examples/sample_openapi.json
java -jar api-regression.jar --check-spec examples/sample_swagger2.json

# Run a suite and export HTML + JSON reports
java -jar api-regression.jar --run examples/sample_suite.json --html target/demo_report.html --json target/demo_report.json
```

---

## Definition of Done Verification

- [x] `mvn clean package` produces one runnable fat JAR (`api-regression.jar`).
- [x] GUI can create a suite by hand, import Postman collections and environments, save/load suite JSON, run the suite, view pass/fail + diffs per TestCase, and export HTML/JSON reports.
- [x] GUI designates token-provider APIs, automatically extracts tokens from responses (via JSONPath or header), and injects them into dependent APIs.
- [x] Excel parameter override (§3a):
  - [x] GUI can attach a local provider spec (Swagger 2.0 or OpenAPI 3.x, JSON or YAML) and import an `.xlsx` workbook; dry run report always shown before apply.
  - [x] Only listed parameters change; untouched-fields invariance and round-trip idempotency tests pass; no field is ever deleted.
  - [x] Missing parameters are added from spec at the correct body path / query / path / header with spec-correct type; undeclared, ambiguous, or uncoercible parameters produce audit ERROR entries with candidate suggestions.
  - [x] Unmatched / ambiguous / conflicting sheets are reported and not applied; spec missing or operation not found still applies modifications to existing parameters.
  - [x] Override report exports to JSON and HTML with per-sheet counts.
  - [x] The shaded fat JAR parses Swagger 2.0 via `--check-spec` using ServiceLoader SPI discovery and reads Excel parameter workbooks without logging warnings.
- [x] Unit and integration tests verify all core engines, importers, coercers, navigators, and executors (37/37 tests passing).
