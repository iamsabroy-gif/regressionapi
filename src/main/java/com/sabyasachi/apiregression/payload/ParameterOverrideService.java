package com.sabyasachi.apiregression.payload;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
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
import io.swagger.v3.oas.models.media.ArraySchema;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.parameters.Parameter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;

@SuppressWarnings("rawtypes")
public class ParameterOverrideService {

    private static final Logger log = LoggerFactory.getLogger(ParameterOverrideService.class);

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
        return apply(suite, xlsxPath, specPath, options, null);
    }

    public OverrideReport apply(TestSuite suite, Path xlsxPath, Path specPath, OverrideOptions options, OverrideReport dryRunReport) throws IOException {
        if (dryRunReport != null) {
            if (xlsxPath != null && Files.exists(xlsxPath)) {
                String currentWbSha = ProviderSpecLoader.computeSha256(Files.readAllBytes(xlsxPath));
                if (dryRunReport.getWorkbookSha256() != null && !dryRunReport.getWorkbookSha256().equals(currentWbSha)) {
                    throw new OverrideException(ReasonCode.WORKBOOK_UNSUPPORTED,
                            "Workbook changed since preview. Please re-run dry run before applying.");
                }
            }
            if (specPath != null && Files.exists(specPath)) {
                String currentSpecSha = ProviderSpecLoader.computeSha256(Files.readAllBytes(specPath));
                if (dryRunReport.getSpecSha256() != null && !dryRunReport.getSpecSha256().equals(currentSpecSha)) {
                    throw new OverrideException(ReasonCode.SPEC_UNAVAILABLE,
                            "Spec changed since preview. Please re-run dry run before applying.");
                }
            }
        }
        return process(suite, xlsxPath, specPath, options != null ? options : new OverrideOptions(), false);
    }

    public static String computeRequestFingerprint(String url, Map<String, String> headers, String bodyTemplate) {
        StringBuilder sb = new StringBuilder();
        sb.append(url != null ? url : "").append("\n");
        if (headers != null) {
            List<String> sortedKeys = new ArrayList<>(headers.keySet());
            Collections.sort(sortedKeys, String.CASE_INSENSITIVE_ORDER);
            for (String k : sortedKeys) {
                sb.append(k.toLowerCase(Locale.ROOT)).append("=").append(headers.get(k)).append("\n");
            }
        }
        sb.append("\n");
        sb.append(bodyTemplate != null ? bodyTemplate : "");
        return ProviderSpecLoader.computeSha256(sb.toString().getBytes(StandardCharsets.UTF_8));
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

        // 1. Resolve Spec path relative to suite file if needed
        Path effectiveSpecPath = specPath;
        if (effectiveSpecPath == null && suite.getSpecPath() != null && !suite.getSpecPath().isBlank()) {
            effectiveSpecPath = Path.of(suite.getSpecPath());
        }
        if (effectiveSpecPath != null && !effectiveSpecPath.isAbsolute() && suite.getFilePath() != null && !suite.getFilePath().isBlank()) {
            Path suiteParent = Path.of(suite.getFilePath()).toAbsolutePath().getParent();
            if (suiteParent != null) {
                effectiveSpecPath = suiteParent.resolve(effectiveSpecPath);
            }
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
        } else if (effectiveSpecPath != null) {
            report.getSpecMessages().add("Spec file not found: " + effectiveSpecPath);
        }

        // 2. Parse Excel Workbook
        List<SheetParams> parsedSheets = excelImporter.parse(xlsxPath, options.isSkipHiddenSheets(), options.getMaxRowsPerSheet());
        // Identity-based conflict set (D2)
        Set<TestCase> targetedInThisWorkbook = Collections.newSetFromMap(new IdentityHashMap<>());
        TypeCoercer coercer = new TypeCoercer(templateCodec.getMapper());

        for (SheetParams sheet : parsedSheets) {
            SheetSummary summary = new SheetSummary(sheet.getSheetName(), sheet.getTarget());
            report.getSheets().add(summary);

            // Handle sheet-level skipped (e.g. HIDDEN_SHEET)
            if ("SKIPPED".equalsIgnoreCase(sheet.getStatus())) {
                summary.setStatus("SKIPPED");
                OverrideEntry entry = new OverrideEntry();
                entry.setSheet(sheet.getSheetName());
                entry.setRow(1);
                entry.setParameter("sheet");
                entry.setAction(OverrideAction.SKIPPED);
                ReasonCode rc = ReasonCode.HIDDEN_SHEET;
                if (sheet.getSkipReason() != null) {
                    try { rc = ReasonCode.valueOf(sheet.getSkipReason()); } catch (Exception ignored) {}
                }
                entry.setReasonCode(rc);
                entry.setReason("Sheet is skipped: " + rc);
                report.getEntries().add(entry);
                continue;
            }

            // Handle sheet-level not applied (e.g. ROW_CAP_EXCEEDED)
            if ("NOT_APPLIED".equalsIgnoreCase(sheet.getStatus())) {
                summary.setStatus("NOT_APPLIED");
                summary.setErrors(1);
                OverrideEntry entry = new OverrideEntry();
                entry.setSheet(sheet.getSheetName());
                entry.setRow(1);
                entry.setParameter("sheet");
                entry.setAction(OverrideAction.ERROR);
                ReasonCode rc = ReasonCode.ROW_CAP_EXCEEDED;
                if (sheet.getSkipReason() != null) {
                    try { rc = ReasonCode.valueOf(sheet.getSkipReason()); } catch (Exception ignored) {}
                }
                entry.setReasonCode(rc);
                entry.setReason(sheet.getSheetWarnings().isEmpty() ? "Sheet row cap exceeded" : sheet.getSheetWarnings().get(0));
                report.getEntries().add(entry);
                continue;
            }

            // Sheet-level warnings (e.g. ROW_CAP_EXCEEDED)
            boolean rowCapExceeded = false;
            for (String sw : sheet.getSheetWarnings()) {
                if (sw.startsWith("ROW_CAP_EXCEEDED")) {
                    rowCapExceeded = true;
                    summary.setStatus("NOT_APPLIED");
                    summary.setErrors(1);
                    OverrideEntry entry = new OverrideEntry();
                    entry.setSheet(sheet.getSheetName());
                    entry.setRow(1);
                    entry.setParameter("sheet");
                    entry.setAction(OverrideAction.ERROR);
                    entry.setReasonCode(ReasonCode.ROW_CAP_EXCEEDED);
                    entry.setReason(sw);
                    report.getEntries().add(entry);
                }
            }
            if (rowCapExceeded) {
                continue;
            }

            // Match TestCase using TargetMatcher (D7, M1)
            TargetMatcher.MatchResult matchResult = TargetMatcher.match(suite, sheet.getTarget(), specIndex);
            if (matchResult.getMatchCount() == 0) {
                summary.setStatus("UNMATCHED");
                summary.setErrors(1);
                OverrideEntry entry = new OverrideEntry();
                entry.setSheet(sheet.getSheetName());
                entry.setRow(1);
                entry.setParameter("target");
                entry.setAction(OverrideAction.ERROR);
                entry.setReasonCode(ReasonCode.UNMATCHED);
                entry.setReason("No TestCase in the suite matches target: '" + sheet.getTarget() + "'");
                report.getEntries().add(entry);
                continue;
            } else if (matchResult.getMatchCount() > 1) {
                summary.setStatus("AMBIGUOUS_TARGET");
                summary.setErrors(1);
                OverrideEntry entry = new OverrideEntry();
                entry.setSheet(sheet.getSheetName());
                entry.setRow(1);
                entry.setParameter("target");
                entry.setAction(OverrideAction.ERROR);
                entry.setReasonCode(ReasonCode.AMBIGUOUS_TARGET);
                entry.setReason("Multiple TestCases match target: '" + sheet.getTarget() + "'");
                entry.setCandidates(matchResult.getCandidateNames());
                report.getEntries().add(entry);
                continue;
            }

            TestCase targetTc = matchResult.getTestCase();
            summary.setTargetTestCase(targetTc.getName());
            summary.setMatchedBy(matchResult.getMatchedBy());

            // Check if manual edits occurred since last import
            if (targetTc.getLastOverride() != null && targetTc.getLastOverride().getProducedFingerprint() != null) {
                String currentFp = computeRequestFingerprint(targetTc.getUrl(), targetTc.getHeaders(), targetTc.getBodyTemplate());
                if (!currentFp.equals(targetTc.getLastOverride().getProducedFingerprint())) {
                    summary.addSheetWarning("MANUAL_EDITS_DISCARDED: manual edits since last import will be discarded.");
                }
            }

            // Check duplicate sheet targeting same TestCase (D2)
            if (targetedInThisWorkbook.contains(targetTc)) {
                summary.setStatus("CONFLICT");
                summary.setErrors(1);
                OverrideEntry entry = new OverrideEntry();
                entry.setSheet(sheet.getSheetName());
                entry.setRow(1);
                entry.setParameter("target");
                entry.setAction(OverrideAction.ERROR);
                entry.setReasonCode(ReasonCode.CONFLICT);
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

            // D1 WorkingRequest
            String workingUrl = baseline.getUrl();
            Map<String, String> workingHeaders = new LinkedHashMap<>(baseline.getHeaders());
            String workingBody = baseline.getBodyTemplate();

            // Lazy body parsing (H5)
            JsonTemplateCodec.ParsedBody parsedBody = templateCodec.parse(workingBody);
            JsonNode workingBodyNode = parsedBody.isJson() ? parsedBody.getRootNode() : null;
            boolean bodyModifiedOrAdded = false;

            // Find spec operation
            SpecIndex.OperationMatch opMatch = null;
            Schema requestBodySchema = null;
            if (specIndex != null) {
                try {
                    opMatch = specIndex.matchOperation(targetTc.getOperationId(), targetTc.getMethod(), workingUrl);
                    if (opMatch != null && opMatch.getOperation() != null && schemaNavigator != null) {
                        requestBodySchema = schemaNavigator.extractJsonRequestBodySchema(opMatch.getOperation().getRequestBody());
                    }
                } catch (OverrideException oe) {
                    if (oe.getReasonCode() == ReasonCode.AMBIGUOUS_OPERATION) {
                        summary.addSheetWarning("Ambiguous spec operation matching: " + oe.getMessage());
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

                if (row.isDuplicate()) {
                    entry.setAction(OverrideAction.SKIPPED);
                    entry.setReasonCode(ReasonCode.DUPLICATE);
                    entry.setReason(row.getSupersededByRow() != null ? "Superseded by row " + row.getSupersededByRow() : "Duplicate parameter superseded");
                    summary.setSkipped(summary.getSkipped() + 1);
                    report.getEntries().add(entry);
                    continue;
                }

                if (!row.isEnabled()) {
                    entry.setAction(OverrideAction.SKIPPED);
                    entry.setReasonCode(ReasonCode.DISABLED);
                    entry.setReason("Row is disabled in sheet.");
                    summary.setSkipped(summary.getSkipped() + 1);
                    report.getEntries().add(entry);
                    continue;
                }

                if (row.getRawValue() == null && "BLANK".equalsIgnoreCase(row.getCellKind())) {
                    entry.setAction(OverrideAction.SKIPPED);
                    entry.setReasonCode(ReasonCode.BLANK);
                    entry.setReason("Cell is blank; field left as imported.");
                    summary.setSkipped(summary.getSkipped() + 1);
                    report.getEntries().add(entry);
                    continue;
                }

                // H3: Refuse to coerce any row whose cellKind is an error kind!
                if (row.getCellKindEnum() != null && row.getCellKindEnum().isErrorKind()) {
                    entry.setAction(OverrideAction.ERROR);
                    if (row.getCellKindEnum() == ParamRow.CellKind.ERROR) {
                        entry.setReasonCode(ReasonCode.CELL_ERROR);
                        entry.setReason("Cell formula or value contains an unresolvable error.");
                    } else if (row.getCellKindEnum() == ParamRow.CellKind.NO_CACHE) {
                        entry.setReasonCode(ReasonCode.FORMULA_NO_CACHE);
                        entry.setReason("Formula cell has no cached result in workbook.");
                    } else if (row.getCellKindEnum() == ParamRow.CellKind.TOO_PRECISE) {
                        entry.setReasonCode(ReasonCode.NUMBER_PRECISION_LOSS);
                        entry.setReason("Numeric value has 15+ digits and loses precision. Format cell as Text in Excel.");
                    }
                    summary.setErrors(summary.getErrors() + 1);
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
                    ParamLocation targetLocation = determineLocation(explicitLoc, paramPath, opMatch, requestBodySchema, schemaNavigator, workingBodyNode, workingUrl, workingHeaders, specIndex != null);
                    entry.setLocation(targetLocation);

                    if (targetLocation == ParamLocation.BODY) {
                        // Check body state per H5
                        if (parsedBody.isNotJson()) {
                            throw new OverrideException(ReasonCode.BODY_NOT_JSON,
                                    "JSON body template is invalid: " + parsedBody.getFailureReason());
                        }

                        if (parsedBody.isAbsent()) {
                            if (requestBodySchema != null) {
                                // Start from empty object or array
                                workingBodyNode = (requestBodySchema instanceof ArraySchema) ?
                                        templateCodec.getMapper().createArrayNode() : templateCodec.getMapper().createObjectNode();
                                parsedBody = JsonTemplateCodec.ParsedBody.json(workingBodyNode);
                            } else {
                                String method = targetTc.getMethod() != null ? targetTc.getMethod().toUpperCase() : "GET";
                                if ("GET".equals(method) || "HEAD".equals(method) || "DELETE".equals(method)) {
                                    throw new OverrideException(ReasonCode.BODY_NOT_APPLICABLE,
                                            "Body overrides are not applicable to " + method + " request without requestBody in spec.");
                                } else {
                                    throw new OverrideException(ReasonCode.SPEC_UNAVAILABLE,
                                            "Body template is absent and spec was unavailable for requestBody gap-fill.");
                                }
                            }
                        }

                        // Apply on row-scoped deep copy (D1 / H6)
                        JsonNode rowWorkingCopy = workingBodyNode.deepCopy();
                        processBodyOverride(row, paramPath, rowWorkingCopy, workingBodyNode, requestBodySchema, schemaNavigator, coercer, options, entry, summary, specIndex != null, opMatch != null, sheet.getRows());

                        // If successful, promote row working copy
                        workingBodyNode = rowWorkingCopy;
                        bodyModifiedOrAdded = true;

                    } else if (targetLocation == ParamLocation.QUERY) {
                        processQueryOverride(row, paramPath, workingUrl, opMatch, coercer, options, entry, summary);
                        RequestPartOverrider.OverridePartResult partRes = RequestPartOverrider.overrideQueryParam(workingUrl, paramPath.getLeafName(), row.getRawValue());
                        workingUrl = partRes.getUpdatedUrl();
                        entry.setAction(partRes.isAdded() ? OverrideAction.ADDED : OverrideAction.MODIFIED);
                        entry.setResolvedPath("query." + paramPath.getLeafName());
                        if (partRes.isAdded()) summary.setAdded(summary.getAdded() + 1);
                        else summary.setModified(summary.getModified() + 1);

                    } else if (targetLocation == ParamLocation.HEADER) {
                        processHeaderOverride(row, paramPath, workingHeaders, opMatch, coercer, options, entry, summary);
                        RequestPartOverrider.OverridePartResult partRes = RequestPartOverrider.overrideHeader(workingHeaders, paramPath.getLeafName(), row.getRawValue());
                        workingHeaders = partRes.getUpdatedHeaders();
                        entry.setAction(partRes.isAdded() ? OverrideAction.ADDED : OverrideAction.MODIFIED);
                        entry.setResolvedPath("header." + paramPath.getLeafName());
                        if (partRes.isAdded()) summary.setAdded(summary.getAdded() + 1);
                        else summary.setModified(summary.getModified() + 1);

                    } else if (targetLocation == ParamLocation.PATH) {
                        if (opMatch == null) {
                            throw new OverrideException(ReasonCode.SPEC_UNAVAILABLE,
                                    "Path parameter override requires spec path template, but spec was unavailable or operation not found.");
                        }
                        processPathOverride(row, paramPath, workingUrl, opMatch, coercer, options, entry, summary);
                        RequestPartOverrider.OverridePartResult partRes = RequestPartOverrider.overridePathParam(workingUrl, opMatch.getPathTemplate(), paramPath.getLeafName(), row.getRawValue());
                        workingUrl = partRes.getUpdatedUrl();
                        entry.setAction(OverrideAction.MODIFIED);
                        entry.setResolvedPath("path." + paramPath.getLeafName());
                        summary.setModified(summary.getModified() + 1);
                    }

                } catch (OverrideException ex) {
                    entry.setAction(OverrideAction.ERROR);
                    entry.setReasonCode(ex.getReasonCode());
                    entry.setReason(ex.getMessage());
                    if (ex.getCandidates() != null && !ex.getCandidates().isEmpty()) {
                        entry.setCandidates(ex.getCandidates());
                    }
                    summary.setErrors(summary.getErrors() + 1);
                } catch (Exception ex) {
                    log.error("Internal error processing row " + row.getRow() + " in sheet '" + sheet.getSheetName() + "'", ex);
                    entry.setAction(OverrideAction.ERROR);
                    entry.setReasonCode(ReasonCode.INTERNAL_ERROR);
                    entry.setReason(ex.getClass().getSimpleName() + ": " + ex.getMessage());
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

            // D1 Commit Step: apply to target TestCase
            if (!isDryRun && (summary.getErrors() == 0 || options.isApplyWithErrors())) {
                try {
                    targetTc.setUrl(workingUrl);
                    targetTc.setHeaders(workingHeaders);
                    if (bodyModifiedOrAdded && workingBodyNode != null) {
                        String updatedBody = templateCodec.encode(workingBodyNode, parsedBody.getUnquotedPlaceholders());
                        targetTc.setBodyTemplate(updatedBody);
                    }

                    if ("OPERATION_ID".equals(matchResult.getMatchedBy()) && targetTc.getOperationId() == null) {
                        targetTc.setOperationId(sheet.getTarget().trim());
                    }

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
                    String finalBody = (bodyModifiedOrAdded && workingBodyNode != null) ?
                            templateCodec.encode(workingBodyNode, parsedBody.getUnquotedPlaceholders()) : targetTc.getBodyTemplate();
                    String producedFp = computeRequestFingerprint(workingUrl, workingHeaders, finalBody);
                    prov.setProducedFingerprint(producedFp);
                    targetTc.setLastOverride(prov);
                } catch (Exception ex) {
                    log.error("Failed to commit WorkingRequest to TestCase", ex);
                    summary.setStatus("ERROR");
                    summary.setErrors(summary.getErrors() + 1);
                }
            }
        }

        return report;
    }

    private void processQueryOverride(ParamRow row, ParamPath paramPath, String currentUrl,
                                      SpecIndex.OperationMatch opMatch, TypeCoercer coercer,
                                      OverrideOptions options, OverrideEntry entry, SheetSummary summary) {
        String val = row.getRawValue();
        if ("<null>".equalsIgnoreCase(val != null ? val.trim() : "")) {
            throw new OverrideException(ReasonCode.NULL_NOT_ALLOWED_HERE,
                    "Null value is not allowed for query parameter '" + paramPath.getLeafName() + "'.");
        }

        Parameter specParam = (opMatch != null) ? opMatch.findParameter(paramPath.getLeafName(), "query") : null;
        if (opMatch != null && specParam == null) {
            // Check if present in current URL
            boolean presentInUrl = currentUrl != null && currentUrl.contains(paramPath.getLeafName() + "=");
            if (!presentInUrl) {
                throw new OverrideException(ReasonCode.NOT_IN_SPEC,
                        "Query parameter '" + paramPath.getLeafName() + "' is not declared in spec for operation.");
            }
        }

        if (specParam != null && specParam.getSchema() != null) {
            validateParamSchema(specParam.getSchema(), val, coercer, options, entry, summary);
        }
    }

    private void processHeaderOverride(ParamRow row, ParamPath paramPath, Map<String, String> headers,
                                       SpecIndex.OperationMatch opMatch, TypeCoercer coercer,
                                       OverrideOptions options, OverrideEntry entry, SheetSummary summary) {
        String val = row.getRawValue();
        if ("<null>".equalsIgnoreCase(val != null ? val.trim() : "")) {
            throw new OverrideException(ReasonCode.NULL_NOT_ALLOWED_HERE,
                    "Null value is not allowed for header '" + paramPath.getLeafName() + "'.");
        }

        Parameter specParam = (opMatch != null) ? opMatch.findParameter(paramPath.getLeafName(), "header") : null;
        if (opMatch != null && specParam == null) {
            boolean present = false;
            if (headers != null) {
                for (String k : headers.keySet()) {
                    if (k.equalsIgnoreCase(paramPath.getLeafName())) {
                        present = true;
                        break;
                    }
                }
            }
            if (!present) {
                throw new OverrideException(ReasonCode.NOT_IN_SPEC,
                        "Header '" + paramPath.getLeafName() + "' is not declared in spec for operation.");
            }
        }

        if (specParam != null && specParam.getSchema() != null) {
            validateParamSchema(specParam.getSchema(), val, coercer, options, entry, summary);
        }
    }

    private void processPathOverride(ParamRow row, ParamPath paramPath, String currentUrl,
                                     SpecIndex.OperationMatch opMatch, TypeCoercer coercer,
                                     OverrideOptions options, OverrideEntry entry, SheetSummary summary) {
        String val = row.getRawValue();
        if ("<null>".equalsIgnoreCase(val != null ? val.trim() : "")) {
            throw new OverrideException(ReasonCode.NULL_NOT_ALLOWED_HERE,
                    "Null value is not allowed for path parameter '" + paramPath.getLeafName() + "'.");
        }

        Parameter specParam = (opMatch != null) ? opMatch.findParameter(paramPath.getLeafName(), "path") : null;
        if (specParam != null && specParam.getSchema() != null) {
            validateParamSchema(specParam.getSchema(), val, coercer, options, entry, summary);
        }
    }

    private void validateParamSchema(Schema paramSchema, String rawValue, TypeCoercer coercer,
                                     OverrideOptions options, OverrideEntry entry, SheetSummary summary) {
        if (rawValue == null || rawValue.contains("{{")) return;
        String val = "<empty>".equalsIgnoreCase(rawValue.trim()) ? "" : rawValue;
        List<String> warnings = new ArrayList<>();
        TypeCoercer.CoercionResult cres = coercer.coerce(val, null, paramSchema.getType(), paramSchema.getFormat(), null, null, warnings);
        entry.getWarnings().addAll(cres.getWarnings());

        ConstraintValidator.ValidationResult vres = ConstraintValidator.validate(paramSchema, cres.getNode(), val, options.getConstraintPolicy(), false);
        if (!vres.isValid()) {
            throw new OverrideException(ReasonCode.CONSTRAINT_FORMAT, vres.getErrorMessage());
        }
        if (vres.getWarningMessage() != null) {
            entry.addWarning(vres.getWarningMessage());
            summary.setWarnings(summary.getWarnings() + 1);
        }
    }

    private void processBodyOverride(ParamRow row, ParamPath paramPath, JsonNode rootNode, JsonNode initialBodyNode,
                                     Schema requestBodySchema, SchemaNavigator schemaNavigator,
                                     TypeCoercer coercer, OverrideOptions options,
                                     OverrideEntry entry, SheetSummary summary,
                                     boolean specAttached, boolean opMatched, List<ParamRow> allSheetRows) {
        ParamPath effectivePath = paramPath;
        Schema targetPropSchema = null;
        String specPointer = null;

        // 1. Resolve bare name if needed
        if (paramPath.isBareName()) {
            List<String> bodyCandidates = PayloadOverrider.findCandidatePathsForLeaf(rootNode, paramPath.getLeafName());
            if (bodyCandidates.size() == 1) {
                effectivePath = ParamPath.parse(bodyCandidates.get(0));
            } else if (bodyCandidates.size() > 1) {
                throw new OverrideException(ReasonCode.AMBIGUOUS,
                        "Multiple fields in JSON payload match bare name '" + paramPath.getLeafName() + "'", bodyCandidates);
            } else {
                // Not in payload -> look up in spec!
                if (schemaNavigator == null || requestBodySchema == null) {
                    if (specAttached && !opMatched) {
                        throw new OverrideException(ReasonCode.OPERATION_NOT_FOUND,
                                "No matching operation found in spec for gap-fill of bare parameter '" + paramPath.getLeafName() + "'.");
                    }
                    throw new OverrideException(ReasonCode.SPEC_UNAVAILABLE,
                            "Field '" + paramPath.getLeafName() + "' is not present in payload and provider spec was not available for gap-fill.");
                }

                List<SchemaNavigator.ResolvedField> specCandidates = schemaNavigator.findSchemaPathsForLeaf(requestBodySchema, paramPath.getLeafName());
                if (specCandidates.isEmpty()) {
                    throw new OverrideException(ReasonCode.NOT_IN_SPEC,
                            "Field '" + paramPath.getLeafName() + "' is neither in the payload nor declared in the provider OpenAPI spec.");
                } else if (specCandidates.size() > 1) {
                    List<String> candList = new ArrayList<>();
                    for (var sc : specCandidates) candList.add(sc.getJsonPointer());
                    throw new OverrideException(ReasonCode.AMBIGUOUS,
                            "Field '" + paramPath.getLeafName() + "' matches multiple schema locations in provider spec.", candList);
                } else {
                    SchemaNavigator.ResolvedField rf = specCandidates.get(0);
                    if (rf.isPassesThroughArray()) {
                        String arrayPtr = rf.getJsonPointer();
                        int arrIdx = arrayPtr.indexOf("[*]");
                        String arrayPathStr = (arrIdx != -1) ? arrayPtr.substring(0, arrIdx) : arrayPtr;
                        JsonNode existingArr = PayloadOverrider.findExistingNode(rootNode, ParamPath.parse(arrayPathStr));
                        if (existingArr != null && existingArr.isArray()) {
                            String suggested = rf.getJsonPointer().replace("[*]", "[0]");
                            throw new OverrideException(ReasonCode.NEEDS_INDEX,
                                    "Field '" + paramPath.getLeafName() + "' is inside an array in spec schema and array is present in payload. Specify an array index, e.g. " + suggested,
                                    List.of(suggested));
                        } else {
                            // Array is absent -> create element [0]
                            effectivePath = ParamPath.parse(rf.getJsonPointer().replace("[*]", "[0]"));
                            targetPropSchema = rf.getSchema();
                            specPointer = rf.getSpecPointer().replace("[*]", "/0");
                        }
                    } else {
                        effectivePath = ParamPath.parse(rf.getJsonPointer());
                        targetPropSchema = rf.getSchema();
                        specPointer = rf.getSpecPointer();
                    }
                }
            }
        }

        entry.setResolvedPath(effectivePath.toJsonPointer());
        entry.setSpecPointer(specPointer);

        // 2. Find existing node in body
        JsonNode existingNode = PayloadOverrider.findExistingNode(rootNode, effectivePath);
        entry.setOldValue(existingNode);

        // 3. H1 Resolution Policy: If explicit path and not in payload
        if (existingNode == null) {
            if (schemaNavigator == null || requestBodySchema == null) {
                if (specAttached && !opMatched) {
                    throw new OverrideException(ReasonCode.OPERATION_NOT_FOUND,
                            "No matching operation found in spec for gap-fill of field '" + effectivePath + "'.");
                }
                throw new OverrideException(ReasonCode.SPEC_UNAVAILABLE,
                        "Field '" + effectivePath + "' is not in payload and provider spec is unavailable for gap-fill.");
            }

            SchemaNavigator.Resolution resolution = schemaNavigator.resolveExplicitPath(requestBodySchema, effectivePath, rootNode);
            if (resolution.getStatus() == SchemaNavigator.Resolution.Status.NO_SCHEMA) {
                if (specAttached && !opMatched) {
                    throw new OverrideException(ReasonCode.OPERATION_NOT_FOUND,
                            "Operation not found in spec for field '" + effectivePath + "'.");
                }
                throw new OverrideException(ReasonCode.SPEC_UNAVAILABLE,
                        "Field '" + effectivePath + "' is not in payload and provider spec is unavailable.");
            } else if (resolution.getStatus() == SchemaNavigator.Resolution.Status.UNDECLARED) {
                if (resolution.isAdditionalPropertiesAllowed()) {
                    entry.addWarning("Field '" + effectivePath + "' is undeclared in spec, but permitted by additionalProperties.");
                    entry.setReasonCode(ReasonCode.UNDECLARED);
                    if (resolution.getAdditionalPropertiesSchema() != null) {
                        targetPropSchema = resolution.getAdditionalPropertiesSchema();
                    }
                } else {
                    throw new OverrideException(ReasonCode.NOT_IN_SPEC,
                            "Field '" + effectivePath + "' is neither in payload nor declared in spec.", resolution.getCandidates());
                }
            } else if (resolution.getStatus() == SchemaNavigator.Resolution.Status.DECLARED_IN_SPEC) {
                targetPropSchema = resolution.getSchema();
                specPointer = resolution.getSpecPointer();
                entry.setSpecPointer(specPointer);
            }
        } else {
            if (targetPropSchema == null && schemaNavigator != null && requestBodySchema != null) {
                targetPropSchema = schemaNavigator.navigateExplicitPath(requestBodySchema, effectivePath, rootNode);
            }
        }

        // ReadOnly check
        if (targetPropSchema != null && Boolean.TRUE.equals(targetPropSchema.getReadOnly())) {
            if (existingNode == null) {
                throw new OverrideException(ReasonCode.READONLY_ADD,
                        "Field '" + effectivePath + "' is marked readOnly in spec and cannot be added.");
            } else if (options.getConstraintPolicy() == ConstraintPolicy.STRICT) {
                throw new OverrideException(ReasonCode.READONLY_MODIFY,
                        "Field '" + effectivePath + "' is marked readOnly in spec.");
            } else {
                entry.addWarning("READONLY_MODIFY: Field '" + effectivePath + "' is marked readOnly in spec.");
                summary.setWarnings(summary.getWarnings() + 1);
            }
        }

        // 4. Coerce value
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

        // 5. Constraint validation
        ConstraintValidator.ValidationResult valRes = ConstraintValidator.validate(
                targetPropSchema,
                coercionRes.getNode(),
                row.getRawValue(),
                options.getConstraintPolicy(),
                existingNode == null
        );

        if (!valRes.isValid()) {
            throw new OverrideException(ReasonCode.CONSTRAINT_RANGE, valRes.getErrorMessage());
        }
        if (valRes.getWarningMessage() != null) {
            entry.addWarning(valRes.getWarningMessage());
            summary.setWarnings(summary.getWarnings() + 1);
        }

        // 6. Apply override to body copy
        PayloadOverrider.ApplyResult applyRes = PayloadOverrider.applyOverride(rootNode, effectivePath, coercionRes.getNode());
        entry.setAction(applyRes.isAdded() ? OverrideAction.ADDED : OverrideAction.MODIFIED);

        if (applyRes.isAdded()) {
            summary.setAdded(summary.getAdded() + 1);
        } else {
            summary.setModified(summary.getModified() + 1);
        }

        // 7. Check REQUIRED_SIBLINGS_MISSING for newly created intermediate objects
        List<ParamPath.Segment> segs = effectivePath.getSegments();
        if (existingNode == null && segs.size() > 1 && schemaNavigator != null && requestBodySchema != null) {
            StringBuilder prefix = new StringBuilder();
            for (int i = 0; i < segs.size() - 1; i++) {
                ParamPath.Segment seg = segs.get(i);
                if (seg.isArray()) {
                    prefix.append("[").append(seg.getArrayIndex()).append("]");
                } else {
                    if (prefix.length() > 0) prefix.append(".");
                    prefix.append(seg.getPropertyName());
                }
                ParamPath intermediatePath = ParamPath.parse(prefix.toString());
                if (PayloadOverrider.findExistingNode(initialBodyNode, intermediatePath) == null) {
                    Schema intermediateSchema = schemaNavigator.navigateExplicitPath(requestBodySchema, intermediatePath, rootNode);
                    if (intermediateSchema != null && intermediateSchema.getRequired() != null && !intermediateSchema.getRequired().isEmpty()) {
                        List<String> requiredList = intermediateSchema.getRequired();
                        List<String> missing = new ArrayList<>();
                        for (String req : requiredList) {
                            String childPath = prefix + "." + req;
                            boolean inBody = PayloadOverrider.findExistingNode(rootNode, ParamPath.parse(childPath)) != null;
                            boolean inSheet = false;
                            if (allSheetRows != null) {
                                for (ParamRow r : allSheetRows) {
                                    if (r.getParameter() != null && r.getParameter().trim().endsWith(req)) {
                                        inSheet = true;
                                        break;
                                    }
                                }
                            }
                            if (!inBody && !inSheet) {
                                missing.add(req);
                            }
                        }
                        if (!missing.isEmpty()) {
                            entry.addWarning("REQUIRED_SIBLINGS_MISSING: Intermediate object '" + prefix + "' has required properties not set by any row on this sheet: " + missing);
                            summary.setWarnings(summary.getWarnings() + 1);
                        }
                    }
                }
            }
        }
    }

    private ParamLocation determineLocation(String explicitLocation, ParamPath paramPath,
                                            SpecIndex.OperationMatch opMatch,
                                            Schema requestBodySchema, SchemaNavigator schemaNavigator,
                                            JsonNode workingBodyNode, String workingUrl, Map<String, String> workingHeaders,
                                            boolean specAttached) {
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

        String leaf = paramPath.getLeafName();

        if (opMatch != null) {
            Parameter opParam = opMatch.findParameter(leaf, null);

            boolean inBodySchema = false;
            if (schemaNavigator != null && requestBodySchema != null) {
                List<SchemaNavigator.ResolvedField> fields = schemaNavigator.findSchemaPathsForLeaf(requestBodySchema, leaf);
                if (!fields.isEmpty()) inBodySchema = true;
            }

            if (opParam != null && inBodySchema) {
                throw new OverrideException(ReasonCode.AMBIGUOUS_LOCATION,
                        "Bare parameter '" + leaf + "' is ambiguous: matches both operation parameter (" + opParam.getIn() + ") and body schema.",
                        List.of(opParam.getIn() + "." + leaf, "body." + leaf));
            }

            if (opParam != null && opParam.getIn() != null) {
                switch (opParam.getIn().toLowerCase()) {
                    case "query": return ParamLocation.QUERY;
                    case "path": return ParamLocation.PATH;
                    case "header": return ParamLocation.HEADER;
                }
            }

            if (inBodySchema) {
                return ParamLocation.BODY;
            }
        }

        // Check if bare name matches query, header, or body in current request
        boolean inPayload = workingBodyNode != null && !PayloadOverrider.findCandidatePathsForLeaf(workingBodyNode, leaf).isEmpty();
        boolean inQuery = workingUrl != null && (workingUrl.contains("?" + leaf + "=") || workingUrl.contains("&" + leaf + "=") || workingUrl.endsWith("?" + leaf) || workingUrl.contains("&" + leaf + "&"));
        boolean inHeader = false;
        if (workingHeaders != null) {
            for (String h : workingHeaders.keySet()) {
                if (h.equalsIgnoreCase(leaf)) {
                    inHeader = true;
                    break;
                }
            }
        }

        int matches = (inPayload ? 1 : 0) + (inQuery ? 1 : 0) + (inHeader ? 1 : 0);
        if (matches > 1) {
            List<String> candidates = new ArrayList<>();
            if (inQuery) candidates.add("query." + leaf);
            if (inHeader) candidates.add("header." + leaf);
            if (inPayload) candidates.add("body." + leaf);
            throw new OverrideException(ReasonCode.AMBIGUOUS_LOCATION,
                    "Bare parameter '" + leaf + "' exists in multiple parts of the request", candidates);
        }

        if (inQuery) return ParamLocation.QUERY;
        if (inHeader) return ParamLocation.HEADER;
        if (inPayload) return ParamLocation.BODY;

        // Zero hits:
        if (opMatch != null) {
            // Spec operation matched, but param is neither in spec nor request:
            // Route to BODY so processBodyOverride emits NOT_IN_SPEC with candidates
            return ParamLocation.BODY;
        }

        if (specAttached) {
            throw new OverrideException(ReasonCode.OPERATION_NOT_FOUND,
                    "No matching operation found in spec for gap-fill of bare parameter '" + leaf + "'.");
        } else {
            throw new OverrideException(ReasonCode.SPEC_UNAVAILABLE,
                    "Bare parameter '" + leaf + "' was not found in request and no OpenAPI spec is available.");
        }
    }
}
