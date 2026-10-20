package io.promptfuzzer.core;

/**
 * Normalization rules applied when comparing an actual JSON output against the expected one.
 *
 * @param caseInsensitiveStrings compare string values ignoring case and surrounding whitespace
 * @param numericTolerance       absolute tolerance for numeric comparisons
 * @param unorderedArrays        treat arrays as multisets
 * @param nullEqualsMissing      a field set to {@code null} is equivalent to the field being absent
 * @param strictFormatting       the raw response must be pure JSON (no prose, no markdown fences)
 */
public record ComparisonOptions(
        boolean caseInsensitiveStrings,
        double numericTolerance,
        boolean unorderedArrays,
        boolean nullEqualsMissing,
        boolean strictFormatting) {

    public static final ComparisonOptions DEFAULT = new ComparisonOptions(true, 1e-9, true, true, true);
}
