package com.sabyasachi.apiregression.payload;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sabyasachi.apiregression.model.TestCase;
import com.sabyasachi.apiregression.model.TestSuite;
import com.sabyasachi.apiregression.testsupport.JsonPointerDiff;
import com.sabyasachi.apiregression.testsupport.SpecXlsxBuilder;
import com.sabyasachi.apiregression.testsupport.XlsxFixtureBuilder;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class ExcelSpecParityTest {

    private static final ObjectMapper mapper = new ObjectMapper();
    private static Path yamlSpecPath;
    private static Path excelSpecPath;

    @BeforeAll
    static void setupSpecs(@TempDir Path tempDir) throws IOException {
        // 1. YAML spec equivalent
        yamlSpecPath = tempDir.resolve("customer_spec.yaml");
        String yaml = """
                openapi: 3.0.3
                info:
                  title: Customer Service
                  version: 1.0.0
                paths:
                  /getCustDetails:
                    post:
                      operationId: getCustDetails
                      requestBody:
                        required: true
                        content:
                          application/json:
                            schema:
                              type: object
                              required:
                                - SessionContext
                                - CustId
                                - accountNum
                              properties:
                                SessionContext:
                                  type: object
                                  required:
                                    - BankCode
                                    - refid
                                    - userid
                                  properties:
                                    BankCode:
                                      type: string
                                      example: HDFC01
                                    refid:
                                      type: string
                                      example: R-1001
                                    userid:
                                      type: string
                                      example: U-77
                                CustId:
                                  type: string
                                  example: "123456"
                                  pattern: ^\\d{6}$
                                accountNum:
                                  type: string
                                  example: "12345678"
                                  pattern: ^\\d{8}$
                """;
        Files.writeString(yamlSpecPath, yaml);

        // 2. Excel spec equivalent (Example A)
        excelSpecPath = tempDir.resolve("customer_spec.xlsx");
        SpecXlsxBuilder.create()
                .sheet("getCustDetails")
                .operation("POST /getCustDetails")
                .operationId("getCustDetails")
                .header("Field", "Example", "Type", "Required", "Pattern")
                .row(0, "SessionContext", null, "object", "Y", null)
                .row(1, "BankCode", "HDFC01", "string", "Y", null)
                .row(1, "refid", "R-1001", "string", "Y", null)
                .row(1, "userid", "U-77", "string", "Y", null)
                .row(0, "CustId", "123456", "string", "Y", "^\\d{6}$")
                .row(0, "accountNum", "12345678", "string", "Y", "^\\d{8}$")
                .build(excelSpecPath);
    }

    private TestCase createBaseTestCase(String body) {
        TestCase tc = new TestCase();
        tc.setName("Get Customer Details");
        tc.setOperationId("getCustDetails");
        tc.setMethod("POST");
        tc.setUrl("https://api.example.com/getCustDetails");
        Map<String, String> hdrs = new LinkedHashMap<>();
        hdrs.put("Content-Type", "application/json");
        tc.setHeaders(hdrs);
        tc.setBodyTemplate(body);
        return tc;
    }

    @Test
    void case1_overrideUserid_producesIdenticalDiff(@TempDir Path tempDir) throws Exception {
        String baseBody = """
                {
                  "CustId": "123456",
                  "accountNum": "12345678",
                  "SessionContext": {
                    "BankCode": "HDFC01",
                    "refid": "R-1001",
                    "userid": "U-77"
                  }
                }
                """;

        Path overrideWb = tempDir.resolve("override_u99.xlsx");
        XlsxFixtureBuilder.create()
                .sheet("getCustDetails")
                .text("userid", "U-99")
                .build(overrideWb);

        ParameterOverrideService service = new ParameterOverrideService();
        OverrideOptions options = new OverrideOptions();

        // Run with YAML spec
        TestCase tcYaml = createBaseTestCase(baseBody);
        TestSuite suiteYaml = new TestSuite("Suite YAML");
        suiteYaml.setTestCases(new ArrayList<>(List.of(tcYaml)));
        OverrideReport reportYaml = service.apply(suiteYaml, overrideWb, yamlSpecPath, options);

        // Run with XLSX spec
        TestCase tcXlsx = createBaseTestCase(baseBody);
        TestSuite suiteXlsx = new TestSuite("Suite XLSX");
        suiteXlsx.setTestCases(new ArrayList<>(List.of(tcXlsx)));
        OverrideReport reportXlsx = service.apply(suiteXlsx, overrideWb, excelSpecPath, options);

        // Compare resulting JSON
        JsonNode nodeYaml = mapper.readTree(tcYaml.getBodyTemplate());
        JsonNode nodeXlsx = mapper.readTree(tcXlsx.getBodyTemplate());
        assertEquals(nodeYaml, nodeXlsx);
        assertEquals("U-99", nodeXlsx.at("/SessionContext/userid").asText());

        // Check pointer diff
        JsonPointerDiff.assertUntouchedExcept(baseBody, tcXlsx.getBodyTemplate(), Set.of("/SessionContext/userid"));
        assertEquals(reportYaml.getEntries().size(), reportXlsx.getEntries().size());
        assertEquals(reportYaml.getEntries().get(0).getResolvedPath(), reportXlsx.getEntries().get(0).getResolvedPath());
    }

    @Test
    void case2_overrideSessionContextBankCode_producesIdenticalPayload(@TempDir Path tempDir) throws Exception {
        String baseBody = """
                {
                  "CustId": "123456",
                  "accountNum": "12345678",
                  "SessionContext": {
                    "BankCode": "HDFC01",
                    "refid": "R-1001",
                    "userid": "U-77"
                  }
                }
                """;

        Path overrideWb = tempDir.resolve("override_bank.xlsx");
        XlsxFixtureBuilder.create()
                .sheet("getCustDetails")
                .text("SessionContext.BankCode", "ICIC01")
                .build(overrideWb);

        ParameterOverrideService service = new ParameterOverrideService();
        OverrideOptions options = new OverrideOptions();

        TestCase tcYaml = createBaseTestCase(baseBody);
        TestSuite suiteYaml = new TestSuite("Suite YAML");
        suiteYaml.setTestCases(new ArrayList<>(List.of(tcYaml)));
        service.apply(suiteYaml, overrideWb, yamlSpecPath, options);

        TestCase tcXlsx = createBaseTestCase(baseBody);
        TestSuite suiteXlsx = new TestSuite("Suite XLSX");
        suiteXlsx.setTestCases(new ArrayList<>(List.of(tcXlsx)));
        service.apply(suiteXlsx, overrideWb, excelSpecPath, options);

        JsonNode nodeYaml = mapper.readTree(tcYaml.getBodyTemplate());
        JsonNode nodeXlsx = mapper.readTree(tcXlsx.getBodyTemplate());
        assertEquals(nodeYaml, nodeXlsx);
        assertEquals("ICIC01", nodeXlsx.at("/SessionContext/BankCode").asText());
    }

    @Test
    void case3_dropSessionContext_gapFillProducesIdenticalObject(@TempDir Path tempDir) throws Exception {
        // Base template without SessionContext
        String baseBody = """
                {
                  "CustId": "123456",
                  "accountNum": "12345678"
                }
                """;

        Path overrideWb = tempDir.resolve("override_gapfill.xlsx");
        XlsxFixtureBuilder.create()
                .sheet("getCustDetails")
                .text("userid", "U-99")
                .build(overrideWb);

        ParameterOverrideService service = new ParameterOverrideService();
        OverrideOptions options = new OverrideOptions();

        TestCase tcYaml = createBaseTestCase(baseBody);
        TestSuite suiteYaml = new TestSuite("Suite YAML");
        suiteYaml.setTestCases(new ArrayList<>(List.of(tcYaml)));
        OverrideReport reportYaml = service.apply(suiteYaml, overrideWb, yamlSpecPath, options);

        TestCase tcXlsx = createBaseTestCase(baseBody);
        TestSuite suiteXlsx = new TestSuite("Suite XLSX");
        suiteXlsx.setTestCases(new ArrayList<>(List.of(tcXlsx)));
        OverrideReport reportXlsx = service.apply(suiteXlsx, overrideWb, excelSpecPath, options);

        JsonNode nodeYaml = mapper.readTree(tcYaml.getBodyTemplate());
        JsonNode nodeXlsx = mapper.readTree(tcXlsx.getBodyTemplate());
        assertEquals(nodeYaml, nodeXlsx);
        assertEquals("U-99", nodeXlsx.at("/SessionContext/userid").asText());
        assertEquals(reportYaml.getEntries().get(0).getWarnings(), reportXlsx.getEntries().get(0).getWarnings());
    }

    @Test
    void case4_invalidCustId_bothFailPatternValidation(@TempDir Path tempDir) throws Exception {
        String baseBody = """
                {
                  "CustId": "123456",
                  "accountNum": "12345678"
                }
                """;

        Path overrideWb = tempDir.resolve("override_bad_custid.xlsx");
        XlsxFixtureBuilder.create()
                .sheet("getCustDetails")
                .text("CustId", "ABC") // Violates ^\d{6}$
                .build(overrideWb);

        ParameterOverrideService service = new ParameterOverrideService();
        OverrideOptions options = new OverrideOptions();
        options.setConstraintPolicy(ConstraintPolicy.STRICT);

        TestCase tcYaml = createBaseTestCase(baseBody);
        TestSuite suiteYaml = new TestSuite("Suite YAML");
        suiteYaml.setTestCases(new ArrayList<>(List.of(tcYaml)));

        TestCase tcXlsx = createBaseTestCase(baseBody);
        TestSuite suiteXlsx = new TestSuite("Suite XLSX");
        suiteXlsx.setTestCases(new ArrayList<>(List.of(tcXlsx)));

        OverrideReport reportYaml = service.apply(suiteYaml, overrideWb, yamlSpecPath, options);
        OverrideReport reportXlsx = service.apply(suiteXlsx, overrideWb, excelSpecPath, options);

        assertEquals(1, reportYaml.getTotalErrors());
        assertEquals(1, reportXlsx.getTotalErrors());
        assertEquals(reportYaml.getEntries().get(0).getAction(), OverrideAction.ERROR);
        assertEquals(reportXlsx.getEntries().get(0).getAction(), OverrideAction.ERROR);
        assertEquals(reportYaml.getEntries().get(0).getReasonCode(), reportXlsx.getEntries().get(0).getReasonCode());
        assertEquals(reportYaml.getEntries().get(0).getReason(), reportXlsx.getEntries().get(0).getReason());
    }

    @Test
    void case5_emptyOverride_bothEmitsIdenticalReport(@TempDir Path tempDir) throws Exception {
        String baseBody = """
                {
                  "CustId": "123456",
                  "accountNum": "12345678"
                }
                """;

        Path overrideWb = tempDir.resolve("override_empty.xlsx");
        XlsxFixtureBuilder.create()
                .sheet("getCustDetails")
                .text("CustId", "123456")
                .build(overrideWb);

        ParameterOverrideService service = new ParameterOverrideService();
        OverrideOptions options = new OverrideOptions();

        TestCase tcYaml = createBaseTestCase(baseBody);
        TestSuite suiteYaml = new TestSuite("Suite YAML");
        suiteYaml.setTestCases(new ArrayList<>(List.of(tcYaml)));
        OverrideReport reportYaml = service.apply(suiteYaml, overrideWb, yamlSpecPath, options);

        TestCase tcXlsx = createBaseTestCase(baseBody);
        TestSuite suiteXlsx = new TestSuite("Suite XLSX");
        suiteXlsx.setTestCases(new ArrayList<>(List.of(tcXlsx)));
        OverrideReport reportXlsx = service.apply(suiteXlsx, overrideWb, excelSpecPath, options);

        assertEquals(reportYaml.getTotalModified(), reportXlsx.getTotalModified());
        assertEquals(reportYaml.getTotalAdded(), reportXlsx.getTotalAdded());
        assertEquals(reportYaml.getTotalErrors(), reportXlsx.getTotalErrors());
        assertEquals(tcYaml.getBodyTemplate(), tcXlsx.getBodyTemplate());
    }
}
