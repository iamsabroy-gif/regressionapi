package com.sabyasachi.apiregression.payload;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.IntNode;
import com.fasterxml.jackson.databind.node.TextNode;
import com.sabyasachi.apiregression.model.TestCase;
import com.sabyasachi.apiregression.model.TestSuite;
import com.sabyasachi.apiregression.spec.ConstraintValidator;
import com.sabyasachi.apiregression.spec.ProviderSpecLoader;
import com.sabyasachi.apiregression.spec.SchemaNavigator;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.media.IntegerSchema;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.FileOutputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@SuppressWarnings("rawtypes")
public class GapFillTest {

    private final Path oas3Path = Path.of("src/test/resources/spec/customers-oas3.yaml");
    private final Path oas31Path = Path.of("src/test/resources/spec/customers-oas31.yaml");

    private Path createWorkbook(Path tempDir, String sheetName, String[][] rows) throws Exception {
        Path excelPath = tempDir.resolve("test_" + System.nanoTime() + ".xlsx");
        try (Workbook wb = new XSSFWorkbook()) {
            Sheet sheet = wb.createSheet(sheetName);
            Row h = sheet.createRow(0);
            h.createCell(0).setCellValue("parameter");
            h.createCell(1).setCellValue("value");
            h.createCell(2).setCellValue("type");
            h.createCell(3).setCellValue("location");

            for (int i = 0; i < rows.length; i++) {
                Row r = sheet.createRow(i + 1);
                r.createCell(0).setCellValue(rows[i][0]);
                r.createCell(1).setCellValue(rows[i][1]);
                if (rows[i].length > 2 && rows[i][2] != null) r.createCell(2).setCellValue(rows[i][2]);
                if (rows[i].length > 3 && rows[i][3] != null) r.createCell(3).setCellValue(rows[i][3]);
            }

            try (FileOutputStream fos = new FileOutputStream(excelPath.toFile())) {
                wb.write(fos);
            }
        }
        return excelPath;
    }

    @Test
    public void testSchemaNavigatorFindsLeaf() throws Exception {
        OpenAPI openAPI = ProviderSpecLoader.loadOpenAPI(oas3Path);
        assertNotNull(openAPI);

        SchemaNavigator navigator = new SchemaNavigator(openAPI);
        Schema customerSchema = openAPI.getComponents().getSchemas().get("Customer");
        assertNotNull(customerSchema);

        List<SchemaNavigator.ResolvedField> fields = navigator.findSchemaPathsForLeaf(customerSchema, "name");
        assertFalse(fields.isEmpty());
        assertEquals("$.name", fields.get(0).getJsonPointer());
    }

    @Test
    public void m3_oas31_arrayItemsAndTypesPreserved() throws Exception {
        OpenAPI openAPI = ProviderSpecLoader.loadOpenAPI(oas31Path);
        assertNotNull(openAPI);

        SchemaNavigator navigator = new SchemaNavigator(openAPI);
        Schema customerSchema = openAPI.getComponents().getSchemas().get("Customer");
        assertNotNull(customerSchema);

        Schema baseSchema = openAPI.getComponents().getSchemas().get("Base");
        assertNotNull(baseSchema);
        Schema notesSchema = (Schema) baseSchema.getProperties().get("notes");
        assertNotNull(notesSchema);

        SchemaNavigator.SchemaView notesView = new SchemaNavigator.SchemaView(notesSchema);
        assertTrue(notesView.isNullable(), "OAS 3.1 type: [string, null] must be recognized as nullable");
        assertTrue(notesView.getTypes().contains("null"));
        assertTrue(notesView.getTypes().contains("string"));
    }

    @Test
    public void m3_oneOf_discriminatorPicksBranch() throws Exception {
        OpenAPI openAPI = ProviderSpecLoader.loadOpenAPI(oas3Path);
        SchemaNavigator navigator = new SchemaNavigator(openAPI);
        Schema paymentSchema = openAPI.getComponents().getSchemas().get("Payment");

        JsonNode cardPayload = new ObjectMapper().readTree("{\"paymentType\": \"CARD\"}");
        Schema branch = navigator.resolvePolymorphicProperty(paymentSchema, "cardNumber", cardPayload);
        assertNotNull(branch);
        assertEquals("CardPayment", branch.getName());
    }

    @Test
    public void m3_oneOf_ambiguousProperty_isAmbiguousPolymorphic() {
        ObjectSchema branch1 = new ObjectSchema();
        branch1.setName("Branch1");
        branch1.addProperty("prop", new StringSchema());

        ObjectSchema branch2 = new ObjectSchema();
        branch2.setName("Branch2");
        branch2.addProperty("prop", new IntegerSchema());

        ObjectSchema parent = new ObjectSchema();
        parent.setOneOf(List.of(branch1, branch2));

        SchemaNavigator navigator = new SchemaNavigator(null);
        OverrideException ex = assertThrows(OverrideException.class, () ->
                navigator.resolvePolymorphicProperty(parent, "prop", null));
        assertEquals(ReasonCode.AMBIGUOUS_POLYMORPHIC, ex.getReasonCode());
    }

    @Test
    public void m3_oneOf_propertyInOneBranch_used() throws Exception {
        OpenAPI openAPI = ProviderSpecLoader.loadOpenAPI(oas3Path);
        SchemaNavigator navigator = new SchemaNavigator(openAPI);
        Schema paymentSchema = openAPI.getComponents().getSchemas().get("Payment");

        // iban exists only in BankPayment
        Schema branch = navigator.resolvePolymorphicProperty(paymentSchema, "iban", null);
        assertNotNull(branch);
        assertEquals("BankPayment", branch.getName());
    }

    @Test
    public void m3_needsIndex_whenArrayPresent(@TempDir Path tempDir) throws Exception {
        TestCase tc = new TestCase();
        tc.setName("Create Customer");
        tc.setMethod("POST");
        tc.setUrl("https://api.example.com/v1/customers");
        tc.setHeaders(new LinkedHashMap<>());
        tc.setBodyTemplate("{\n  \"contacts\": []\n}");

        TestSuite suite = new TestSuite("Customer Suite");
        suite.setTestCases(new ArrayList<>(List.of(tc)));

        Path xlsx = createWorkbook(tempDir, "Create Customer", new String[][]{
                {"email", "new@test.com", "string", null}
        });

        ParameterOverrideService service = new ParameterOverrideService();
        OverrideReport report = service.dryRun(suite, xlsx, oas3Path, new OverrideOptions());
        assertEquals(1, report.getEntries().size());
        OverrideEntry entry = report.getEntries().get(0);
        assertEquals(OverrideAction.ERROR, entry.getAction());
        assertEquals(ReasonCode.NEEDS_INDEX, entry.getReasonCodeEnum());
        assertFalse(entry.getCandidates().isEmpty());
        assertTrue(entry.getCandidates().get(0).contains("[0]"));
    }

    @Test
    public void m3_arrayAbsent_createdWithElementZero(@TempDir Path tempDir) throws Exception {
        TestCase tc = new TestCase();
        tc.setName("Create Customer");
        tc.setMethod("POST");
        tc.setUrl("https://api.example.com/v1/customers");
        tc.setHeaders(new LinkedHashMap<>());
        tc.setBodyTemplate("{\n  \"name\": \"Alice\"\n}");

        TestSuite suite = new TestSuite("Customer Suite");
        suite.setTestCases(new ArrayList<>(List.of(tc)));

        Path xlsx = createWorkbook(tempDir, "Create Customer", new String[][]{
                {"email", "alice@test.com", "string", null}
        });

        ParameterOverrideService service = new ParameterOverrideService();
        OverrideReport report = service.dryRun(suite, xlsx, oas3Path, new OverrideOptions());
        assertEquals(1, report.getEntries().size());
        OverrideEntry entry = report.getEntries().get(0);
        assertEquals(OverrideAction.ADDED, entry.getAction());
        assertEquals("/contacts/0/email", entry.getResolvedPath());
    }

    @Test
    public void m3_noSpec_bareQueryName_modifiesQuery_notBody(@TempDir Path tempDir) throws Exception {
        TestCase tc = new TestCase();
        tc.setName("List Customers");
        tc.setMethod("GET");
        tc.setUrl("https://api.example.com/v1/customers?channel=web");
        tc.setHeaders(new LinkedHashMap<>());
        tc.setBodyTemplate(null);

        TestSuite suite = new TestSuite("Customer Suite");
        suite.setTestCases(new ArrayList<>(List.of(tc)));

        Path xlsx = createWorkbook(tempDir, "List Customers", new String[][]{
                {"channel", "mobile", null, null}
        });

        ParameterOverrideService service = new ParameterOverrideService();
        OverrideReport report = service.apply(suite, xlsx, null, new OverrideOptions());
        assertEquals(1, report.getEntries().size());
        OverrideEntry entry = report.getEntries().get(0);
        assertEquals(OverrideAction.MODIFIED, entry.getAction());
        assertEquals("query.channel", entry.getResolvedPath());
        assertTrue(tc.getUrl().contains("channel=mobile"));
        assertNull(tc.getBodyTemplate());
    }

    @Test
    public void m3_additionalPropertiesTrue_explicitPath_addedWithWarning(@TempDir Path tempDir) throws Exception {
        TestCase tc = new TestCase();
        tc.setName("Create Customer");
        tc.setMethod("POST");
        tc.setUrl("https://api.example.com/v1/customers");
        tc.setHeaders(new LinkedHashMap<>());
        tc.setBodyTemplate("{\n  \"name\": \"Alice\",\n  \"meta\": {}\n}");

        TestSuite suite = new TestSuite("Customer Suite");
        suite.setTestCases(new ArrayList<>(List.of(tc)));

        Path xlsx = createWorkbook(tempDir, "Create Customer", new String[][]{
                {"$.meta.customField", "customValue", "string", "body"}
        });

        ParameterOverrideService service = new ParameterOverrideService();
        OverrideReport report = service.dryRun(suite, xlsx, oas3Path, new OverrideOptions());
        assertEquals(1, report.getEntries().size());
        OverrideEntry entry = report.getEntries().get(0);
        assertEquals(OverrideAction.ADDED, entry.getAction());
        assertEquals(ReasonCode.UNDECLARED, entry.getReasonCodeEnum());
        assertTrue(entry.getWarnings().stream().anyMatch(w -> w.contains("additionalProperties")));
    }

    @Test
    public void m3_additionalPropertiesAbsent_isNotInSpec(@TempDir Path tempDir) throws Exception {
        TestCase tc = new TestCase();
        tc.setName("Create Customer");
        tc.setMethod("POST");
        tc.setUrl("https://api.example.com/v1/customers");
        tc.setHeaders(new LinkedHashMap<>());
        tc.setBodyTemplate("{\n  \"name\": \"Alice\"\n}");

        TestSuite suite = new TestSuite("Customer Suite");
        suite.setTestCases(new ArrayList<>(List.of(tc)));

        Path xlsx = createWorkbook(tempDir, "Create Customer", new String[][]{
                {"$.bogus.field", "value", "string", "body"}
        });

        ParameterOverrideService service = new ParameterOverrideService();
        OverrideReport report = service.dryRun(suite, xlsx, oas3Path, new OverrideOptions());
        assertEquals(1, report.getEntries().size());
        OverrideEntry entry = report.getEntries().get(0);
        assertEquals(OverrideAction.ERROR, entry.getAction());
        assertEquals(ReasonCode.NOT_IN_SPEC, entry.getReasonCodeEnum());
    }

    @Test
    public void m3_requiredSiblingsMissing_warning(@TempDir Path tempDir) throws Exception {
        TestCase tc = new TestCase();
        tc.setName("Create Customer");
        tc.setMethod("POST");
        tc.setUrl("https://api.example.com/v1/customers");
        tc.setHeaders(new LinkedHashMap<>());
        // No address object present initially
        tc.setBodyTemplate("{\n  \"name\": \"Alice\"\n}");

        TestSuite suite = new TestSuite("Customer Suite");
        suite.setTestCases(new ArrayList<>(List.of(tc)));

        // Address schema has required: [city]. If we add address.street without setting required city:
        // Actually in customers-oas3.yaml, Address requires [city]. Let's set street only.
        Path xlsx = createWorkbook(tempDir, "Create Customer", new String[][]{
                {"$.address.street", "Main St", "string", "body"}
        });

        ParameterOverrideService service = new ParameterOverrideService();
        OverrideReport report = service.dryRun(suite, xlsx, oas3Path, new OverrideOptions());
        assertEquals(1, report.getEntries().size());
        OverrideEntry entry = report.getEntries().get(0);
        assertEquals(OverrideAction.ADDED, entry.getAction());
        assertTrue(entry.getWarnings().stream().anyMatch(w -> w.contains("REQUIRED_SIBLINGS_MISSING")));
    }

    @Test
    public void m3_ambiguousLocation_listsBoth(@TempDir Path tempDir) throws Exception {
        TestCase tc = new TestCase();
        tc.setName("Create Customer");
        tc.setMethod("POST");
        tc.setUrl("https://api.example.com/v1/customers?channel=web");
        tc.setHeaders(new LinkedHashMap<>());
        tc.setBodyTemplate("{\n  \"name\": \"Alice\",\n  \"channel\": \"app\"\n}");

        TestSuite suite = new TestSuite("Customer Suite");
        suite.setTestCases(new ArrayList<>(List.of(tc)));

        // Bare name 'channel' exists in both query and body
        Path xlsx = createWorkbook(tempDir, "Create Customer", new String[][]{
                {"channel", "newChannel", "string", null}
        });

        ParameterOverrideService service = new ParameterOverrideService();
        OverrideReport report = service.dryRun(suite, xlsx, null, new OverrideOptions());
        assertEquals(1, report.getEntries().size());
        OverrideEntry entry = report.getEntries().get(0);
        assertEquals(OverrideAction.ERROR, entry.getAction());
        assertEquals(ReasonCode.AMBIGUOUS_LOCATION, entry.getReasonCodeEnum());
    }

    @Test
    public void m3_operationNotFound_modifyStillApplied(@TempDir Path tempDir) throws Exception {
        TestCase tc = new TestCase();
        tc.setName("Unknown Op");
        tc.setMethod("PATCH");
        tc.setUrl("https://api.example.com/v1/unknown");
        tc.setHeaders(new LinkedHashMap<>());
        tc.setBodyTemplate("{\n  \"name\": \"Alice\"\n}");

        TestSuite suite = new TestSuite("Customer Suite");
        suite.setTestCases(new ArrayList<>(List.of(tc)));

        Path xlsx = createWorkbook(tempDir, "Unknown Op", new String[][]{
                {"$.name", "Bob", "string", "body"},
                {"$.missingField", "foo", "string", "body"}
        });

        ParameterOverrideService service = new ParameterOverrideService();
        OverrideReport report = service.dryRun(suite, xlsx, oas3Path, new OverrideOptions());
        assertEquals(2, report.getEntries().size());

        // Row 1: MODIFY succeeds
        assertEquals(OverrideAction.MODIFIED, report.getEntries().get(0).getAction());

        // Row 2: Gap-fill gets OPERATION_NOT_FOUND
        assertEquals(OverrideAction.ERROR, report.getEntries().get(1).getAction());
        assertEquals(ReasonCode.OPERATION_NOT_FOUND, report.getEntries().get(1).getReasonCodeEnum());
    }

    @Test
    public void m3_recursiveSchema_terminates() throws Exception {
        OpenAPI openAPI = ProviderSpecLoader.loadOpenAPI(oas3Path);
        SchemaNavigator navigator = new SchemaNavigator(openAPI);
        Schema nodeSchema = openAPI.getComponents().getSchemas().get("Node");

        assertDoesNotThrow(() -> {
            List<SchemaNavigator.ResolvedField> fields = navigator.findSchemaPathsForLeaf(nodeSchema, "name");
            assertFalse(fields.isEmpty());
        });
    }

    @Test
    public void m3_ambiguousLeaf_listsAllCandidatePaths(@TempDir Path tempDir) throws Exception {
        TestCase tc = new TestCase();
        tc.setName("Create Customer");
        tc.setMethod("POST");
        tc.setUrl("https://api.example.com/v1/customers");
        tc.setHeaders(new LinkedHashMap<>());
        tc.setBodyTemplate("{\n  \"name\": \"Alice\"\n}");

        TestSuite suite = new TestSuite("Customer Suite");
        suite.setTestCases(new ArrayList<>(List.of(tc)));

        // city is under both address.city and billing.city in Customer
        Path xlsx = createWorkbook(tempDir, "Create Customer", new String[][]{
                {"city", "Paris", "string", null}
        });

        ParameterOverrideService service = new ParameterOverrideService();
        OverrideReport report = service.dryRun(suite, xlsx, oas3Path, new OverrideOptions());
        assertEquals(1, report.getEntries().size());
        OverrideEntry entry = report.getEntries().get(0);
        assertEquals(OverrideAction.ERROR, entry.getAction());
        assertEquals(ReasonCode.AMBIGUOUS, entry.getReasonCodeEnum());
        assertEquals(2, entry.getCandidates().size());
    }

    @Test
    public void m3_readOnlyAdd_isError(@TempDir Path tempDir) throws Exception {
        TestCase tc = new TestCase();
        tc.setName("Create Customer");
        tc.setMethod("POST");
        tc.setUrl("https://api.example.com/v1/customers");
        tc.setHeaders(new LinkedHashMap<>());
        tc.setBodyTemplate("{\n  \"name\": \"Alice\"\n}");

        TestSuite suite = new TestSuite("Customer Suite");
        suite.setTestCases(new ArrayList<>(List.of(tc)));

        // id is marked readOnly: true in spec
        Path xlsx = createWorkbook(tempDir, "Create Customer", new String[][]{
                {"$.id", "123", "integer", "body"}
        });

        ParameterOverrideService service = new ParameterOverrideService();
        OverrideReport report = service.dryRun(suite, xlsx, oas3Path, new OverrideOptions());
        assertEquals(1, report.getEntries().size());
        OverrideEntry entry = report.getEntries().get(0);
        assertEquals(OverrideAction.ERROR, entry.getAction());
        assertEquals(ReasonCode.READONLY_ADD, entry.getReasonCodeEnum());
    }

    @Test
    public void m3_readOnlyModify_warnOrStrictError(@TempDir Path tempDir) throws Exception {
        TestCase tc = new TestCase();
        tc.setName("Create Customer");
        tc.setMethod("POST");
        tc.setUrl("https://api.example.com/v1/customers");
        tc.setHeaders(new LinkedHashMap<>());
        tc.setBodyTemplate("{\n  \"name\": \"Alice\",\n  \"id\": 10\n}");

        TestSuite suite = new TestSuite("Customer Suite");
        suite.setTestCases(new ArrayList<>(List.of(tc)));

        Path xlsx = createWorkbook(tempDir, "Create Customer", new String[][]{
                {"$.id", "20", "integer", "body"}
        });

        ParameterOverrideService service = new ParameterOverrideService();

        // 1. STRICT -> ERROR READONLY_MODIFY
        OverrideOptions strictOptions = new OverrideOptions();
        strictOptions.setConstraintPolicy(ConstraintPolicy.STRICT);
        OverrideReport strictReport = service.dryRun(suite, xlsx, oas3Path, strictOptions);
        assertEquals(OverrideAction.ERROR, strictReport.getEntries().get(0).getAction());
        assertEquals(ReasonCode.READONLY_MODIFY, strictReport.getEntries().get(0).getReasonCodeEnum());

        // 2. WARN -> MODIFIED with READONLY_MODIFY warning
        OverrideOptions warnOptions = new OverrideOptions();
        warnOptions.setConstraintPolicy(ConstraintPolicy.WARN);
        OverrideReport warnReport = service.dryRun(suite, xlsx, oas3Path, warnOptions);
        assertEquals(OverrideAction.MODIFIED, warnReport.getEntries().get(0).getAction());
        assertTrue(warnReport.getEntries().get(0).getWarnings().stream().anyMatch(w -> w.contains("READONLY_MODIFY")));
    }

    @Test
    public void m3_specMissing_modificationsStillApplied(@TempDir Path tempDir) throws Exception {
        TestCase tc = new TestCase();
        tc.setName("Simple Test");
        tc.setMethod("POST");
        tc.setUrl("https://api.example.com/v1/test");
        tc.setHeaders(new LinkedHashMap<>());
        tc.setBodyTemplate("{\n  \"name\": \"Alice\",\n  \"count\": 5\n}");

        TestSuite suite = new TestSuite("Simple Suite");
        suite.setTestCases(new ArrayList<>(List.of(tc)));

        Path xlsx = createWorkbook(tempDir, "Simple Test", new String[][]{
                {"$.name", "Bob", "string", "body"}
        });

        ParameterOverrideService service = new ParameterOverrideService();
        OverrideReport report = service.apply(suite, xlsx, null, new OverrideOptions());
        assertEquals(1, report.getEntries().size());
        assertEquals(OverrideAction.MODIFIED, report.getEntries().get(0).getAction());
        assertTrue(tc.getBodyTemplate().contains("\"name\" : \"Bob\"") || tc.getBodyTemplate().contains("\"name\":\"Bob\""));
    }
}
