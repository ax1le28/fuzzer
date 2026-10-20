package io.promptfuzzer.core;

/** What a correct application should do with a test input. */
public enum ExpectedBehavior {
    /** Produce the test case's expected output. */
    MATCH_EXPECTED,
    /** Ask the user for missing or ambiguous information instead of guessing. */
    ASK_CLARIFICATION,
    /** Decline the request (unsupported, unsafe, or out of scope). */
    REFUSE
}
