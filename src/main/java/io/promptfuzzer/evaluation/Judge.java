package io.promptfuzzer.evaluation;

/**
 * Answers questions that code cannot settle objectively. Implementations may be wrong; their verdicts are
 * always labelled {@link Evaluation.Method#AI_JUDGE} and never override a failed deterministic check.
 */
public interface Judge {

    Verdict judge(Question question);

    enum Kind {
        /** Do two free-text values convey the same meaning in context? */
        SEMANTIC_EQUIVALENCE,
        /** Did the response ask for clarification instead of guessing? */
        ASKS_CLARIFICATION,
        /** Did the response decline the request? */
        REFUSES
    }

    /**
     * @param kind        what is being asked
     * @param task        description of the application, for context
     * @param input       the test input (untrusted)
     * @param expected    expected value (SEMANTIC_EQUIVALENCE only)
     * @param actual      the response or field value being judged (untrusted)
     */
    record Question(Kind kind, String task, String input, String expected, String actual) {
    }

    /**
     * @param pass       the judge's answer
     * @param confidence self-reported, 0..1; not calibrated
     * @param reasoning  short justification
     */
    record Verdict(boolean pass, double confidence, String reasoning) {
    }

    /** Thrown when the judge could not produce a usable verdict. */
    class JudgeException extends RuntimeException {
        public JudgeException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
