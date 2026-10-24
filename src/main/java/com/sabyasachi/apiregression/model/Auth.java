package com.sabyasachi.apiregression.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.Objects;

@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class Auth {

    public enum AuthType {
        NONE,
        BEARER,
        BASIC,
        API_KEY
    }

    private AuthType type = AuthType.NONE;
    private String bearerToken;
    private String username;
    private String password;
    private String apiKeyKey;
    private String apiKeyValue;
    private String apiKeyAddTo; // "HEADER" or "QUERY"

    public Auth() {
    }

    public static Auth none() {
        return new Auth();
    }

    public static Auth bearer(String token) {
        Auth auth = new Auth();
        auth.setType(AuthType.BEARER);
        auth.setBearerToken(token);
        return auth;
    }

    public static Auth basic(String username, String password) {
        Auth auth = new Auth();
        auth.setType(AuthType.BASIC);
        auth.setUsername(username);
        auth.setPassword(password);
        return auth;
    }

    public static Auth apiKey(String key, String value, String addTo) {
        Auth auth = new Auth();
        auth.setType(AuthType.API_KEY);
        auth.setApiKeyKey(key);
        auth.setApiKeyValue(value);
        auth.setApiKeyAddTo(addTo != null ? addTo : "HEADER");
        return auth;
    }

    public AuthType getType() {
        return type;
    }

    public void setType(AuthType type) {
        this.type = type != null ? type : AuthType.NONE;
    }

    public String getBearerToken() {
        return bearerToken;
    }

    public void setBearerToken(String bearerToken) {
        this.bearerToken = bearerToken;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public String getApiKeyKey() {
        return apiKeyKey;
    }

    public void setApiKeyKey(String apiKeyKey) {
        this.apiKeyKey = apiKeyKey;
    }

    public String getApiKeyValue() {
        return apiKeyValue;
    }

    public void setApiKeyValue(String apiKeyValue) {
        this.apiKeyValue = apiKeyValue;
    }

    public String getApiKeyAddTo() {
        return apiKeyAddTo;
    }

    public void setApiKeyAddTo(String apiKeyAddTo) {
        this.apiKeyAddTo = apiKeyAddTo;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Auth auth)) return false;
        return type == auth.type &&
                Objects.equals(bearerToken, auth.bearerToken) &&
                Objects.equals(username, auth.username) &&
                Objects.equals(password, auth.password) &&
                Objects.equals(apiKeyKey, auth.apiKeyKey) &&
                Objects.equals(apiKeyValue, auth.apiKeyValue) &&
                Objects.equals(apiKeyAddTo, auth.apiKeyAddTo);
    }

    @Override
    public int hashCode() {
        return Objects.hash(type, bearerToken, username, password, apiKeyKey, apiKeyValue, apiKeyAddTo);
    }
}
