package com.sabyasachi.apiregression.payload;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class RequestPartOverriderTest {

    @Test
    public void testQueryParamReplacePreservesOrderAndAddsNew() {
        String url = "https://api.example.com/items?sort=asc&filter=active&page=1";
        
        // 1. Replace existing query param
        RequestPartOverrider.OverridePartResult res1 = RequestPartOverrider.overrideQueryParam(url, "filter", "pending");
        assertEquals("https://api.example.com/items?sort=asc&filter=pending&page=1", res1.getUpdatedUrl());
        assertEquals("active", res1.getOldValue());
        assertFalse(res1.isAdded());

        // 2. Add new query param
        RequestPartOverrider.OverridePartResult res2 = RequestPartOverrider.overrideQueryParam(res1.getUpdatedUrl(), "limit", "50");
        assertEquals("https://api.example.com/items?sort=asc&filter=pending&page=1&limit=50", res2.getUpdatedUrl());
        assertTrue(res2.isAdded());
        assertNull(res2.getOldValue());

        // 3. Repeated query param throws error
        String badUrl = "https://api.example.com/items?tag=a&tag=b";
        OverrideException ex = assertThrows(OverrideException.class, () -> RequestPartOverrider.overrideQueryParam(badUrl, "tag", "c"));
        assertEquals(ReasonCode.QUERY_REPEATED, ex.getReasonCode());
    }

    @Test
    public void testHeaderCaseInsensitiveMatchPreservesCasing() {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("X-Custom-Header", "original-val");
        headers.put("Content-Type", "application/json");

        // Case-insensitive match on existing header
        RequestPartOverrider.OverridePartResult res1 = RequestPartOverrider.overrideHeader(headers, "x-custom-header", "new-val");
        assertFalse(res1.isAdded());
        assertEquals("original-val", res1.getOldValue());
        assertEquals("new-val", res1.getUpdatedHeaders().get("X-Custom-Header"));
        assertTrue(res1.getUpdatedHeaders().containsKey("X-Custom-Header"));

        // Add brand new header
        RequestPartOverrider.OverridePartResult res2 = RequestPartOverrider.overrideHeader(res1.getUpdatedHeaders(), "X-New-Trace", "trace-999");
        assertTrue(res2.isAdded());
        assertEquals("trace-999", res2.getUpdatedHeaders().get("X-New-Trace"));
    }

    @Test
    public void testPathParamSubstitutionWithTemplate() {
        String url = "https://api.example.com/v1/customers/123/orders/456";
        String template = "/customers/{customerId}/orders/{orderId}";

        // Substitute customerId
        RequestPartOverrider.OverridePartResult res1 = RequestPartOverrider.overridePathParam(url, template, "customerId", "999");
        assertEquals("https://api.example.com/v1/customers/999/orders/456", res1.getUpdatedUrl());
        assertEquals("123", res1.getOldValue());

        // Substitute orderId with placeholder
        RequestPartOverrider.OverridePartResult res2 = RequestPartOverrider.overridePathParam(res1.getUpdatedUrl(), template, "orderId", "{{orderId}}");
        assertEquals("https://api.example.com/v1/customers/999/orders/{{orderId}}", res2.getUpdatedUrl());
        assertEquals("456", res2.getOldValue());
    }
}
