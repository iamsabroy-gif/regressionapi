package com.sabyasachi.apiregression.payload;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class OverrideException extends RuntimeException {

    private final ReasonCode reasonCode;
    private final List<String> candidates;

    public OverrideException(ReasonCode reasonCode, String message) {
        this(reasonCode, message, Collections.emptyList(), null);
    }

    public OverrideException(ReasonCode reasonCode, String message, List<String> candidates) {
        this(reasonCode, message, candidates, null);
    }

    public OverrideException(ReasonCode reasonCode, String message, Throwable cause) {
        this(reasonCode, message, Collections.emptyList(), cause);
    }

    public OverrideException(ReasonCode reasonCode, String message, List<String> candidates, Throwable cause) {
        super(message, cause);
        this.reasonCode = reasonCode;
        this.candidates = candidates != null ? new ArrayList<>(candidates) : Collections.emptyList();
    }

    public ReasonCode getReasonCode() {
        return reasonCode;
    }

    public List<String> getCandidates() {
        return candidates;
    }
}
