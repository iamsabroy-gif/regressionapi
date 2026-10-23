package com.sabyasachi.apiregression.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class RequestSnapshot {

    private String url;
    private Map<String, String> headers = new LinkedHashMap<>();
    private String bodyTemplate;
    private String capturedAt = java.time.Instant.now().toString();

    public RequestSnapshot() {
    }

    public RequestSnapshot(String url, Map<String, String> headers, String bodyTemplate) {
        this.url = url;
        this.headers = headers != null ? new LinkedHashMap<>(headers) : new LinkedHashMap<>();
        this.bodyTemplate = bodyTemplate;
        this.capturedAt = java.time.Instant.now().toString();
    }

    public static RequestSnapshot from(TestCase tc) {
        if (tc == null) return null;
        return new RequestSnapshot(tc.getUrl(), tc.getHeaders(), tc.getBodyTemplate());
    }

    public String getCapturedAt() {
        return capturedAt;
    }

    public void setCapturedAt(String capturedAt) {
        this.capturedAt = capturedAt;
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public Map<String, String> getHeaders() {
        return headers;
    }

    public void setHeaders(Map<String, String> headers) {
        this.headers = headers != null ? headers : new LinkedHashMap<>();
    }

    public String getBodyTemplate() {
        return bodyTemplate;
    }

    public void setBodyTemplate(String bodyTemplate) {
        this.bodyTemplate = bodyTemplate;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof RequestSnapshot that)) return false;
        return Objects.equals(url, that.url) &&
                Objects.equals(headers, that.headers) &&
                Objects.equals(bodyTemplate, that.bodyTemplate);
    }

    @Override
    public int hashCode() {
        return Objects.hash(url, headers, bodyTemplate);
    }
}
