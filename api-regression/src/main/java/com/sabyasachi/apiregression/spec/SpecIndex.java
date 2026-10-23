package com.sabyasachi.apiregression.spec;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.servers.Server;

import java.util.*;

public class SpecIndex {

    public static class OperationMatch {
        private final String pathTemplate;
        private final String method;
        private final Operation operation;
        private final List<Parameter> mergedParameters;

        public OperationMatch(String pathTemplate, String method, Operation operation, List<Parameter> mergedParameters) {
            this.pathTemplate = pathTemplate;
            this.method = method;
            this.operation = operation;
            this.mergedParameters = mergedParameters != null ? mergedParameters : Collections.emptyList();
        }

        public String getPathTemplate() {
            return pathTemplate;
        }

        public String getMethod() {
            return method;
        }

        public Operation getOperation() {
            return operation;
        }

        public List<Parameter> getMergedParameters() {
            return mergedParameters;
        }

        public Parameter findParameter(String name, String in) {
            if (name == null) return null;
            for (Parameter p : mergedParameters) {
                if (name.equalsIgnoreCase(p.getName())) {
                    if (in == null || in.equalsIgnoreCase(p.getIn())) {
                        return p;
                    }
                }
            }
            return null;
        }

        public Parameter findParameter(String name) {
            return findParameter(name, null);
        }

        public String getPathPattern() {
            return pathTemplate;
        }
    }

    private final OpenAPI openAPI;
    private final Map<String, OperationMatch> operationsById = new LinkedHashMap<>();
    private final List<OperationMatch> allOperations = new ArrayList<>();
    private final List<String> serverBasePaths = new ArrayList<>();

    public SpecIndex(OpenAPI openAPI) {
        this.openAPI = openAPI;
        indexSpec();
    }

    private void indexSpec() {
        if (openAPI == null) return;

        // Index server base paths
        if (openAPI.getServers() != null) {
            for (Server s : openAPI.getServers()) {
                if (s.getUrl() != null && !s.getUrl().isBlank()) {
                    String u = s.getUrl().trim();
                    // Extract path part if full URL
                    int schemeIdx = u.indexOf("://");
                    if (schemeIdx != -1) {
                        int pathStart = u.indexOf('/', schemeIdx + 3);
                        if (pathStart != -1) {
                            u = u.substring(pathStart);
                        } else {
                            u = "";
                        }
                    }
                    u = u.replaceAll("/+$", "");
                    if (!u.isEmpty() && !serverBasePaths.contains(u)) {
                        serverBasePaths.add(u);
                    }
                }
            }
        }

        if (openAPI.getPaths() == null) return;

        for (Map.Entry<String, PathItem> entry : openAPI.getPaths().entrySet()) {
            String pathTemplate = entry.getKey();
            PathItem item = entry.getValue();
            if (item == null) continue;

            List<Parameter> pathParams = item.getParameters() != null ? item.getParameters() : Collections.emptyList();

            indexOp(pathTemplate, "GET", item.getGet(), pathParams);
            indexOp(pathTemplate, "POST", item.getPost(), pathParams);
            indexOp(pathTemplate, "PUT", item.getPut(), pathParams);
            indexOp(pathTemplate, "DELETE", item.getDelete(), pathParams);
            indexOp(pathTemplate, "PATCH", item.getPatch(), pathParams);
            indexOp(pathTemplate, "HEAD", item.getHead(), pathParams);
            indexOp(pathTemplate, "OPTIONS", item.getOptions(), pathParams);
        }
    }

    private void indexOp(String pathTemplate, String method, Operation op, List<Parameter> pathParams) {
        if (op == null) return;

        // Merge parameters: op-level parameters override path-level parameters with same (name, in)
        Map<String, Parameter> merged = new LinkedHashMap<>();
        for (Parameter p : pathParams) {
            if (p != null && p.getName() != null) {
                merged.put(paramKey(p), p);
            }
        }
        if (op.getParameters() != null) {
            for (Parameter p : op.getParameters()) {
                if (p != null && p.getName() != null) {
                    merged.put(paramKey(p), p);
                }
            }
        }

        OperationMatch match = new OperationMatch(pathTemplate, method, op, new ArrayList<>(merged.values()));
        allOperations.add(match);

        if (op.getOperationId() != null && !op.getOperationId().isBlank()) {
            operationsById.put(op.getOperationId().trim(), match);
        }
    }

    private String paramKey(Parameter p) {
        return (p.getName() != null ? p.getName().toLowerCase() : "") + ":" + (p.getIn() != null ? p.getIn().toLowerCase() : "");
    }

    public OperationMatch matchOperation(String operationId, String method, String rawUrl) {
        // 1. Match by operationId
        if (operationId != null && !operationId.isBlank()) {
            OperationMatch match = operationsById.get(operationId.trim());
            if (match != null) return match;
        }

        // 2. Match by method and URL path
        if (method == null || rawUrl == null || rawUrl.isBlank()) {
            return null;
        }

        String targetMethod = method.toUpperCase();
        String cleanPath = extractCleanPath(rawUrl);

        List<OperationMatch> methodCandidates = new ArrayList<>();
        for (OperationMatch om : allOperations) {
            if (om.getMethod().equalsIgnoreCase(targetMethod)) {
                methodCandidates.add(om);
            }
        }

        OperationMatch bestMatch = null;
        int maxLiteralSegments = -1;
        int tieCount = 0;

        for (OperationMatch om : methodCandidates) {
            int score = scorePathMatch(cleanPath, om.getPathTemplate());
            if (score >= 0) {
                if (score > maxLiteralSegments) {
                    maxLiteralSegments = score;
                    bestMatch = om;
                    tieCount = 1;
                } else if (score == maxLiteralSegments) {
                    tieCount++;
                }
            }
        }

        if (tieCount > 1) {
            throw new IllegalArgumentException("AMBIGUOUS_OPERATION: multiple path templates match URL '" + rawUrl + "' with identical specificity.");
        }

        return bestMatch;
    }

    public Optional<OperationMatch> findByOperationId(String operationId) {
        if (operationId == null) return Optional.empty();
        return Optional.ofNullable(operationsById.get(operationId.trim()));
    }

    public Optional<OperationMatch> findByMethodAndPath(String method, String url) {
        return Optional.ofNullable(matchOperation(null, method, url));
    }

    private String extractCleanPath(String url) {
        String u = url.trim();
        // Strip query string
        int qIdx = u.indexOf('?');
        if (qIdx != -1) {
            u = u.substring(0, qIdx);
        }

        // Strip {{baseUrl}} if present
        if (u.startsWith("{{baseUrl}}")) {
            u = u.substring("{{baseUrl}}".length());
        }

        // Strip scheme and host if present
        int scheme = u.indexOf("://");
        if (scheme != -1) {
            int pathStart = u.indexOf('/', scheme + 3);
            u = (pathStart != -1) ? u.substring(pathStart) : "";
        }

        // Strip server basePaths if matched at start
        for (String base : serverBasePaths) {
            if (!base.isEmpty() && u.startsWith(base)) {
                u = u.substring(base.length());
                break;
            }
        }

        if (!u.startsWith("/")) {
            u = "/" + u;
        }
        return u.replaceAll("/+$", "");
    }

    private int scorePathMatch(String requestPath, String template) {
        String[] reqSegs = requestPath.split("/");
        String[] tplSegs = template.split("/");

        if (reqSegs.length != tplSegs.length) {
            return -1;
        }

        int literalCount = 0;
        for (int i = 0; i < reqSegs.length; i++) {
            String r = reqSegs[i];
            String t = tplSegs[i];

            if (t.startsWith("{") && t.endsWith("}")) {
                // Wildcard path parameter segment
                continue;
            } else if (t.startsWith(":") && t.length() > 1) {
                continue;
            } else if (r.equalsIgnoreCase(t)) {
                literalCount++;
            } else {
                return -1; // Segment mismatch
            }
        }

        return literalCount;
    }

    public List<OperationMatch> getAllOperations() {
        return allOperations;
    }
}
