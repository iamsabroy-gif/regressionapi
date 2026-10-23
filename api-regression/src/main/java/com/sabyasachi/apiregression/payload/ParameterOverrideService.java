package com.sabyasachi.apiregression.payload;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sabyasachi.apiregression.importer.ExcelParameterImporter;
import com.sabyasachi.apiregression.importer.ParamRow;
import com.sabyasachi.apiregression.importer.SheetParams;
import com.sabyasachi.apiregression.model.OverrideProvenance;
import com.sabyasachi.apiregression.model.RequestSnapshot;
import com.sabyasachi.apiregression.model.TestCase;
import com.sabyasachi.apiregression.model.TestSuite;
import com.sabyasachi.apiregression.spec.ConstraintValidator;
import com.sabyasachi.apiregression.spec.ProviderSpecLoader;
import com.sabyasachi.apiregression.spec.SchemaNavigator;
import com.sabyasachi.apiregression.spec.SpecIndex;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.parameters.Parameter;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;

@SuppressWarnings("rawtypes")
public class ParameterOverrideService {

    private final ExcelParameterImporter excelImporter;
    private final ProviderSpecLoader specLoader;
    private final JsonTemplateCodec templateCodec;

    public ParameterOverrideService() {
        this(new ExcelParameterImporter(), new ProviderSpecLoader(), new JsonTemplateCodec());
    }

    public ParameterOverrideService(ExcelParameterImporter excelImporter, ProviderSpecLoader specLoader, JsonTemplateCodec templateCodec) {
        this.excelImporter = excelImporter;
        this.specLoader = specLoader;
        this.templateCodec = templateCodec;
    }

    public OverrideReport dryRun(TestSuite suite, Path xlsxPath, Path specPath, OverrideOptions options) throws IOException {
        return process(suite, xlsxPath, specPath, options != null ? options : new OverrideOptions(), true);
    }

    public OverrideReport apply(TestSuite suite, Path xlsxPath, Path specPath, OverrideOptions options) throws IOException {
        return process(suite, xlsxPath, specPath, options != null ? options : new OverrideOptions(), false);
    }

    private OverrideReport process(TestSuite suite, Path xlsxPath, Path specPath, OverrideOptions options, boolean isDryRun) throws IOException {
        if (suite == null) {
            throw new IllegalArgumentException("TestSuite cannot be null");
        }
        if (xlsxPath == null || !Files.exists(xlsxPath)) {
            throw new IOException("Excel workbook not found: " + xlsxPath);
        }

        OverrideReport report = new OverrideReport();
        report.setWorkbook(xlsxPath.toAbsolutePath().toString());
        report.setWorkbookSha256(ProviderSpecLoader.computeSha256(Files.readAllBytes(xlsxPath)));
        report.setPolicy(options.getConstraintPolicy());
        report.setDryRun(isDryRun);
        report.setCreatedAt(Instant.now());

        // 1. Load Spec if provided or set on suite
        Path effectiveSpecPath = specPath;
        if (effectiveSpecPath == null && suite.getSpecPath() != null && !suite.getSpecPath().isBlank()) {
            effectiveSpecPath = Path.of(suite.getSpecPath());
        }

        SpecIndex specIndex = null;
        SchemaNavigator schemaNavigator = null;
        if (effectiveSpecPath != null && Files.exists(effectiveSpecPath)) {
            try {
                ProviderSpecLoader.LoadedSpec loadedSpec = specLoader.load(effectiveSpecPath);
                report.setSpecPath(loadedSpec.getSpecPath());
                report.setSpecSha256(loadedSpec.getSha256());
                report.setSpecVersion(loadedSpec.getSpecVersion());
                report.setSpecMessages(loadedSpec.getMessages());
                specIndex = new SpecIndex(loadedSpec.getOpenAPI());
                schemaNavigator = new SchemaNavigator(loadedSpec.getOpenAPI());
            } catch (Exception e) {
                report.getSpecMessages().add("Spec parse warning: " + e.getMessage());
            }
        }

        // 2. Parse Excel Workbook
        List<SheetParams> parsedSheets = excelImporter.parse(xlsxPath, options.isSkipHiddenSheets(), options.getMaxRowsPerSheet());
        Set<TestCase> targetedInThisWorkbook = new HashSet<>();
        TypeCoercer coercer = new TypeCoercer(templateCodec.getMapper());

        for (SheetParams sheet : parsedSheets) {
            SheetSummary summary = new SheetSummary(sheet.getSheetName(), sheet.getTarget());
            report.getSheets().add(summary);

            // Match TestCase
            MatchResult matchResult = matchTestCase(suite, sheet.getTarget(), specIndex);
            if (matchResult.matchCount == 0) {
                summary.setStatus("UNMATCHED");
                summary.setErrors(1);
                OverrideEntry entry = new OverrideEntry();
                entry.setSheet(sheet.getSheetName());
                entry.setRow(1);
                entry.setParameter("target");
                entry.setAction(OverrideAction.ERROR);
                entry.setReasonCode("UNMATCHED_TARGET");
                entry.setReason("No TestCase in the suite matches target: '" + sheet.getTarget() + "'");
                report.getEntries().add(entry);
                continue;
            } else if (matchResult.matchCount > 1) {
                summary.setStatus("AMBIGUOUS_TARGET");
                summary.setErrors(1);
                OverrideEntry entry = new OverrideEntry();
                entry.setSheet(sheet.getSheetName());
                entry.setRow(1);
                entry.setParameter("target");
                entry.setAction(OverrideAction.ERROR);
                entry.setReasonCode("AMBIGUOUS_TARGET");
                entry.setReason("Multiple TestCases match target: '" + sheet.getTarget() + "'");
                entry.setCandidates(matchResult.candidateNames);
                report.getEntries().add(entry);
                continue;
            }

            TestCase targetTc = matchResult.testCase;
            summary.setTargetTestCase(targetTc.getName());
            summary.setMatchedBy(matchResult.matchedBy);

            // Check duplicate sheet targeting same TestCase
            if (targetedInThisWorkbook.contains(targetTc)) {
                summary.setStatus("CONFLICT");
                summary.setErrors(1);
                OverrideEntry entry = new OverrideEntry();
                entry.setSheet(sheet.getSheetName());
                entry.setRow(1);
                entry.setParameter("target");
                entry.setAction(OverrideAction.ERROR);
                entry.setReasonCode("CONFLICT");
                entry.setReason("Another sheet in this workbook already targets TestCase: '" + targetTc.getName() + "'");
                report.getEntries().add(entry);
                continue;
            }
            targetedInThisWorkbook.add(targetTc);

            // Idempotency: Always start from baseline!
            if (!isDryRun && targetTc.getBaseline() == null) {
                targetTc.promoteToBaseline();
            }
            RequestSnapshot baseline = targetTc.getBaseline();
            if (baseline == null) {
                baseline = RequestSnapshot.from(targetTc);
            }

            // Setup working copies
            String workingUrl = baseline.getUrl();
            Map<String, String> workingHeaders = new LinkedHashMap<>(baseline.getHeaders());
            String workingBody = baseline.getBodyTemplate();

            // Decode body template
            JsonTemplateCodec.DecodedTemplate decodedTemplate = null;
            ObjectNode rootBodyNode = null;
            try {
                decodedTemplate = templateCodec.decode(workingBody);
                rootBodyNode = decodedTemplate.getRootNode();
            } catch (Exception e) {
                summary.setErrors(summary.getErrors() + 1);
                OverrideEntry entry = new OverrideEntry();
                entry.setSheet(sheet.getSheetName());
                entry.setRow(1);
                entry.setParameter("body");
                entry.setAction(OverrideAction.ERROR);
                entry.setReasonCode("BODY_NOT_JSON");
                entry.setReason("Failed to parse JSON body template: " + e.getMessage());
                report.getEntries().add(entry);
                continue;
            }

            // Find spec operation
            SpecIndex.OperationMatch opMatch = null;
            Schema requestBodySchema = null;
            if (specIndex != null) {
                try {
                    opMatch = specIndex.matchOperation(targetTc.getOperationId(), targetTc.getMethod(), workingUrl);
                    if (opMatch != null && opMatch.getOperation() != null && schemaNavigator != null) {
                        requestBodySchema = schemaNavigator.extractJsonRequestBodySchema(opMatch.getOperation().getRequestBody());
                    }
                } catch (Exception ignored) {
                }
            }

            // Process each row
            for (ParamRow row : sheet.getRows()) {
                OverrideEntry entry = new OverrideEntry();
                entry.setSheet(sheet.getSheetName());
                entry.setRow(row.getRow());
                entry.setParameter(row.getParameter());
                entry.setRawValue(row.getRawValue());
                entry.getWarnings().addAll(row.getWarnings());

                if (!row.isEnabled()) {
                    entry.setAction(OverrideAction.SKIPPED);
                    entry.setReasonCode("DISABLED");
                    entry.setReason("Row is disabled in sheet.");
                    summary.setSkipped(summary.getSkipped() + 1);
                    report.getEntries().add(entry);
                    continue;
                }

                if (row.getRawValue() == null && "BLANK".equalsIgnoreCase(row.getCellKind())) {
                    entry.setAction(OverrideAction.SKIPPED);
                    entry.setReasonCode("BLANK");
                    entry.setReason("Cell is blank; field left as imported.");
                    summary.setSkipped(summary.getSkipped() + 1);
                    report.getEntries().add(entry);
                    continue;
                }

                try {
                    String paramStr = row.getParameter().trim();
                    String explicitLoc = row.getExplicitLocation();

                    if (explicitLoc == null || explicitLoc.isBlank()) {
                        String lower = paramStr.toLowerCase();
                        if (lower.startsWith("body.")) {
                            explicitLoc = "body";
                            paramStr = paramStr.substring(5).trim();
                        } else if (lower.startsWith("query.")) {
                            explicitLoc = "query";
                            paramStr = paramStr.substring(6).trim();
                        } else if (lower.startsWith("header.")) {
                            explicitLoc = "header";
                            paramStr = paramStr.substring(7).trim();
                        } else if (lower.startsWith("path.")) {
                            explicitLoc = "path";
                            paramStr = paramStr.substring(5).trim();
                        }
                    }

                    ParamPath paramPath = ParamPath.parse(paramStr);
                    ParamLocation targetLocation = determineLocation(explicitLoc, paramPath, opMatch);
                    entry.setLocation(targetLocation);

                    if (targetLocation == ParamLocation.BODY) {
                        processBodyOverride(row, paramPath, rootBodyNode, requestBodySchema, schemaNavigator, coercer, options, entry, summary);
                    } else if (targetLocation == ParamLocation.QUERY) {
                        RequestPartOverrider.OverridePartResult partRes = RequestPartOverrider.overrideQueryParam(workingUrl, paramPath.getLeafName(), row.getRawValue());
                        workingUrl = partRes.getUpdatedUrl();
                        entry.setAction(partRes.isAdded() ? OverrideAction.ADDED : OverrideAction.MODIFIED);
                        entry.setResolvedPath("query." + paramPath.getLeafName());
                        if (partRes.isAdded()) summary.setAdded(summary.getAdded() + 1);
                        else summary.setModified(summary.getModified() + 1);
                    } else if (targetLocation == ParamLocation.HEADER) {
                        RequestPartOverrider.OverridePartResult partRes = RequestPartOverrider.overrideHeader(workingHeaders, paramPath.getLeafName(), row.getRawValue());
                        workingHeaders = partRes.getUpdatedHeaders();
                        entry.setAction(partRes.isAdded() ? OverrideAction.ADDED : OverrideAction.MODIFIED);
                        entry.setResolvedPath("header." + paramPath.getLeafName());
                        if (partRes.isAdded()) summary.setAdded(summary.getAdded() + 1);
                        else summary.setModified(summary.getModified() + 1);
                    } else if (targetLocation == ParamLocation.PATH) {
                        if (opMatch == null) {
                            throw new IllegalArgumentException("Path parameter override requires spec path template, but spec was unavailable or operation not found.");
                        }
                        RequestPartOverrider.OverridePartResult partRes = RequestPartOverrider.overridePathParam(workingUrl, opMatch.getPathTemplate(), paramPath.getLeafName(), row.getRawValue());
                        workingUrl = partRes.getUpdatedUrl();
                        entry.setAction(OverrideAction.MODIFIED);
                        entry.setResolvedPath("path." + paramPath.getLeafName());
                        summary.setModified(summary.getModified() + 1);
                    }

                } catch (Exception ex) {
                    entry.setAction(OverrideAction.ERROR);
                    entry.setReasonCode("OVERRIDE_FAILED");
                    entry.setReason(ex.getMessage());
                    summary.setErrors(summary.getErrors() + 1);
                }

                report.getEntries().add(entry);
            }

            // Summary status
            if (summary.getErrors() > 0) {
                summary.setStatus("PARTIAL");
            } else {
                summary.setStatus("APPLIED");
            }

            // If not dry-run and (no errors or applyWithErrors allowed): Apply to target TestCase
            if (!isDryRun && (summary.getErrors() == 0 || options.isApplyWithErrors())) {
                try {
                    String updatedBody = templateCodec.encode(rootBodyNode, decodedTemplate.getUnquotedPlaceholders());
                    targetTc.setUrl(workingUrl);
                    targetTc.setHeaders(workingHeaders);
                    targetTc.setBodyTemplate(updatedBody);

                    OverrideProvenance prov = new OverrideProvenance();
                    prov.setWorkbookFile(xlsxPath.getFileName().toString());
                    prov.setWorkbookSha256(report.getWorkbookSha256());
                    prov.setSheet(sheet.getSheetName());
                    prov.setSpecSha256(report.getSpecSha256());
                    prov.setAppliedAt(Instant.now());
                    prov.setModified(summary.getModified());
                    prov.setAdded(summary.getAdded());
                    prov.setErrors(summary.getErrors());
                    prov.setWarnings(summary.getWarnings());
                    targetTc.setLastOverride(prov);
                } catch (Exception ex) {
                    summary.setStatus("ERROR");
                    summary.setErrors(summary.getErrors() + 1);
                }
            }
        }

        return report;
    }

    private void processBodyOverride(ParamRow row, ParamPath paramPath, ObjectNode rootNode,
                                     Schema requestBodySchema, SchemaNavigator schemaNavigator,
                                     TypeCoercer coercer, OverrideOptions options,
                                     OverrideEntry entry, SheetSummary summary) {
        ParamPath effectivePath = paramPath;
        Schema targetPropSchema = null;
        String specPointer = null;

        // 1. Resolve bare name if needed
        if (paramPath.isBareName()) {
            List<String> bodyCandidates = PayloadOverrider.findCandidatePathsForLeaf(rootNode, paramPath.getLeafName());
            if (bodyCandidates.size() == 1) {
                effectivePath = ParamPath.parse(bodyCandidates.get(0));
            } else if (bodyCandidates.size() > 1) {
                entry.setAction(OverrideAction.ERROR);
                entry.setReasonCode("AMBIGUOUS_PROPERTY");
                entry.setReason("Multiple fields in JSON payload match bare name '" + paramPath.getLeafName() + "'");
                entry.setCandidates(bodyCandidates);
                summary.setErrors(summary.getErrors() + 1);
                return;
            } else {
                // Not in payload -> look up in spec!
                if (schemaNavigator == null || requestBodySchema == null) {
                    entry.setAction(OverrideAction.ERROR);
                    entry.setReasonCode("SPEC_UNAVAILABLE");
                    entry.setReason("Field '" + paramPath.getLeafName() + "' is not present in payload and provider spec was not available for gap-fill.");
                    summary.setErrors(summary.getErrors() + 1);
                    return;
                }

                List<SchemaNavigator.ResolvedField> specCandidates = schemaNavigator.findSchemaPathsForLeaf(requestBodySchema, paramPath.getLeafName());
                if (specCandidates.isEmpty()) {
                    entry.setAction(OverrideAction.ERROR);
                    entry.setReasonCode("NOT_IN_SPEC");
                    entry.setReason("Field '" + paramPath.getLeafName() + "' is neither in the payload nor declared in the provider OpenAPI spec.");
                    summary.setErrors(summary.getErrors() + 1);
                    return;
                } else if (specCandidates.size() > 1) {
                    entry.setAction(OverrideAction.ERROR);
                    entry.setReasonCode("AMBIGUOUS_SPEC_PROPERTY");
                    entry.setReason("Field '" + paramPath.getLeafName() + "' matches multiple schema locations in provider spec.");
                    List<String> candList = new ArrayList<>();
                    for (var sc : specCandidates) candList.add(sc.getJsonPointer());
                    entry.setCandidates(candList);
                    summary.setErrors(summary.getErrors() + 1);
                    return;
                } else {
                    SchemaNavigator.ResolvedField rf = specCandidates.get(0);
                    if (rf.isPassesThroughArray()) {
                        entry.setAction(OverrideAction.ERROR);
                        entry.setReasonCode("NEEDS_INDEX");
                        entry.setReason("Field '" + paramPath.getLeafName() + "' is inside an array in spec schema. Please specify an array index, e.g. " + rf.getJsonPointer().replace("[*]", "[0]"));
                        summary.setErrors(summary.getErrors() + 1);
                        return;
                    }
                    effectivePath = ParamPath.parse(rf.getJsonPointer());
                    targetPropSchema = rf.getSchema();
                    specPointer = rf.getSpecPointer();
                }
            }
        }

        entry.setResolvedPath(effectivePath.toJsonPointer());
        entry.setSpecPointer(specPointer);

        // 2. Find existing node in body
        JsonNode existingNode = PayloadOverrider.findExistingNode(rootNode, effectivePath);
        entry.setOldValue(existingNode);

        // If explicit path and not in payload, check spec for type & validation
        if (existingNode == null && targetPropSchema == null && schemaNavigator != null && requestBodySchema != null) {
            targetPropSchema = schemaNavigator.navigateExplicitPath(requestBodySchema, effectivePath);
        }

        // 3. Coerce value
        String specType = (targetPropSchema != null) ? targetPropSchema.getType() : null;
        String specFormat = (targetPropSchema != null) ? targetPropSchema.getFormat() : null;

        List<String> coercionWarnings = new ArrayList<>();
        TypeCoercer.CoercionResult coercionRes = coercer.coerce(
                row.getRawValue(),
                row.getExplicitType(),
                specType,
                specFormat,
                existingNode,
                row.getCellKind(),
                coercionWarnings
        );
        entry.setCoercedValue(coercionRes.getNode());
        entry.getWarnings().addAll(coercionRes.getWarnings());

        // 4. Constraint validation
        ConstraintValidator.ValidationResult valRes = ConstraintValidator.validate(
                targetPropSchema,
                coercionRes.getNode(),
                row.getRawValue(),
                options.getConstraintPolicy(),
                existingNode == null
        );

        if (!valRes.isValid()) {
            entry.setAction(OverrideAction.ERROR);
            entry.setReasonCode("CONSTRAINT_VIOLATION");
            entry.setReason(valRes.getErrorMessage());
            summary.setErrors(summary.getErrors() + 1);
            return;
        }
        if (valRes.getWarningMessage() != null) {
            entry.addWarning(valRes.getWarningMessage());
            summary.setWarnings(summary.getWarnings() + 1);
        }

        // 5. Apply override to body
        PayloadOverrider.ApplyResult applyRes = PayloadOverrider.applyOverride(rootNode, effectivePath, coercionRes.getNode());
        entry.setAction(applyRes.isAdded() ? OverrideAction.ADDED : OverrideAction.MODIFIED);

        if (applyRes.isAdded()) {
            summary.setAdded(summary.getAdded() + 1);
        } else {
            summary.setModified(summary.getModified() + 1);
        }
    }

    private ParamLocation determineLocation(String explicitLocation, ParamPath paramPath, SpecIndex.OperationMatch opMatch) {
        if (explicitLocation != null && !explicitLocation.isBlank()) {
            switch (explicitLocation.trim().toLowerCase()) {
                case "query": return ParamLocation.QUERY;
                case "path": return ParamLocation.PATH;
                case "header": return ParamLocation.HEADER;
                case "body": return ParamLocation.BODY;
            }
        }

        // If parameter path is explicit path (starts with $, contains . or [) -> default BODY
        if (paramPath.isExplicitPath()) {
            return ParamLocation.BODY;
        }

        // Check if bare name matches query/header/path in operation
        if (opMatch != null) {
            Parameter p = opMatch.findParameter(paramPath.getLeafName(), null);
            if (p != null && p.getIn() != null) {
                switch (p.getIn().toLowerCase()) {
                    case "query": return ParamLocation.QUERY;
                    case "path": return ParamLocation.PATH;
                    case "header": return ParamLocation.HEADER;
                }
            }
        }

        return ParamLocation.BODY;
    }

    private static class MatchResult {
        TestCase testCase;
        int matchCount = 0;
        String matchedBy;
        List<String> candidateNames = new ArrayList<>();
    }

    private MatchResult matchTestCase(TestSuite suite, String target, SpecIndex specIndex) {
        MatchResult res = new MatchResult();
        if (suite == null || suite.getTestCases() == null || target == null || target.isBlank()) {
            return res;
        }
        String cleanTarget = target.trim();

        // 1. Exact match on TestCase.name
        for (TestCase tc : suite.getTestCases()) {
            if (cleanTarget.equals(tc.getName())) {
                res.matchCount++;
                res.testCase = tc;
                res.matchedBy = "NAME";
                res.candidateNames.add(tc.getName());
            }
        }
        if (res.matchCount == 1) return res;
        if (res.matchCount > 1) return res;

        // 2. Exact match on operationId
        for (TestCase tc : suite.getTestCases()) {
            if (cleanTarget.equals(tc.getOperationId())) {
                res.matchCount++;
                res.testCase = tc;
                res.matchedBy = "OPERATION_ID";
                res.candidateNames.add(tc.getName());
            }
        }
        if (res.matchCount == 1) return res;
        if (res.matchCount > 1) return res;

        // 3. Match by METHOD /path-template (e.g. "POST /customers" or "GET /customers/{id}")
        int spaceIdx = cleanTarget.indexOf(' ');
        if (spaceIdx != -1) {
            String method = cleanTarget.substring(0, spaceIdx).trim().toUpperCase();
            String pathTpl = cleanTarget.substring(spaceIdx + 1).trim();

            for (TestCase tc : suite.getTestCases()) {
                if (method.equalsIgnoreCase(tc.getMethod())) {
                    if (tc.getUrl() != null && tc.getUrl().contains(pathTpl)) {
                        res.matchCount++;
                        res.testCase = tc;
                        res.matchedBy = "METHOD_PATH";
                        res.candidateNames.add(tc.getName());
                    }
                }
            }
        }

        return res;
    }
}
