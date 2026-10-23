package com.sabyasachi.apiregression.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class RunOptions {

    private int timeoutSeconds = 30;
    private int retryCount = 0;
    private int threads = 1;

    public RunOptions() {
    }

    public RunOptions(int timeoutSeconds, int retryCount, int threads) {
        this.timeoutSeconds = timeoutSeconds > 0 ? timeoutSeconds : 30;
        this.retryCount = Math.max(0, retryCount);
        this.threads = Math.max(1, threads);
    }

    public int getTimeoutSeconds() {
        return timeoutSeconds;
    }

    public void setTimeoutSeconds(int timeoutSeconds) {
        this.timeoutSeconds = timeoutSeconds > 0 ? timeoutSeconds : 30;
    }

    public int getRetryCount() {
        return retryCount;
    }

    public void setRetryCount(int retryCount) {
        this.retryCount = Math.max(0, retryCount);
    }

    public int getThreads() {
        return threads;
    }

    public void setThreads(int threads) {
        this.threads = Math.max(1, threads);
    }
}
