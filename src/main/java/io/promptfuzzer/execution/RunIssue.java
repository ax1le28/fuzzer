package io.promptfuzzer.execution;

/**
 * Why a result says nothing about the model. Call errors ({@code ERROR}) were attempted and failed;
 * skipped calls ({@code SKIPPED}) were never sent. Neither counts towards the pass rate.
 */
public enum RunIssue {

    CONNECTION_FAILED(false, "Connection failed",
            "The model server could not be reached, so no response was received."),
    TIMEOUT(false, "Timed out",
            "The request was sent but no response arrived before the timeout."),
    PROVIDER_ERROR(false, "Provider error",
            "The provider rejected the request or returned a reply that could not be read."),
    EVALUATION_ERROR(false, "Evaluation inconclusive",
            "A response arrived but could not be graded, usually because the AI judge call failed."),
    REQUEST_LIMIT(true, "Request limit reached",
            "Not sent: the run's request ceiling was reached. Raise PF_MAX_REQUESTS or run fewer tests."),
    COST_LIMIT(true, "Cost limit reached",
            "Not sent: the run's dollar budget was used up. Raise the budget or run fewer tests."),
    ENDPOINT_UNAVAILABLE(true, "Endpoint unavailable",
            "Not sent: the endpoint refused several connections in a row, so the remaining calls were stopped."),
    CANCELLED(true, "Cancelled",
            "Not sent: the run was cancelled.");

    private final boolean skipped;
    private final String label;
    private final String description;

    RunIssue(boolean skipped, String label, String description) {
        this.skipped = skipped;
        this.label = label;
        this.description = description;
    }

    /** True when the call was never sent; false when it was attempted and failed. */
    public boolean skipped() {
        return skipped;
    }

    public String label() {
        return label;
    }

    public String description() {
        return description;
    }
}
