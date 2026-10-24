package com.sabyasachi.apiregression.spec;

import com.sabyasachi.apiregression.payload.OverrideException;
import com.sabyasachi.apiregression.payload.ReasonCode;
import com.sabyasachi.apiregression.testsupport.SpecXlsxBuilder;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.media.ArraySchema;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.parameters.Parameter;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ExcelSpecLoaderTest {

    @BeforeAll
    static void generateSampleSpecIfMissing() throws IOException {
        Path sampleSpecPath = Path.of("examples/sample_spec.xlsx");
        if (!Files.exists(sampleSpecPath)) {
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
                    .build(sampleSpecPath);
        }
    }

    @Test
    void exampleA_indentSpaces_buildsExpectedSchema(@TempDir Path tempDir) throws Exception {
        Path path = tempDir.resolve("exampleA.xlsx");
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
                .build(path);

        OpenAPI openAPI = ProviderSpecLoader.loadOpenAPI(path);
        assertNotNull(openAPI);
        assertNotNull(openAPI.getPaths());
        assertTrue(openAPI.getPaths().containsKey("/getCustDetails"));

        Operation op = openAPI.getPaths().get("/getCustDetails").getPost();
        assertNotNull(op);
        assertEquals("getCustDetails", op.getOperationId());

        assertNotNull(op.getRequestBody());
        assertNotNull(op.getRequestBody().getContent());
        var mediaType = op.getRequestBody().getContent().get("application/json");
        assertNotNull(mediaType);
        Schema<?> bodySchema = mediaType.getSchema();
        assertTrue(bodySchema instanceof ObjectSchema);
        assertEquals("object", bodySchema.getType());

        Map<String, Schema> props = bodySchema.getProperties();
        assertNotNull(props);
        assertEquals(3, props.size());
        assertTrue(props.containsKey("SessionContext"));
        assertTrue(props.containsKey("CustId"));
        assertTrue(props.containsKey("accountNum"));

        // Check required fields at top level
        assertTrue(bodySchema.getRequired().contains("SessionContext"));
        assertTrue(bodySchema.getRequired().contains("CustId"));
        assertTrue(bodySchema.getRequired().contains("accountNum"));

        // Check nested SessionContext
        Schema sessionContextSchema = props.get("SessionContext");
        assertTrue(sessionContextSchema instanceof ObjectSchema);
        Map<String, Schema> sessionProps = sessionContextSchema.getProperties();
        assertEquals(3, sessionProps.size());
        assertTrue(sessionProps.containsKey("BankCode"));
        assertTrue(sessionProps.containsKey("refid"));
        assertTrue(sessionProps.containsKey("userid"));
        assertTrue(sessionContextSchema.getRequired().contains("BankCode"));
        assertTrue(sessionContextSchema.getRequired().contains("refid"));
        assertTrue(sessionContextSchema.getRequired().contains("userid"));

        // Check pattern
        assertEquals("^\\d{6}$", props.get("CustId").getPattern());
        assertEquals("^\\d{8}$", props.get("accountNum").getPattern());
    }

    @Test
    void exampleA_excelCellIndent_equalsLeadingSpaces(@TempDir Path tempDir) throws Exception {
        Path pathSpaces = tempDir.resolve("spaces.xlsx");
        SpecXlsxBuilder.create()
                .sheet("getCustDetails")
                .operation("POST /getCustDetails")
                .header("Field", "Example", "Type", "Required", "Pattern")
                .row(0, "SessionContext", null, "object", "Y", null)
                .row(1, "BankCode", "HDFC01", "string", "Y", null)
                .row(0, "CustId", "123456", "string", "Y", null)
                .build(pathSpaces);

        Path pathIndent = tempDir.resolve("indent.xlsx");
        SpecXlsxBuilder.create()
                .sheet("getCustDetails")
                .operation("POST /getCustDetails")
                .header("Field", "Example", "Type", "Required", "Pattern")
                .row(0, "SessionContext", null, "object", "Y", null)
                .rowWithCellIndent((short) 1, "BankCode", "HDFC01", "string", "Y", null)
                .row(0, "CustId", "123456", "string", "Y", null)
                .build(pathIndent);

        OpenAPI apiSpaces = ProviderSpecLoader.loadOpenAPI(pathSpaces);
        OpenAPI apiIndent = ProviderSpecLoader.loadOpenAPI(pathIndent);

        Schema<?> schemaSpaces = apiSpaces.getPaths().get("/getCustDetails").getPost().getRequestBody().getContent().get("application/json").getSchema();
        Schema<?> schemaIndent = apiIndent.getPaths().get("/getCustDetails").getPost().getRequestBody().getContent().get("application/json").getSchema();

        assertEquals(schemaSpaces.getProperties().keySet(), schemaIndent.getProperties().keySet());
        Schema<?> childSpaces = (Schema<?>) schemaSpaces.getProperties().get("SessionContext");
        Schema<?> childIndent = (Schema<?>) schemaIndent.getProperties().get("SessionContext");
        assertEquals(childSpaces.getProperties().keySet(), childIndent.getProperties().keySet());
    }

    @Test
    void exampleC_arrays(@TempDir Path tempDir) throws Exception {
        Path path = tempDir.resolve("arrays.xlsx");
        SpecXlsxBuilder.create()
                .sheet("createOrder")
                .operation("POST /orders")
                .header("Field", "Example", "Type", "Required", "Pattern")
                .row(0, "orderId", "ORD-1", "string", "Y", null)
                .row(0, "items[]", null, "object[]", "Y", null)
                .row(1, "sku", "SKU-99", "string", "Y", null)
                .row(1, "qty", 2, "integer", "Y", null)
                .row(0, "tags[]", "tag1", "string[]", "N", null)
                .build(path);

        OpenAPI api = ProviderSpecLoader.loadOpenAPI(path);
        Schema<?> body = api.getPaths().get("/orders").getPost().getRequestBody().getContent().get("application/json").getSchema();

        Schema<?> itemsSchema = (Schema<?>) body.getProperties().get("items");
        assertTrue(itemsSchema instanceof ArraySchema);
        ArraySchema itemsArray = (ArraySchema) itemsSchema;
        assertTrue(itemsArray.getItems() instanceof ObjectSchema);
        assertEquals(2, itemsArray.getItems().getProperties().size());
        assertTrue(itemsArray.getItems().getProperties().containsKey("sku"));
        assertTrue(itemsArray.getItems().getProperties().containsKey("qty"));

        Schema<?> tagsSchema = (Schema<?>) body.getProperties().get("tags");
        assertTrue(tagsSchema instanceof ArraySchema);
        ArraySchema tagsArray = (ArraySchema) tagsSchema;
        assertTrue(tagsArray.getItems() instanceof StringSchema);
    }

    @Test
    void exampleB_braceMode_sameModelAsIndent(@TempDir Path tempDir) throws Exception {
        Path pathIndent = tempDir.resolve("indent.xlsx");
        SpecXlsxBuilder.create()
                .sheet("getCustDetails")
                .operation("POST /getCustDetails")
                .header("Field", "Example", "Type", "Required", "Pattern")
                .row(0, "SessionContext", null, "object", "Y", null)
                .row(1, "BankCode", "HDFC01", "string", "Y", null)
                .row(1, "refid", "R-1001", "string", "Y", null)
                .row(0, "CustId", "123456", "string", "Y", "^\\d{6}$")
                .build(pathIndent);

        Path pathBrace = tempDir.resolve("brace.xlsx");
        SpecXlsxBuilder.create()
                .sheet("getCustDetails")
                .operation("POST /getCustDetails")
                .header("Field", "Example", "Type", "Required", "Pattern")
                .row("SessionContext {", null, "object", "Y", null)
                .row("BankCode", "HDFC01", "string", "Y", null)
                .row("refid", "R-1001", "string", "Y", null)
                .row("}", null, null, null, null)
                .row("CustId", "123456", "string", "Y", "^\\d{6}$")
                .build(pathBrace);

        OpenAPI apiIndent = ProviderSpecLoader.loadOpenAPI(pathIndent);
        OpenAPI apiBrace = ProviderSpecLoader.loadOpenAPI(pathBrace);

        Schema<?> bodyIndent = apiIndent.getPaths().get("/getCustDetails").getPost().getRequestBody().getContent().get("application/json").getSchema();
        Schema<?> bodyBrace = apiBrace.getPaths().get("/getCustDetails").getPost().getRequestBody().getContent().get("application/json").getSchema();

        assertEquals(bodyIndent.getProperties().keySet(), bodyBrace.getProperties().keySet());
        Schema<?> scIndent = (Schema<?>) bodyIndent.getProperties().get("SessionContext");
        Schema<?> scBrace = (Schema<?>) bodyBrace.getProperties().get("SessionContext");
        assertEquals(scIndent.getProperties().keySet(), scBrace.getProperties().keySet());
    }

    @Test
    void arrayIndexOtherThanZero_rejected(@TempDir Path tempDir) throws Exception {
        Path path = tempDir.resolve("invalid_array.xlsx");
        SpecXlsxBuilder.create()
                .sheet("test")
                .operation("POST /test")
                .header("Field", "Example")
                .row(0, "items[1]", "val", null, null, null)
                .build(path);

        OverrideException ex = assertThrows(OverrideException.class, () -> ProviderSpecLoader.loadOpenAPI(path));
        assertEquals(ReasonCode.SPEC_LAYOUT_INVALID, ex.getReasonCode());
        assertTrue(ex.getMessage().toLowerCase().contains("zero") || ex.getMessage().contains("[0]"));
    }

    @Test
    void typeInference_textStaysString(@TempDir Path tempDir) throws Exception {
        Path path = tempDir.resolve("text_types.xlsx");
        SpecXlsxBuilder.create()
                .sheet("test")
                .operation("POST /test")
                .header("Field", "Example")
                .row(0, "strBool", "true", null, null, null) // String cell containing "true"
                .row(0, "strNum", "123", null, null, null)   // String cell containing "123"
                .build(path);

        OpenAPI api = ProviderSpecLoader.loadOpenAPI(path);
        Schema<?> body = api.getPaths().get("/test").getPost().getRequestBody().getContent().get("application/json").getSchema();
        assertEquals("string", body.getProperties().get("strBool").getType());
        assertEquals("string", body.getProperties().get("strNum").getType());
    }

    @Test
    void idLikeNumericCell_warns(@TempDir Path tempDir) throws Exception {
        Path path = tempDir.resolve("id_warning.xlsx");
        SpecXlsxBuilder.create()
                .sheet("test")
                .operation("POST /test")
                .header("Field", "Example")
                .row(0, "id", 123456, null, null, null) // Numeric cell with 6 digits
                .build(path);

        ProviderSpecLoader.LoadedSpec loaded = ExcelSpecLoader.load(path);
        List<String> warnings = loaded.getMessages();
        assertTrue(warnings.stream().anyMatch(w -> w.contains("SPEC_CELL_TYPE_COERCED") && w.contains("leading zero")));
    }

    @Test
    void indentJump_rejected_withRow(@TempDir Path tempDir) throws Exception {
        Path path = tempDir.resolve("jump.xlsx");
        SpecXlsxBuilder.create()
                .sheet("test")
                .operation("POST /test")
                .header("Field", "Example")
                .row(0, "parent", null, null, null, null)
                .row(2, "grandchild", "val", null, null, null) // Jumped from 0 to 2
                .build(path);

        OverrideException ex = assertThrows(OverrideException.class, () -> ProviderSpecLoader.loadOpenAPI(path));
        assertEquals(ReasonCode.SPEC_LAYOUT_INVALID, ex.getReasonCode());
        assertTrue(ex.getMessage().contains("row") || ex.getMessage().contains("indentation"));
    }

    @Test
    void unbalancedBrace_rejected_withRow(@TempDir Path tempDir) throws Exception {
        Path path = tempDir.resolve("unclosed_brace.xlsx");
        SpecXlsxBuilder.create()
                .sheet("test")
                .operation("POST /test")
                .header("Field", "Example")
                .row("parent {", null, null, null, null)
                .row("child", "val", null, null, null)
                // missing }
                .build(path);

        OverrideException ex = assertThrows(OverrideException.class, () -> ProviderSpecLoader.loadOpenAPI(path));
        assertEquals(ReasonCode.SPEC_LAYOUT_INVALID, ex.getReasonCode());
        assertTrue(ex.getMessage().contains("unclosed") || ex.getMessage().contains("brace"));
    }

    @Test
    void duplicateKey_rejected(@TempDir Path tempDir) throws Exception {
        Path path = tempDir.resolve("duplicate_key.xlsx");
        SpecXlsxBuilder.create()
                .sheet("test")
                .operation("POST /test")
                .header("Field", "Example")
                .row(0, "code", "A", null, null, null)
                .row(0, "code", "B", null, null, null)
                .build(path);

        OverrideException ex = assertThrows(OverrideException.class, () -> ProviderSpecLoader.loadOpenAPI(path));
        assertEquals(ReasonCode.SPEC_LAYOUT_INVALID, ex.getReasonCode());
        assertTrue(ex.getMessage().contains("Duplicate") || ex.getMessage().contains("duplicate"));
    }

    @Test
    void leafWithChildren_rejected(@TempDir Path tempDir) throws Exception {
        Path path = tempDir.resolve("leaf_with_children.xlsx");
        SpecXlsxBuilder.create()
                .sheet("test")
                .operation("POST /test")
                .header("Field", "Example")
                .row(0, "leaf", "value", null, null, null)
                .row(1, "child", "childValue", null, null, null)
                .build(path);

        OverrideException ex = assertThrows(OverrideException.class, () -> ProviderSpecLoader.loadOpenAPI(path));
        assertEquals(ReasonCode.SPEC_LAYOUT_INVALID, ex.getReasonCode());
        assertTrue(ex.getMessage().contains("primitive") || ex.getMessage().contains("children"));
    }

    @Test
    void hiddenSheet_skipped(@TempDir Path tempDir) throws Exception {
        Path path = tempDir.resolve("hidden.xlsx");
        SpecXlsxBuilder.create()
                .sheet("visible")
                .operation("POST /visible")
                .header("Field", "Example")
                .row(0, "id", "1", null, null, null)
                .sheet("hiddenSheet")
                .hidden()
                .operation("POST /hidden")
                .header("Field", "Example")
                .row(0, "id", "2", null, null, null)
                .build(path);

        OpenAPI api = ProviderSpecLoader.loadOpenAPI(path);
        assertTrue(api.getPaths().containsKey("/visible"));
        assertFalse(api.getPaths().containsKey("/hidden"));
    }

    @Test
    void formulaCachedValue_used_noCacheRowDropped(@TempDir Path tempDir) throws Exception {
        Path path = tempDir.resolve("formula.xlsx");
        SpecXlsxBuilder.create()
                .sheet("test")
                .operation("POST /test")
                .header("Field", "Example")
                .formulaRow(0, "cachedFormula", "CONCATENATE(\"A\", \"B\")", "AB", "string", "Y", null)
                .formulaRowNoCache(0, "noCacheFormula", "TODAY()", "string", "Y", null)
                .build(path);

        OpenAPI api = ProviderSpecLoader.loadOpenAPI(path);
        Schema<?> body = api.getPaths().get("/test").getPost().getRequestBody().getContent().get("application/json").getSchema();
        assertTrue(body.getProperties().containsKey("cachedFormula"));
        assertEquals("AB", body.getProperties().get("cachedFormula").getExample());
        assertFalse(body.getProperties().containsKey("noCacheFormula"));
    }

    @Test
    void patternInvalidOrTooLong_rejected(@TempDir Path tempDir) throws Exception {
        Path pathInvalidRegex = tempDir.resolve("invalid_regex.xlsx");
        SpecXlsxBuilder.create()
                .sheet("test")
                .operation("POST /test")
                .header("Field", "Example", "Type", "Required", "Pattern")
                .row(0, "code", "123", "string", "Y", "[unclosed")
                .build(pathInvalidRegex);

        OverrideException ex1 = assertThrows(OverrideException.class, () -> ProviderSpecLoader.loadOpenAPI(pathInvalidRegex));
        assertEquals(ReasonCode.SPEC_LAYOUT_INVALID, ex1.getReasonCode());
        assertTrue(ex1.getMessage().contains("pattern"));

        Path pathLongRegex = tempDir.resolve("long_regex.xlsx");
        SpecXlsxBuilder.create()
                .sheet("test")
                .operation("POST /test")
                .header("Field", "Example", "Type", "Required", "Pattern")
                .row(0, "code", "123", "string", "Y", "a".repeat(513))
                .build(pathLongRegex);

        OverrideException ex2 = assertThrows(OverrideException.class, () -> ProviderSpecLoader.loadOpenAPI(pathLongRegex));
        assertEquals(ReasonCode.SPEC_LIMIT_EXCEEDED, ex2.getReasonCode());
    }

    @Test
    void queryHeaderPathRows_becomeParameters(@TempDir Path tempDir) throws Exception {
        Path path = tempDir.resolve("parameters.xlsx");
        SpecXlsxBuilder.create()
                .sheet("test")
                .operation("GET /users/{userId}")
                .header("Field", "Example", "Type", "Required", "Pattern", "Location")
                .row("userId", "USR-101", "string", "Y", null) // Needs location column
                .build(path);

        // Build with raw rows for location
        Path pathWithLoc = tempDir.resolve("params_loc.xlsx");
        SpecXlsxBuilder.create()
                .sheet("test")
                .operation("GET /users/{userId}")
                .header("Field", "Example", "Type", "Required", "Pattern", "Location")
                .row("userId", "123", "string", "Y", null, "path")
                .row("filter", "active", "string", "N", null, "query")
                .row("X-Client-Id", "CLI-9", "string", "Y", null, "header")
                .build(pathWithLoc);

        OpenAPI api = ProviderSpecLoader.loadOpenAPI(pathWithLoc);
        Operation op = api.getPaths().get("/users/{userId}").getGet();
        assertNotNull(op);
        List<Parameter> params = op.getParameters();
        assertNotNull(params);
        assertEquals(3, params.size());

        Parameter pathP = params.stream().filter(p -> p.getName().equals("userId")).findFirst().orElseThrow();
        assertEquals("path", pathP.getIn());
        assertTrue(pathP.getRequired());

        Parameter queryP = params.stream().filter(p -> p.getName().equals("filter")).findFirst().orElseThrow();
        assertEquals("query", queryP.getIn());
        assertFalse(queryP.getRequired());

        Parameter headerP = params.stream().filter(p -> p.getName().equals("X-Client-Id")).findFirst().orElseThrow();
        assertEquals("header", headerP.getIn());
        assertTrue(headerP.getRequired());
    }

    @Test
    void sameMethodPathOnTwoSheets_rejected(@TempDir Path tempDir) throws Exception {
        Path path = tempDir.resolve("dup_op.xlsx");
        SpecXlsxBuilder.create()
                .sheet("sheet1")
                .operation("POST /cust")
                .header("Field", "Example")
                .row(0, "a", "1", null, null, null)
                .sheet("sheet2")
                .operation("POST /cust")
                .header("Field", "Example")
                .row(0, "b", "2", null, null, null)
                .build(path);

        OverrideException ex = assertThrows(OverrideException.class, () -> ProviderSpecLoader.loadOpenAPI(path));
        assertEquals(ReasonCode.SPEC_LAYOUT_INVALID, ex.getReasonCode());
        assertTrue(ex.getMessage().contains("Duplicate operation"));
    }

    @Test
    void readmeSheet_ignored(@TempDir Path tempDir) throws Exception {
        Path path = tempDir.resolve("readme.xlsx");
        SpecXlsxBuilder.create()
                .sheet("README")
                // No operation row
                .row("Notes", "This is documentation")
                .sheet("getCustDetails")
                .operation("POST /getCustDetails")
                .header("Field", "Example")
                .row(0, "id", "123", null, null, null)
                .build(path);

        OpenAPI api = ProviderSpecLoader.loadOpenAPI(path);
        assertEquals(1, api.getPaths().size());
        assertTrue(api.getPaths().containsKey("/getCustDetails"));
    }

    @Test
    void zeroOperations_rejected_withOverrideWorkbookHint(@TempDir Path tempDir) throws Exception {
        Path path = tempDir.resolve("no_ops.xlsx");
        SpecXlsxBuilder.create()
                .sheet("README")
                .row("Notes", "No operations here")
                .build(path);

        OverrideException ex = assertThrows(OverrideException.class, () -> ProviderSpecLoader.loadOpenAPI(path));
        assertEquals(ReasonCode.SPEC_LAYOUT_INVALID, ex.getReasonCode());
        assertTrue(ex.getMessage().contains("File -> Import"));
    }

    @Test
    void operationPathMatchesBaseUrlVariableUrl(@TempDir Path tempDir) throws Exception {
        Path path = tempDir.resolve("base_url.xlsx");
        SpecXlsxBuilder.create()
                .sheet("getCustDetails")
                .operation("POST /getCustDetails")
                .header("Field", "Example")
                .row(0, "CustId", "123456", null, null, null)
                .build(path);

        OpenAPI api = ProviderSpecLoader.loadOpenAPI(path);
        SpecIndex index = new SpecIndex(api);

        var op = index.findByMethodAndPath("POST", "{{base_url}}/getCustDetails");
        assertTrue(op.isPresent());
        assertEquals("/getCustDetails", op.get().getPathPattern());
    }

    @Test
    void sampleSpecFile_loadsSuccessfully() throws Exception {
        Path sampleSpecPath = Path.of("examples/sample_spec.xlsx");
        assertTrue(Files.exists(sampleSpecPath));
        OpenAPI api = ProviderSpecLoader.loadOpenAPI(sampleSpecPath);
        assertNotNull(api);
        SpecIndex index = new SpecIndex(api);
        assertTrue(index.findByOperationId("getCustDetails").isPresent());
    }
}
