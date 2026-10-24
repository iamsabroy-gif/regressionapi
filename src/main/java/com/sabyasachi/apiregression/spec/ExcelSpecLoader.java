package com.sabyasachi.apiregression.spec;

import com.sabyasachi.apiregression.importer.ExcelParameterImporter;
import com.sabyasachi.apiregression.payload.OverrideException;
import com.sabyasachi.apiregression.payload.ReasonCode;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.Paths;
import io.swagger.v3.oas.models.media.*;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.parameters.RequestBody;
import io.swagger.v3.oas.models.servers.Server;
import org.apache.poi.poifs.filesystem.FileMagic;
import org.apache.poi.poifs.filesystem.POIFSFileSystem;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Pattern;

public final class ExcelSpecLoader {

    public static final int MAX_SPEC_WORKBOOK_BYTES = 5 * 1024 * 1024; // 5 MB
    public static final int MAX_SHEETS = 50;
    public static final int MAX_ROWS_PER_SHEET = 2000;
    public static final int MAX_COLS_PER_ROW = 10;
    public static final int MAX_DEPTH = 8;
    public static final int MAX_PROPERTIES_PER_OPERATION = 1000;
    public static final int MAX_KEY_LENGTH = 128;
    public static final int MAX_CELL_TEXT_LENGTH = 4096;
    public static final int MAX_OPERATIONS = 50;
    public static final int MAX_PATTERN_LENGTH = 512;

    private static final Set<String> IGNORED_SHEET_NAMES = Set.of("readme", "notes", "legend");
    private static final Set<String> DEFERRED_COLUMNS = Set.of("enum", "min", "max", "nullable", "readonly", "description");

    static {
        ExcelParameterImporter.configureZipSecurity();
    }

    private ExcelSpecLoader() {}

    public static ProviderSpecLoader.LoadedSpec load(Path path) throws IOException {
        byte[] bytes = java.nio.file.Files.readAllBytes(path);
        return load(path, bytes, ProviderSpecLoader.computeSha256(bytes));
    }

    public static boolean looksLikeXlsx(Path path) {
        if (path == null) return false;
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        if (!name.endsWith(".xlsx")) return false;
        try (InputStream is = java.nio.file.Files.newInputStream(path)) {
            byte[] magic = is.readNBytes(4);
            return magic.length == 4
                    && magic[0] == 0x50 && magic[1] == 0x4B
                    && magic[2] == 0x03 && magic[3] == 0x04;
        } catch (IOException e) {
            return false;
        }
    }

    public static ProviderSpecLoader.LoadedSpec load(Path path, byte[] bytes, String sha256) throws IOException {
        ExcelParameterImporter.configureZipSecurity();

        if (bytes == null || bytes.length == 0) {
            throw new OverrideException(ReasonCode.WORKBOOK_UNSUPPORTED, "Specification file is empty.");
        }
        if (bytes.length > MAX_SPEC_WORKBOOK_BYTES) {
            throw new OverrideException(ReasonCode.SPEC_LIMIT_EXCEEDED,
                    "Specification file exceeds 5 MB limit: " + bytes.length + " bytes.");
        }

        // Magic detection
        try (InputStream is = FileMagic.prepareToCheckMagic(new ByteArrayInputStream(bytes))) {
            FileMagic magic = FileMagic.valueOf(is);
            if (magic == FileMagic.OLE2) {
                try (POIFSFileSystem poifs = new POIFSFileSystem(is)) {
                    if (poifs.getRoot().hasEntry("EncryptionInfo")
                            || poifs.getRoot().hasEntry(org.apache.poi.poifs.crypt.EncryptionInfo.ENCRYPTION_INFO_ENTRY)) {
                        throw new OverrideException(ReasonCode.WORKBOOK_ENCRYPTED,
                                "The specification workbook is encrypted or password-protected.");
                    }
                } catch (OverrideException oe) {
                    throw oe;
                } catch (Exception ignored) {}
                throw new OverrideException(ReasonCode.WORKBOOK_UNSUPPORTED,
                        "Legacy .xls binary format is not supported for specifications. Please save as .xlsx.");
            } else if (magic != FileMagic.OOXML) {
                throw new OverrideException(ReasonCode.WORKBOOK_UNSUPPORTED,
                        "Only .xlsx Excel workbooks are supported as specifications.");
            }
        }

        List<String> warnings = new ArrayList<>();
        OpenAPI openAPI = new OpenAPI();
        openAPI.setOpenapi("3.0.3");
        Paths paths = new Paths();
        openAPI.setPaths(paths);

        Set<String> seenOperations = new HashSet<>();
        int operationsCount = 0;

        try (Workbook wb = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            // Check for external links without making any network requests
            try {
                if (wb instanceof XSSFWorkbook xwb) {
                    if (xwb.getPackage() != null) {
                        for (var part : xwb.getPackage().getParts()) {
                            if (part.getPartName().getName().contains("externalLink")) {
                                warnings.add("Workbook contains external links; external data was not fetched.");
                                break;
                            }
                        }
                    }
                }
            } catch (Exception ignored) {}

            int sheetCount = wb.getNumberOfSheets();
            if (sheetCount > MAX_SHEETS) {
                throw new OverrideException(ReasonCode.SPEC_LIMIT_EXCEEDED,
                        "Specification workbook has " + sheetCount + " sheets (maximum allowed is " + MAX_SHEETS + ").");
            }

            for (int s = 0; s < sheetCount; s++) {
                String sheetName = wb.getSheetName(s);
                if (IGNORED_SHEET_NAMES.contains(sheetName.trim().toLowerCase(Locale.ROOT))) {
                    continue;
                }

                if (wb.isSheetHidden(s) || wb.isSheetVeryHidden(s)) {
                    warnings.add("Sheet '" + sheetName + "' is hidden and was skipped.");
                    continue;
                }

                Sheet sheet = wb.getSheetAt(s);
                int lastRow = sheet.getLastRowNum();
                if (lastRow + 1 > MAX_ROWS_PER_SHEET) {
                    throw new OverrideException(ReasonCode.SPEC_LIMIT_EXCEEDED,
                            "Sheet '" + sheetName + "' has " + (lastRow + 1) + " rows (maximum allowed is " + MAX_ROWS_PER_SHEET + ").");
                }

                OperationSpec opSpec = parseSheet(sheet, sheetName, warnings);
                if (opSpec == null) {
                    continue; // Skipped sheet (e.g. no operation prologue)
                }

                String opKey = opSpec.method + " " + opSpec.cleanPath;
                if (!seenOperations.add(opKey)) {
                    throw new OverrideException(ReasonCode.SPEC_LAYOUT_INVALID,
                            "Duplicate operation '" + opKey + "' defined across multiple sheets (sheet '" + sheetName + "').");
                }

                operationsCount++;
                if (operationsCount > MAX_OPERATIONS) {
                    throw new OverrideException(ReasonCode.SPEC_LIMIT_EXCEEDED,
                            "Specification exceeds maximum limit of " + MAX_OPERATIONS + " operations.");
                }

                // Add to OpenAPI
                PathItem pathItem = paths.computeIfAbsent(opSpec.cleanPath, k -> new PathItem());
                if (opSpec.basePath != null && !opSpec.basePath.isBlank() && openAPI.getServers() == null) {
                    openAPI.setServers(List.of(new Server().url(opSpec.basePath.trim())));
                }

                attachOperation(pathItem, opSpec);
            }
        }

        if (operationsCount == 0) {
            throw new OverrideException(ReasonCode.SPEC_LAYOUT_INVALID,
                    "No operation sheets found in specification workbook; if this is an override workbook, use File -> Import.");
        }

        return new ProviderSpecLoader.LoadedSpec(
                openAPI,
                path.toAbsolutePath().normalize().toString(),
                sha256,
                "excel-1.0",
                warnings
        );
    }

    private static void attachOperation(PathItem pathItem, OperationSpec opSpec) {
        Operation operation = new Operation();
        operation.setOperationId(opSpec.operationId);

        // Parameters: query, header, path
        for (SpecNode paramNode : opSpec.parameters) {
            Parameter p = new Parameter();
            p.setName(paramNode.name);
            p.setIn(paramNode.location.toLowerCase(Locale.ROOT));
            if ("path".equalsIgnoreCase(paramNode.location)) {
                p.setRequired(true);
            } else {
                p.setRequired(paramNode.required);
            }
            p.setSchema(buildPrimitiveSchema(paramNode));
            operation.addParametersItem(p);
        }

        // Request body
        if (opSpec.rootBodyNode != null && (!opSpec.rootBodyNode.children.isEmpty() || opSpec.rootBodyNode.isArray)) {
            Schema<?> bodySchema = buildSchemaFromNode(opSpec.rootBodyNode);
            RequestBody rb = new RequestBody();
            rb.setRequired(true);
            Content content = new Content();
            MediaType mediaType = new MediaType();
            mediaType.setSchema(bodySchema);
            content.addMediaType(opSpec.contentType != null ? opSpec.contentType : "application/json", mediaType);
            rb.setContent(content);
            operation.setRequestBody(rb);
        }

        switch (opSpec.method.toUpperCase(Locale.ROOT)) {
            case "GET" -> pathItem.setGet(operation);
            case "POST" -> pathItem.setPost(operation);
            case "PUT" -> pathItem.setPut(operation);
            case "DELETE" -> pathItem.setDelete(operation);
            case "PATCH" -> pathItem.setPatch(operation);
            case "HEAD" -> pathItem.setHead(operation);
            case "OPTIONS" -> pathItem.setOptions(operation);
            default -> pathItem.setPost(operation);
        }
    }

    private static Schema<?> buildSchemaFromNode(SpecNode node) {
        if (node.isArray) {
            ArraySchema arraySchema = new ArraySchema();
            if (!node.children.isEmpty()) {
                if (node.children.size() == 1 && (node.children.get(0).name.equals("[0]") || node.children.get(0).name.equals("[]") || node.children.get(0).name.isEmpty())) {
                    arraySchema.setItems(buildSchemaFromNode(node.children.get(0)));
                } else {
                    ObjectSchema itemObj = new ObjectSchema();
                    Map<String, Schema> props = new LinkedHashMap<>();
                    List<String> requiredList = new ArrayList<>();
                    for (SpecNode child : node.children) {
                        props.put(child.name, buildSchemaFromNode(child));
                        if (child.required) {
                            requiredList.add(child.name);
                        }
                    }
                    itemObj.setProperties(props);
                    if (!requiredList.isEmpty()) {
                        itemObj.setRequired(requiredList);
                    }
                    arraySchema.setItems(itemObj);
                }
            } else {
                String itemType = node.type;
                if (itemType != null && itemType.endsWith("[]")) {
                    itemType = itemType.substring(0, itemType.length() - 2).trim();
                }
                SpecNode itemNode = new SpecNode(node.name, node.rowNumber);
                itemNode.type = (itemType != null && !itemType.isBlank()) ? itemType : "string";
                itemNode.pattern = node.pattern;
                itemNode.exampleValue = node.exampleValue;
                arraySchema.setItems(buildPrimitiveSchema(itemNode));
            }
            return arraySchema;
        }

        if (!node.children.isEmpty() || "object".equalsIgnoreCase(node.type)) {
            ObjectSchema objectSchema = new ObjectSchema();
            Map<String, Schema> props = new LinkedHashMap<>();
            List<String> requiredList = new ArrayList<>();

            for (SpecNode child : node.children) {
                props.put(child.name, buildSchemaFromNode(child));
                if (child.required) {
                    requiredList.add(child.name);
                }
            }
            objectSchema.setProperties(props);
            if (!requiredList.isEmpty()) {
                objectSchema.setRequired(requiredList);
            }
            return objectSchema;
        }

        return buildPrimitiveSchema(node);
    }

    private static Schema<?> buildPrimitiveSchema(SpecNode node) {
        Schema<?> s;
        String t = node.type != null ? node.type.toLowerCase(Locale.ROOT) : "string";
        switch (t) {
            case "integer", "int", "int32", "int64" -> {
                s = new IntegerSchema();
                if ("int64".equalsIgnoreCase(t)) s.setFormat("int64");
            }
            case "number", "float", "double", "decimal" -> {
                s = new NumberSchema();
                if ("float".equalsIgnoreCase(t)) s.setFormat("float");
                else if ("double".equalsIgnoreCase(t)) s.setFormat("double");
            }
            case "boolean", "bool" -> s = new BooleanSchema();
            default -> s = new StringSchema();
        }

        if (node.pattern != null && !node.pattern.isBlank()) {
            s.setPattern(node.pattern.trim());
        }
        if (node.exampleValue != null) {
            s.setExample(node.exampleValue);
        }
        return s;
    }

    private static class OperationSpec {
        String method;
        String cleanPath;
        String operationId;
        String basePath;
        String contentType = "application/json";
        SpecNode rootBodyNode;
        List<SpecNode> parameters = new ArrayList<>();
    }

    private static class SpecNode {
        String name;
        String type;
        String pattern;
        boolean required;
        String location = "body";
        boolean isArray;
        int rowNumber;
        Object exampleValue;
        List<SpecNode> children = new ArrayList<>();

        SpecNode(String name, int rowNumber) {
            this.name = name;
            this.rowNumber = rowNumber;
        }
    }

    private static class RawRow {
        int rowNum;
        String rawKey;
        String trimmedKey;
        int leadingSpaces;
        int excelIndent;
        String value;
        String type;
        String required;
        String pattern;
        String location;
        CellType valueCellType;
        boolean hasFormulaNoCache;
    }

    private static OperationSpec parseSheet(Sheet sheet, String sheetName, List<String> warnings) {
        int firstRow = sheet.getFirstRowNum();
        int lastRow = sheet.getLastRowNum();
        if (firstRow < 0 || lastRow < firstRow) {
            return null;
        }

        // 1. Scan for prologue & header row
        Map<String, String> prologue = new LinkedHashMap<>();
        int headerRowNum = -1;
        Map<String, Integer> colIndices = new HashMap<>();

        for (int r = firstRow; r <= lastRow && r < firstRow + 30; r++) {
            Row row = sheet.getRow(r);
            if (row == null) continue;

            // Check if this row is the column header
            Integer keyCol = findColumnIndex(row, "key", "field", "name");
            if (keyCol != null) {
                headerRowNum = r;
                mapColumns(row, colIndices, sheetName, warnings);
                break;
            }

            // Otherwise, check for prologue key/value
            Cell c0 = row.getCell(0);
            Cell c1 = row.getCell(1);
            if (c0 != null) {
                String k = getCellText(c0);
                String v = c1 != null ? getCellText(c1) : "";
                if (k != null && !k.isBlank()) {
                    prologue.put(k.trim().toLowerCase(Locale.ROOT), v != null ? v.trim() : "");
                }
            }
        }

        if (headerRowNum == -1) {
            warnings.add("Sheet '" + sheetName + "' skipped: no 'Key' column header found.");
            return null;
        }

        String operationStr = prologue.get("operation");
        if (operationStr == null || operationStr.isBlank()) {
            warnings.add("Sheet '" + sheetName + "' skipped: missing required prologue row 'operation METHOD /path'.");
            return null;
        }

        String[] parts = operationStr.trim().split("\\s+", 2);
        if (parts.length < 2) {
            throw new OverrideException(ReasonCode.SPEC_LAYOUT_INVALID,
                    "Invalid 'operation' prologue on sheet '" + sheetName + "': expected 'METHOD /path' but got '" + operationStr + "'.");
        }

        OperationSpec opSpec = new OperationSpec();
        opSpec.method = parts[0].toUpperCase(Locale.ROOT);
        String p = parts[1].trim();
        if (!p.startsWith("/")) p = "/" + p;
        opSpec.cleanPath = p;

        String opId = prologue.get("operationid");
        opSpec.operationId = (opId != null && !opId.isBlank()) ? opId : sheetName;
        opSpec.basePath = prologue.get("basepath");
        if (prologue.containsKey("contenttype") && !prologue.get("contenttype").isBlank()) {
            opSpec.contentType = prologue.get("contenttype");
        }

        // 2. Read data rows
        List<RawRow> rawRows = new ArrayList<>();
        boolean isBraceMode = false;

        for (int r = headerRowNum + 1; r <= lastRow; r++) {
            Row row = sheet.getRow(r);
            if (row == null) continue;

            int colCount = row.getLastCellNum();
            if (colCount > MAX_COLS_PER_ROW) {
                throw new OverrideException(ReasonCode.SPEC_LIMIT_EXCEEDED,
                        "Sheet '" + sheetName + "' row " + (r + 1) + " exceeds maximum column limit of " + MAX_COLS_PER_ROW);
            }

            Integer kCol = colIndices.get("key");
            if (kCol == null) break;
            Cell keyCell = row.getCell(kCol);
            if (keyCell == null) continue;

            if (keyCell.getCellType() == CellType.ERROR) {
                warnings.add("Cell error in Key column at sheet '" + sheetName + "' row " + (r + 1) + "; row skipped.");
                continue;
            }

            String rawKeyText = getCellRawText(keyCell);
            if (rawKeyText == null || rawKeyText.trim().isEmpty()) {
                continue;
            }

            RawRow raw = new RawRow();
            raw.rowNum = r + 1;
            raw.rawKey = rawKeyText;
            raw.trimmedKey = rawKeyText.trim();
            raw.excelIndent = keyCell.getCellStyle() != null ? keyCell.getCellStyle().getIndention() : 0;
            raw.leadingSpaces = countLeadingSpaces(rawKeyText);

            // Check Key length and control chars
            if (raw.trimmedKey.length() > MAX_KEY_LENGTH) {
                throw new OverrideException(ReasonCode.SPEC_LIMIT_EXCEEDED,
                        "Key '" + raw.trimmedKey + "' exceeds maximum length of " + MAX_KEY_LENGTH + " at row " + raw.rowNum);
            }
            validateKeyChars(raw.trimmedKey, raw.rowNum);

            // Read other columns
            if (colIndices.containsKey("value")) {
                Cell vCell = row.getCell(colIndices.get("value"));
                if (vCell != null) {
                    if (vCell.getCellType() == CellType.ERROR) {
                        warnings.add("Cell error in Value at row " + raw.rowNum + "; treated as blank.");
                    } else if (vCell.getCellType() == CellType.FORMULA) {
                        raw.value = getFormulaCachedValue(vCell);
                        if (raw.value == null) {
                            raw.hasFormulaNoCache = true;
                            warnings.add("FORMULA_NO_CACHE: Formula without cached value at row " + raw.rowNum + "; dropped.");
                        }
                    } else {
                        raw.value = getCellText(vCell);
                        raw.valueCellType = vCell.getCellType();
                    }
                }
            }

            if (colIndices.containsKey("type")) {
                Cell c = row.getCell(colIndices.get("type"));
                if (c != null) raw.type = getCellText(c);
            }

            if (colIndices.containsKey("required")) {
                Cell c = row.getCell(colIndices.get("required"));
                if (c != null) raw.required = getCellText(c);
            }

            if (colIndices.containsKey("pattern")) {
                Cell c = row.getCell(colIndices.get("pattern"));
                if (c != null) raw.pattern = getCellText(c);
            }

            if (colIndices.containsKey("location")) {
                Cell c = row.getCell(colIndices.get("location"));
                if (c != null) raw.location = getCellText(c);
            }

            if (raw.hasFormulaNoCache) {
                continue; // Drop formula row without cache
            }

            // Check for brace mode trigger
            if (raw.trimmedKey.endsWith("{") || raw.trimmedKey.endsWith("[")
                    || raw.trimmedKey.equals("{") || raw.trimmedKey.equals("}")
                    || raw.trimmedKey.equals("[") || raw.trimmedKey.equals("]")) {
                isBraceMode = true;
            }

            rawRows.add(raw);
        }

        if (rawRows.isEmpty()) {
            return opSpec;
        }

        // 3. Parse into tree of SpecNode
        int propCount = 0;
        SpecNode rootBody = new SpecNode("", 0);
        rootBody.type = "object";
        opSpec.rootBodyNode = rootBody;

        if (isBraceMode) {
            propCount = parseBraceMode(rawRows, rootBody, opSpec.parameters, sheetName, warnings);
        } else {
            propCount = parseIndentationMode(rawRows, rootBody, opSpec.parameters, sheetName, warnings);
        }

        if (propCount > MAX_PROPERTIES_PER_OPERATION) {
            throw new OverrideException(ReasonCode.SPEC_LIMIT_EXCEEDED,
                    "Sheet '" + sheetName + "' has " + propCount + " properties (maximum limit is " + MAX_PROPERTIES_PER_OPERATION + ").");
        }

        return opSpec;
    }

    private static int parseIndentationMode(List<RawRow> rows, SpecNode rootBody,
                                             List<SpecNode> parameters, String sheetName, List<String> warnings) {
        int totalProps = 0;
        int prevDepth = -1;
        Deque<SpecNode> stack = new ArrayDeque<>();
        stack.push(rootBody);

        for (int i = 0; i < rows.size(); i++) {
            RawRow row = rows.get(i);
            int odd = row.leadingSpaces % 2;
            if (odd != 0) {
                warnings.add("WHITESPACE: Odd leading spaces (" + row.leadingSpaces + ") at row " + row.rowNum + "; rounded down.");
            }
            int depth = row.excelIndent + (row.leadingSpaces / 2);
            if (depth > MAX_DEPTH) {
                throw new OverrideException(ReasonCode.SPEC_LIMIT_EXCEEDED,
                        "Depth " + depth + " exceeds maximum limit of " + MAX_DEPTH + " at row " + row.rowNum);
            }

            if (prevDepth == -1) {
                if (depth != 0) {
                    throw new OverrideException(ReasonCode.SPEC_LAYOUT_INVALID,
                            "First data row must have depth 0, but had depth " + depth + " at row " + row.rowNum);
                }
            } else {
                if (depth > prevDepth + 1) {
                    throw new OverrideException(ReasonCode.SPEC_LAYOUT_INVALID,
                            "Indentation jump from depth " + prevDepth + " to " + depth + " exceeds +1 at row " + row.rowNum);
                }
            }

            // Lookahead: is this row a parent?
            boolean hasChildren = (i + 1 < rows.size()) && ((rows.get(i + 1).excelIndent + (rows.get(i + 1).leadingSpaces / 2)) > depth);

            SpecNode node = createNodeFromRow(row, hasChildren, warnings);
            totalProps++;

            if ("query".equalsIgnoreCase(node.location) || "header".equalsIgnoreCase(node.location) || "path".equalsIgnoreCase(node.location)) {
                if (depth != 0) {
                    throw new OverrideException(ReasonCode.SPEC_LAYOUT_INVALID,
                            "Nested parameter not allowed for location '" + node.location + "' at row " + row.rowNum);
                }
                parameters.add(node);
                prevDepth = depth;
                continue;
            }

            // Adjust stack to point to current parent (depth)
            while (stack.size() > depth + 1) {
                stack.pop();
            }

            SpecNode parent = stack.peek();
            if (parent != null) {
                // Check duplicate under parent
                for (SpecNode existing : parent.children) {
                    if (existing.name.equalsIgnoreCase(node.name)) {
                        throw new OverrideException(ReasonCode.SPEC_LAYOUT_INVALID,
                                "Duplicate key '" + node.name + "' under parent at row " + row.rowNum + " (previously defined at row " + existing.rowNumber + ").");
                    }
                }
                parent.children.add(node);
            }

            if (hasChildren) {
                stack.push(node);
            }
            prevDepth = depth;
        }

        return totalProps;
    }

    private static int parseBraceMode(List<RawRow> rows, SpecNode rootBody,
                                       List<SpecNode> parameters, String sheetName, List<String> warnings) {
        int totalProps = 0;
        Deque<SpecNode> stack = new ArrayDeque<>();
        stack.push(rootBody);

        for (RawRow row : rows) {
            String key = row.trimmedKey;
            if (key.endsWith(",")) {
                key = key.substring(0, key.length() - 1).trim();
            }

            if (key.equals("}") || key.equals("]")) {
                if (stack.size() <= 1) {
                    throw new OverrideException(ReasonCode.SPEC_LAYOUT_INVALID,
                            "Unmatched closing brace/bracket '" + key + "' at row " + row.rowNum);
                }
                stack.pop();
                continue;
            }

            if (key.equals("{")) {
                // Lone { inside array container -> exemplar element
                SpecNode parent = stack.peek();
                if (parent == null || !parent.isArray) {
                    throw new OverrideException(ReasonCode.SPEC_LAYOUT_INVALID,
                            "Lone '{' is only permitted directly inside array brackets at row " + row.rowNum);
                }
                SpecNode elem = new SpecNode("__elem__", row.rowNum);
                elem.type = "object";
                parent.children.add(elem);
                stack.push(elem);
                continue;
            }

            boolean opensObject = key.endsWith("{");
            boolean opensArray = key.endsWith("[");

            String cleanKey = key;
            if (opensObject) {
                cleanKey = key.substring(0, key.length() - 1).trim();
            } else if (opensArray) {
                cleanKey = key.substring(0, key.length() - 1).trim();
            }

            row.trimmedKey = cleanKey;
            SpecNode node = createNodeFromRow(row, opensObject || opensArray, warnings);
            if (opensArray) {
                node.isArray = true;
            }
            totalProps++;

            if ("query".equalsIgnoreCase(node.location) || "header".equalsIgnoreCase(node.location) || "path".equalsIgnoreCase(node.location)) {
                if (stack.size() > 1) {
                    throw new OverrideException(ReasonCode.SPEC_LAYOUT_INVALID,
                            "Nested parameter not allowed for location '" + node.location + "' at row " + row.rowNum);
                }
                parameters.add(node);
                continue;
            }

            SpecNode parent = stack.peek();
            if (parent != null) {
                for (SpecNode existing : parent.children) {
                    if (existing.name.equalsIgnoreCase(node.name)) {
                        throw new OverrideException(ReasonCode.SPEC_LAYOUT_INVALID,
                                "Duplicate key '" + node.name + "' under parent at row " + row.rowNum + " (previously defined at row " + existing.rowNumber + ").");
                    }
                }
                parent.children.add(node);
            }

            if (opensObject || opensArray) {
                stack.push(node);
            }
        }

        if (stack.size() > 1) {
            SpecNode unclosed = stack.peek();
            throw new OverrideException(ReasonCode.SPEC_LAYOUT_INVALID,
                    "Unclosed brace or bracket for property '" + (unclosed != null ? unclosed.name : "") + "' at end of sheet '" + sheetName + "'.");
        }

        return totalProps;
    }

    private static SpecNode createNodeFromRow(RawRow row, boolean hasChildren, List<String> warnings) {
        String key = row.trimmedKey;
        boolean isArray = false;

        if (key.endsWith("[]")) {
            isArray = true;
            key = key.substring(0, key.length() - 2).trim();
        } else if (key.endsWith("[0]")) {
            isArray = true;
            key = key.substring(0, key.length() - 3).trim();
        } else if (key.matches(".*\\[\\d+\\]$")) {
            throw new OverrideException(ReasonCode.SPEC_LAYOUT_INVALID,
                    "Array index other than [0] is not permitted (only zero / [0] allowed): '" + row.trimmedKey + "' at row " + row.rowNum);
        }

        if (key.isEmpty()) {
            throw new OverrideException(ReasonCode.SPEC_LAYOUT_INVALID,
                    "Empty property name at row " + row.rowNum);
        }

        SpecNode node = new SpecNode(key, row.rowNum);
        node.location = (row.location != null && !row.location.isBlank()) ? row.location.trim().toLowerCase(Locale.ROOT) : "body";

        // Required
        if (row.required != null) {
            String req = row.required.trim().toLowerCase(Locale.ROOT);
            node.required = req.equals("y") || req.equals("yes") || req.equals("true") || req.equals("1");
        }

        // Pattern validation
        if (row.pattern != null && !row.pattern.isBlank()) {
            String p = row.pattern.trim();
            if (p.length() > MAX_PATTERN_LENGTH) {
                throw new OverrideException(ReasonCode.SPEC_LIMIT_EXCEEDED,
                        "Pattern exceeds " + MAX_PATTERN_LENGTH + " characters at row " + row.rowNum);
            }
            try {
                Pattern.compile(p);
            } catch (Exception e) {
                throw new OverrideException(ReasonCode.SPEC_LAYOUT_INVALID,
                        "Invalid regex pattern at row " + row.rowNum + ": " + e.getMessage(), e);
            }
            node.pattern = p;
        }

        // Type determination
        String declaredType = row.type != null ? row.type.trim().toLowerCase(Locale.ROOT) : null;
        if (declaredType != null && declaredType.endsWith("[]")) {
            isArray = true;
            declaredType = declaredType.substring(0, declaredType.length() - 2).trim();
        }

        node.isArray = isArray;

        if (hasChildren) {
            if (row.value != null && !row.value.isBlank()) {
                throw new OverrideException(ReasonCode.SPEC_LAYOUT_INVALID,
                        "Leaf property has both primitive value and child properties / children at row " + row.rowNum);
            }
            node.type = (declaredType != null && !declaredType.isBlank()) ? declaredType : (isArray ? "array" : "object");
            return node;
        }

        // Leaf node
        if (row.value != null && !row.value.isBlank()) {
            node.exampleValue = row.value.trim();
        }

        if (declaredType != null && !declaredType.isBlank()) {
            node.type = declaredType;
            return node;
        }

        // Infer from Value
        if (row.value == null || row.value.isBlank()) {
            warnings.add("No type or example value for field '" + node.name + "' at row " + row.rowNum + "; defaulting to string.");
            node.type = "string";
            return node;
        }

        String val = row.value.trim();
        if (row.valueCellType == CellType.BOOLEAN) {
            node.type = "boolean";
        } else if (row.valueCellType == CellType.NUMERIC) {
            if (val.contains(".") || val.contains("e") || val.contains("E")) {
                node.type = "number";
            } else {
                node.type = "integer";
            }
            String lowerKey = node.name.toLowerCase(Locale.ROOT);
            if (lowerKey.contains("id") || lowerKey.contains("code") || lowerKey.contains("account") || lowerKey.contains("num") || lowerKey.contains("ref") || lowerKey.contains("no")) {
                String digitsOnly = val.replaceAll("[^0-9]", "");
                if (digitsOnly.length() >= 6) {
                    warnings.add("SPEC_CELL_TYPE_COERCED: Numeric cell for '" + node.name + "' coerced to string/integer; if leading zeros were dropped by Excel, reformat cell as Text");
                }
            }
        } else {
            // Text cell
            node.type = "string";
        }

        return node;
    }

    private static void mapColumns(Row headerRow, Map<String, Integer> colIndices, String sheetName, List<String> warnings) {
        int lastCol = headerRow.getLastCellNum();
        if (lastCol > MAX_COLS_PER_ROW) {
            throw new OverrideException(ReasonCode.SPEC_LIMIT_EXCEEDED,
                    "Sheet '" + sheetName + "' header has " + lastCol + " columns (maximum limit is " + MAX_COLS_PER_ROW + ").");
        }

        for (int c = 0; c < lastCol; c++) {
            Cell cell = headerRow.getCell(c);
            if (cell == null) continue;
            String text = getCellText(cell);
            if (text == null || text.isBlank()) continue;
            String norm = text.trim().toLowerCase(Locale.ROOT);
            if (norm.equals("field") || norm.equals("name")) norm = "key";
            if (norm.equals("example") || norm.equals("sample")) norm = "value";
            if (norm.equals("mandatory")) norm = "required";

            if (DEFERRED_COLUMNS.contains(norm)) {
                warnings.add("Column '" + text.trim() + "' in sheet '" + sheetName + "' is deferred in v1 and was ignored.");
            }

            colIndices.put(norm, c);
        }
    }

    private static Integer findColumnIndex(Row row, String... aliases) {
        int lastCol = row.getLastCellNum();
        for (int c = 0; c < lastCol; c++) {
            Cell cell = row.getCell(c);
            if (cell == null) continue;
            String t = getCellText(cell);
            if (t == null) continue;
            String trimmed = t.trim().toLowerCase(Locale.ROOT);
            for (String alias : aliases) {
                if (trimmed.equals(alias)) {
                    return c;
                }
            }
        }
        return null;
    }

    private static void validateKeyChars(String key, int rowNum) {
        for (int i = 0; i < key.length(); i++) {
            char ch = key.charAt(i);
            if (ch < 32 && ch != '\t') {
                throw new OverrideException(ReasonCode.SPEC_LAYOUT_INVALID,
                        "Key contains control characters at row " + rowNum);
            }
        }
        if (key.contains("$ref") || key.contains("#/")) {
            throw new OverrideException(ReasonCode.SPEC_LAYOUT_INVALID,
                    "$ref or #/ is not allowed in specification key at row " + rowNum);
        }
    }

    private static int countLeadingSpaces(String s) {
        int count = 0;
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch == ' ') count++;
            else if (ch == '\t') count += 2;
            else break;
        }
        return count;
    }

    private static String getCellRawText(Cell cell) {
        if (cell == null) return null;
        if (cell.getCellType() == CellType.STRING) {
            return cell.getStringCellValue();
        }
        return getCellText(cell);
    }

    private static String getCellText(Cell cell) {
        if (cell == null) return null;
        return switch (cell.getCellType()) {
            case STRING -> cell.getStringCellValue();
            case NUMERIC -> {
                double val = cell.getNumericCellValue();
                if (val == Math.floor(val) && !Double.isInfinite(val)) {
                    yield String.valueOf((long) val);
                }
                yield String.valueOf(val);
            }
            case BOOLEAN -> String.valueOf(cell.getBooleanCellValue());
            case FORMULA -> getFormulaCachedValue(cell);
            default -> null;
        };
    }

    private static String getFormulaCachedValue(Cell cell) {
        try {
            if (cell instanceof org.apache.poi.xssf.usermodel.XSSFCell xssfCell) {
                if (!xssfCell.getCTCell().isSetV() || xssfCell.getCTCell().getV() == null || xssfCell.getCTCell().getV().isEmpty()) {
                    return null;
                }
            }
            CellType cachedType = cell.getCachedFormulaResultType();
            if (cachedType == CellType._NONE || cachedType == CellType.BLANK || cachedType == CellType.ERROR) {
                return null;
            }
            return switch (cachedType) {
                case STRING -> cell.getStringCellValue();
                case NUMERIC -> {
                    double val = cell.getNumericCellValue();
                    if (val == Math.floor(val) && !Double.isInfinite(val)) {
                        yield String.valueOf((long) val);
                    }
                    yield String.valueOf(val);
                }
                case BOOLEAN -> String.valueOf(cell.getBooleanCellValue());
                default -> null;
            };
        } catch (Exception e) {
            return null;
        }
    }
}
