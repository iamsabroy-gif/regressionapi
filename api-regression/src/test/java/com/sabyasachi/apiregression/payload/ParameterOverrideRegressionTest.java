package com.sabyasachi.apiregression.payload;

import com.fasterxml.jackson.databind.JsonNode;
import com.sabyasachi.apiregression.model.TestCase;
import com.sabyasachi.apiregression.model.TestSuite;
import com.sabyasachi.apiregression.testsupport.JsonPointerDiff;
import com.sabyasachi.apiregression.testsupport.XlsxFixtureBuilder;
import org.apache.poi.ss.usermodel.FormulaError;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

public class ParameterOverrideRegressionTest {

    private ParameterOverrideService service;
    private Path oas3Spec;

    @BeforeEach
    void setUp() {
        service = new ParameterOverrideService();
        oas3Spec = Path.of("src/test/resources/spec/customers-oas3.yaml");
    }

    // ==========================================
    // H1: Undeclared fields get added
    // ==========================================

    @Test
    void h1_explicitPathNotInPayloadNotInSpec_isNotInSpec(@TempDir Path tempDir) throws Exception {
        TestCase tc = new TestCase();
        tc.setName("Create Customer");
        tc.setMethod("POST");
        tc.setUrl("https://api.example.com/customers");
        tc.setBodyTemplate("{\"name\": \"Alice\"}");

        TestSuite suite = new TestSuite();
        suite.setTestCases(new ArrayList<>(List.of(tc)));

        Path xlsx = tempDir.resolve("h1.xlsx");
        XlsxFixtureBuilder.create()
                .sheet("Create Customer")
                .target("POST /customers")
                .row("$.bogus.field", "value")
                .build(xlsx);

        OverrideReport report = service.apply(suite, xlsx, oas3Spec, new OverrideOptions());
        assertEquals(1, report.getTotalErrors());
        OverrideEntry entry = report.getEntries().get(0);
        assertEquals(OverrideAction.ERROR, entry.getAction());
        assertEquals(ReasonCode.NOT_IN_SPEC, entry.getReasonCodeEnum());
        // Body unchanged
        assertEquals("{\"name\": \"Alice\"}", tc.getBodyTemplate());
    }

    @Test
    void h1_noSpec_explicitMissingPath_isSpecUnavailable(@TempDir Path tempDir) throws Exception {
        TestCase tc = new TestCase();
        tc.setName("Create Customer");
        tc.setMethod("POST");
        tc.setUrl("https://api.example.com/customers");
        tc.setBodyTemplate("{\"name\": \"Alice\"}");

        TestSuite suite = new TestSuite();
        suite.setTestCases(new ArrayList<>(List.of(tc)));

        Path xlsx = tempDir.resolve("h1_nospec.xlsx");
        XlsxFixtureBuilder.create()
                .sheet("Create Customer")
                .target("POST /customers")
                .row("$.address.city", "Wonderland")
                .build(xlsx);

        OverrideReport report = service.apply(suite, xlsx, null, new OverrideOptions());
        assertEquals(1, report.getTotalErrors());
        OverrideEntry entry = report.getEntries().get(0);
        assertEquals(OverrideAction.ERROR, entry.getAction());
        assertEquals(ReasonCode.SPEC_UNAVAILABLE, entry.getReasonCodeEnum());
    }

    @Test
    void h1_typoGetsDidYouMean(@TempDir Path tempDir) throws Exception {
        TestCase tc = new TestCase();
        tc.setName("Create Customer");
        tc.setMethod("POST");
        tc.setUrl("https://api.example.com/customers");
        tc.setBodyTemplate("{\"name\": \"Alice\"}");

        TestSuite suite = new TestSuite();
        suite.setTestCases(new ArrayList<>(List.of(tc)));

        Path xlsx = tempDir.resolve("h1_typo.xlsx");
        XlsxFixtureBuilder.create()
                .sheet("Create Customer")
                .target("POST /customers")
                .row("$.adress.city", "Wonderland")
                .build(xlsx);

        OverrideReport report = service.apply(suite, xlsx, oas3Spec, new OverrideOptions());
        assertEquals(1, report.getTotalErrors());
        OverrideEntry entry = report.getEntries().get(0);
        assertEquals(ReasonCode.NOT_IN_SPEC, entry.getReasonCodeEnum());
        assertTrue(entry.getCandidates().contains("address"), "Expected candidates to suggest 'address' for typo 'adress'");
    }

    // ==========================================
    // H2: Identity conflict set
    // ==========================================

    @Test
    void h2_twoSheetsSameTestCase_applyMode_secondIsConflict(@TempDir Path tempDir) throws Exception {
        TestCase tc = new TestCase();
        tc.setName("Create Customer");
        tc.setMethod("POST");
        tc.setUrl("https://api.example.com/customers");
        tc.setBodyTemplate("{\"name\": \"Alice\", \"code\": \"US1234\"}");

        TestSuite suite = new TestSuite();
        suite.setTestCases(new ArrayList<>(List.of(tc)));

        Path xlsx = tempDir.resolve("h2.xlsx");
        XlsxFixtureBuilder.create()
                .sheet("Sheet1")
                .target("Create Customer")
                .row("$.name", "Bob")
                .and()
                .sheet("Sheet2")
                .target("Create Customer")
                .row("$.code", "CA9999")
                .build(xlsx);

        OverrideReport report = service.apply(suite, xlsx, oas3Spec, new OverrideOptions());
        assertEquals(2, report.getSheets().size());
        assertEquals("APPLIED", report.getSheets().get(0).getStatus());
        assertEquals("CONFLICT", report.getSheets().get(1).getStatus());

        // First sheet applied, second sheet not applied
        assertTrue(tc.getBodyTemplate().contains("Bob"));
        assertTrue(tc.getBodyTemplate().contains("US1234"));
    }

    @Test
    void h2_dryRunAndApplyAgree(@TempDir Path tempDir) throws Exception {
        TestCase tc = new TestCase();
        tc.setName("Create Customer");
        tc.setMethod("POST");
        tc.setUrl("https://api.example.com/customers");
        tc.setBodyTemplate("{\"name\": \"Alice\"}");

        TestSuite suite = new TestSuite();
        suite.setTestCases(new ArrayList<>(List.of(tc)));

        Path xlsx = tempDir.resolve("h2_agree.xlsx");
        XlsxFixtureBuilder.create()
                .sheet("Sheet1")
                .target("Create Customer")
                .row("$.name", "Bob")
                .and()
                .sheet("Sheet2")
                .target("Create Customer")
                .row("$.name", "Charlie")
                .build(xlsx);

        OverrideReport dryReport = service.dryRun(suite, xlsx, oas3Spec, new OverrideOptions());
        OverrideReport applyReport = service.apply(suite, xlsx, oas3Spec, new OverrideOptions());

        assertEquals(dryReport.getTotalErrors(), applyReport.getTotalErrors());
        assertEquals(dryReport.getSheets().get(0).getStatus(), applyReport.getSheets().get(0).getStatus());
        assertEquals(dryReport.getSheets().get(1).getStatus(), applyReport.getSheets().get(1).getStatus());
    }

    @Test
    void h2_distinctTestCasesEqualByValue_notConflict(@TempDir Path tempDir) throws Exception {
        TestCase tc1 = new TestCase();
        tc1.setName("Same Name");
        tc1.setMethod("POST");
        tc1.setUrl("https://api.example.com/customers");
        tc1.setBodyTemplate("{\"name\": \"Alice\"}");

        TestCase tc2 = new TestCase();
        tc2.setName("Same Name");
        tc2.setMethod("POST");
        tc2.setUrl("https://api.example.com/customers");
        tc2.setBodyTemplate("{\"name\": \"Alice\"}");

        TestSuite suite = new TestSuite();
        suite.setTestCases(new ArrayList<>(List.of(tc1, tc2)));

        // Ambiguous target because two test cases share the same name
        Path xlsx = tempDir.resolve("h2_same.xlsx");
        XlsxFixtureBuilder.create()
                .sheet("Sheet1")
                .target("Same Name")
                .row("$.name", "Bob")
                .build(xlsx);

        OverrideReport report = service.apply(suite, xlsx, oas3Spec, new OverrideOptions());
        assertEquals("AMBIGUOUS_TARGET", report.getSheets().get(0).getStatus());
    }

    // ==========================================
    // H3: Spreadsheet junk written as real data
    // ==========================================

    @Test
    void h3_cachedFormulaError_isError(@TempDir Path tempDir) throws Exception {
        TestCase tc = new TestCase();
        tc.setName("Create Customer");
        tc.setMethod("POST");
        tc.setUrl("https://api.example.com/customers");
        tc.setBodyTemplate("{\"name\": \"Alice\"}");

        TestSuite suite = new TestSuite();
        suite.setTestCases(new ArrayList<>(List.of(tc)));

        Path xlsx = tempDir.resolve("h3_error.xlsx");
        XlsxFixtureBuilder.create()
                .sheet("Create Customer")
                .target("POST /customers")
                .formulaError("$.name", "1/0", FormulaError.DIV0)
                .build(xlsx);

        OverrideReport report = service.apply(suite, xlsx, oas3Spec, new OverrideOptions());
        assertEquals(1, report.getTotalErrors());
        assertEquals(ReasonCode.CELL_ERROR, report.getEntries().get(0).getReasonCodeEnum());
        assertEquals("{\"name\": \"Alice\"}", tc.getBodyTemplate());
    }

    @Test
    void h3_formulaWithoutCachedValue_isError_notZero(@TempDir Path tempDir) throws Exception {
        TestCase tc = new TestCase();
        tc.setName("Create Customer");
        tc.setMethod("POST");
        tc.setUrl("https://api.example.com/customers");
        tc.setBodyTemplate("{\"name\": \"Alice\"}");

        TestSuite suite = new TestSuite();
        suite.setTestCases(new ArrayList<>(List.of(tc)));

        Path xlsx = tempDir.resolve("h3_nocache.xlsx");
        XlsxFixtureBuilder.create()
                .sheet("Create Customer")
                .target("POST /customers")
                .formulaNoCache("$.name", "CONCAT(\"A\",\"B\")")
                .build(xlsx);

        OverrideReport report = service.apply(suite, xlsx, oas3Spec, new OverrideOptions());
        assertEquals(1, report.getTotalErrors());
        assertEquals(ReasonCode.FORMULA_NO_CACHE, report.getEntries().get(0).getReasonCodeEnum());
        assertEquals("{\"name\": \"Alice\"}", tc.getBodyTemplate());
    }

    @Test
    void h3_numberAtOrAbove1e15_isError(@TempDir Path tempDir) throws Exception {
        TestCase tc = new TestCase();
        tc.setName("Create Customer");
        tc.setMethod("POST");
        tc.setUrl("https://api.example.com/customers");
        tc.setBodyTemplate("{\"name\": \"Alice\"}");

        TestSuite suite = new TestSuite();
        suite.setTestCases(new ArrayList<>(List.of(tc)));

        Path xlsx = tempDir.resolve("h3_too_precise.xlsx");
        XlsxFixtureBuilder.create()
                .sheet("Create Customer")
                .target("POST /customers")
                .number("$.name", 1234567890123456789.0)
                .build(xlsx);

        OverrideReport report = service.apply(suite, xlsx, oas3Spec, new OverrideOptions());
        assertEquals(1, report.getTotalErrors());
        assertEquals(ReasonCode.NUMBER_PRECISION_LOSS, report.getEntries().get(0).getReasonCodeEnum());
    }

    @Test
    void h3_textCellPan_keptVerbatim(@TempDir Path tempDir) throws Exception {
        TestCase tc = new TestCase();
        tc.setName("Create Customer");
        tc.setMethod("POST");
        tc.setUrl("https://api.example.com/customers");
        tc.setBodyTemplate("{\"name\": \"Alice\"}");

        TestSuite suite = new TestSuite();
        suite.setTestCases(new ArrayList<>(List.of(tc)));

        Path xlsx = tempDir.resolve("h3_text_pan.xlsx");
        XlsxFixtureBuilder.create()
                .sheet("Create Customer")
                .target("POST /customers")
                .text("$.name", "1234567890123456")
                .build(xlsx);

        OverrideReport report = service.apply(suite, xlsx, oas3Spec, new OverrideOptions());
        assertEquals(0, report.getTotalErrors());
        assertTrue(tc.getBodyTemplate().contains("\"1234567890123456\""));
    }

    // ==========================================
    // H4: Query/header/path rules
    // ==========================================

    @Test
    void h4_emptySentinel_query_writesEmptyValue(@TempDir Path tempDir) throws Exception {
        TestCase tc = new TestCase();
        tc.setName("Get Customers");
        tc.setMethod("GET");
        tc.setUrl("https://api.example.com/customers?channel=web");

        TestSuite suite = new TestSuite();
        suite.setTestCases(new ArrayList<>(List.of(tc)));

        Path xlsx = tempDir.resolve("h4_empty.xlsx");
        XlsxFixtureBuilder.create()
                .sheet("Get Customers")
                .target("GET /customers")
                .row("query.channel", "<empty>")
                .build(xlsx);

        OverrideReport report = service.apply(suite, xlsx, oas3Spec, new OverrideOptions());
        assertEquals(0, report.getTotalErrors());
        assertEquals("https://api.example.com/customers?channel=", tc.getUrl());
    }

    @Test
    void h4_nullSentinelHeader_isError(@TempDir Path tempDir) throws Exception {
        TestCase tc = new TestCase();
        tc.setName("Get Customer By Id");
        tc.setMethod("GET");
        tc.setUrl("https://api.example.com/customers/123");

        TestSuite suite = new TestSuite();
        suite.setTestCases(new ArrayList<>(List.of(tc)));

        Path xlsx = tempDir.resolve("h4_null_header.xlsx");
        XlsxFixtureBuilder.create()
                .sheet("Get Customer By Id")
                .target("GET /customers/{id}")
                .row("header.X-Request-Id", "<null>")
                .build(xlsx);

        OverrideReport report = service.apply(suite, xlsx, oas3Spec, new OverrideOptions());
        assertEquals(1, report.getTotalErrors());
        assertEquals(ReasonCode.NULL_NOT_ALLOWED_HERE, report.getEntries().get(0).getReasonCodeEnum());
    }

    @Test
    void h4_queryValueEncoded() {
        RequestPartOverrider.OverridePartResult res = RequestPartOverrider.overrideQueryParam(
                "https://api.example.com/search", "q", "a b&c=d");
        assertEquals("https://api.example.com/search?q=a%20b%26c%3Dd", res.getUpdatedUrl());
    }

    @Test
    void h4_pathValueEncoded() {
        RequestPartOverrider.OverridePartResult res = RequestPartOverrider.overridePathParam(
                "https://api.example.com/files/test", "/files/{filename}", "filename", "a/b");
        assertEquals("https://api.example.com/files/a%2Fb", res.getUpdatedUrl());
    }

    @Test
    void h4_headerCrlf_rejected() {
        Map<String, String> headers = new HashMap<>();
        OverrideException ex = assertThrows(OverrideException.class, () -> {
            RequestPartOverrider.overrideHeader(headers, "X-Auth", "token\r\nInjected: yes");
        });
        assertEquals(ReasonCode.HEADER_INVALID_VALUE, ex.getReasonCode());
    }

    @Test
    void h4_placeholderQueryValueUntouched() {
        RequestPartOverrider.OverridePartResult res = RequestPartOverrider.overrideQueryParam(
                "https://api.example.com/search", "q", "prefix_{{var}}_suffix");
        assertEquals("https://api.example.com/search?q=prefix_{{var}}_suffix", res.getUpdatedUrl());
    }

    // ==========================================
    // H5: Body handling on non-body sheets
    // ==========================================

    @Test
    void h5_getQuerySheet_bodyStaysNull(@TempDir Path tempDir) throws Exception {
        TestCase tc = new TestCase();
        tc.setName("Get Customers");
        tc.setMethod("GET");
        tc.setUrl("https://api.example.com/customers");
        tc.setBodyTemplate(null);

        TestSuite suite = new TestSuite();
        suite.setTestCases(new ArrayList<>(List.of(tc)));

        Path xlsx = tempDir.resolve("h5_get_null.xlsx");
        XlsxFixtureBuilder.create()
                .sheet("Get Customers")
                .target("GET /customers")
                .row("query.channel", "mobile")
                .build(xlsx);

        OverrideReport report = service.apply(suite, xlsx, oas3Spec, new OverrideOptions());
        assertEquals(0, report.getTotalErrors());
        assertNull(tc.getBodyTemplate(), "GET request body must remain null when only query params are modified");
    }

    @Test
    void h5_nonJsonBody_queryRowStillApplied_bodyRowErrors(@TempDir Path tempDir) throws Exception {
        TestCase tc = new TestCase();
        tc.setName("Form Submit");
        tc.setMethod("POST");
        tc.setUrl("https://api.example.com/customers");
        tc.setBodyTemplate("username=admin&password=secret"); // Form-encoded, not JSON

        TestSuite suite = new TestSuite();
        suite.setTestCases(new ArrayList<>(List.of(tc)));

        Path xlsx = tempDir.resolve("h5_form.xlsx");
        XlsxFixtureBuilder.create()
                .sheet("Form Submit")
                .target("POST /customers")
                .row("query.channel", "web")
                .row("body.name", "Admin")
                .build(xlsx);

        OverrideOptions options = new OverrideOptions();
        options.setApplyWithErrors(true);
        OverrideReport report = service.apply(suite, xlsx, oas3Spec, options);
        assertEquals(1, report.getTotalErrors());
        assertEquals(ReasonCode.BODY_NOT_JSON, report.getEntries().get(1).getReasonCodeEnum());
        assertEquals(OverrideAction.ADDED, report.getEntries().get(0).getAction());
        assertEquals("https://api.example.com/customers?channel=web", tc.getUrl());
    }

    @Test
    void h5_bodyRowOnGet_isBodyNotApplicable(@TempDir Path tempDir) throws Exception {
        TestCase tc = new TestCase();
        tc.setName("Get Customers");
        tc.setMethod("GET");
        tc.setUrl("https://api.example.com/customers");
        tc.setBodyTemplate(null);

        TestSuite suite = new TestSuite();
        suite.setTestCases(new ArrayList<>(List.of(tc)));

        Path xlsx = tempDir.resolve("h5_get_body.xlsx");
        XlsxFixtureBuilder.create()
                .sheet("Get Customers")
                .target("GET /customers")
                .row("body.name", "Admin")
                .build(xlsx);

        OverrideReport report = service.apply(suite, xlsx, oas3Spec, new OverrideOptions());
        assertEquals(1, report.getTotalErrors());
        assertEquals(ReasonCode.BODY_NOT_APPLICABLE, report.getEntries().get(0).getReasonCodeEnum());
    }

    @Test
    void h5_arrayRootedBody_modifyIndexZero(@TempDir Path tempDir) throws Exception {
        TestCase tc = new TestCase();
        tc.setName("Batch Update");
        tc.setMethod("PUT");
        tc.setUrl("https://api.example.com/items");
        tc.setBodyTemplate("[{\"id\": 1, \"name\": \"first\"}, {\"id\": 2, \"name\": \"second\"}]");

        TestSuite suite = new TestSuite();
        suite.setTestCases(new ArrayList<>(List.of(tc)));

        Path xlsx = tempDir.resolve("h5_array_root.xlsx");
        XlsxFixtureBuilder.create()
                .sheet("Batch Update")
                .target("Batch Update")
                .row("$[0].name", "updated-first")
                .build(xlsx);

        OverrideReport report = service.apply(suite, xlsx, null, new OverrideOptions());
        assertEquals(0, report.getTotalErrors());
        assertTrue(tc.getBodyTemplate().contains("updated-first"));
        assertTrue(tc.getBodyTemplate().contains("second"));
    }

    // ==========================================
    // H6: Failed row leaves partial changes
    // ==========================================

    @Test
    void h6_failedRowLeavesBodyUnchanged(@TempDir Path tempDir) throws Exception {
        TestCase tc = new TestCase();
        tc.setName("Create Customer");
        tc.setMethod("POST");
        tc.setUrl("https://api.example.com/customers");
        tc.setBodyTemplate("{\"name\": \"Alice\"}");

        TestSuite suite = new TestSuite();
        suite.setTestCases(new ArrayList<>(List.of(tc)));

        Path xlsx = tempDir.resolve("h6_fail.xlsx");
        XlsxFixtureBuilder.create()
                .sheet("Create Customer")
                .target("POST /customers")
                .row("$.a.b[3].c", "value") // array out of bounds
                .build(xlsx);

        OverrideReport report = service.apply(suite, xlsx, oas3Spec, new OverrideOptions());
        assertEquals(1, report.getTotalErrors());
        assertEquals("{\"name\": \"Alice\"}", tc.getBodyTemplate());
    }

    @Test
    void h6_failedRowDoesNotAffectLaterRows(@TempDir Path tempDir) throws Exception {
        TestCase tc = new TestCase();
        tc.setName("Create Customer");
        tc.setMethod("POST");
        tc.setUrl("https://api.example.com/customers");
        tc.setBodyTemplate("{\"name\": \"Alice\", \"code\": \"US1234\"}");

        TestSuite suite = new TestSuite();
        suite.setTestCases(new ArrayList<>(List.of(tc)));

        Path xlsx = tempDir.resolve("h6_multi.xlsx");
        XlsxFixtureBuilder.create()
                .sheet("Create Customer")
                .target("POST /customers")
                .row("$.invalid[5]", "value") // Row 1 fails
                .row("$.code", "CA9999")      // Row 2 succeeds
                .build(xlsx);

        OverrideOptions options = new OverrideOptions();
        options.setApplyWithErrors(true);
        OverrideReport report = service.apply(suite, xlsx, oas3Spec, options);

        assertEquals(1, report.getTotalErrors());
        assertEquals(1, report.getTotalModified());
        assertTrue(tc.getBodyTemplate().contains("CA9999"));
        assertFalse(tc.getBodyTemplate().contains("invalid"));
    }

    // ==========================================
    // M1: Target matching
    // ==========================================

    @Test
    void m1_getCustomersById_matchesTemplateTarget() {
        TestCase tc = new TestCase();
        tc.setName("Find Customer");
        tc.setMethod("GET");
        tc.setUrl("{{baseUrl}}/customers/123");

        TestSuite suite = new TestSuite();
        suite.setTestCases(new ArrayList<>(List.of(tc)));

        TargetMatcher.MatchResult match = TargetMatcher.match(suite, "GET /customers/{id}", null);
        assertEquals(1, match.getMatchCount());
        assertEquals("Find Customer", match.getTestCase().getName());
        assertEquals("METHOD_PATH", match.getMatchedBy());
    }

    @Test
    void m1_getCustomers_doesNotMatchCustomersById() {
        TestCase tc = new TestCase();
        tc.setName("Find Customer");
        tc.setMethod("GET");
        tc.setUrl("{{baseUrl}}/customers/123");

        TestSuite suite = new TestSuite();
        suite.setTestCases(new ArrayList<>(List.of(tc)));

        TargetMatcher.MatchResult match = TargetMatcher.match(suite, "GET /customers", null);
        assertEquals(0, match.getMatchCount());
    }

    @Test
    void m1_postAndGetSamePath_separatedByMethod() {
        TestCase getTc = new TestCase();
        getTc.setName("Get Customers");
        getTc.setMethod("GET");
        getTc.setUrl("{{baseUrl}}/customers");

        TestCase postTc = new TestCase();
        postTc.setName("Create Customer");
        postTc.setMethod("POST");
        postTc.setUrl("{{baseUrl}}/customers");

        TestSuite suite = new TestSuite();
        suite.setTestCases(new ArrayList<>(List.of(getTc, postTc)));

        TargetMatcher.MatchResult matchGet = TargetMatcher.match(suite, "GET /customers", null);
        assertEquals(1, matchGet.getMatchCount());
        assertEquals("Get Customers", matchGet.getTestCase().getName());

        TargetMatcher.MatchResult matchPost = TargetMatcher.match(suite, "POST /customers", null);
        assertEquals(1, matchPost.getMatchCount());
        assertEquals("Create Customer", matchPost.getTestCase().getName());
    }

    @Test
    void m1_twoTestCasesSameTemplate_isAmbiguousTarget_withCandidates() {
        TestCase tc1 = new TestCase();
        tc1.setName("Get Customer First");
        tc1.setMethod("GET");
        tc1.setUrl("https://api.example.com/customers/1");

        TestCase tc2 = new TestCase();
        tc2.setName("Get Customer Second");
        tc2.setMethod("GET");
        tc2.setUrl("https://api.example.com/customers/2");

        TestSuite suite = new TestSuite();
        suite.setTestCases(new ArrayList<>(List.of(tc1, tc2)));

        TargetMatcher.MatchResult match = TargetMatcher.match(suite, "GET /customers/{id}", null);
        assertEquals(2, match.getMatchCount());
        assertTrue(match.getCandidateNames().contains("Get Customer First"));
        assertTrue(match.getCandidateNames().contains("Get Customer Second"));
    }

    @Test
    void m1_queryStringIgnoredInMatching() {
        TestCase tc = new TestCase();
        tc.setName("Query Customer");
        tc.setMethod("GET");
        tc.setUrl("{{baseUrl}}/customers?channel=web&sort=desc");

        TestSuite suite = new TestSuite();
        suite.setTestCases(new ArrayList<>(List.of(tc)));

        TargetMatcher.MatchResult match = TargetMatcher.match(suite, "GET /customers", null);
        assertEquals(1, match.getMatchCount());
        assertEquals("Query Customer", match.getTestCase().getName());
    }
}
