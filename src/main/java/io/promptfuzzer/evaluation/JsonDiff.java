package io.promptfuzzer.evaluation;

import com.fasterxml.jackson.databind.JsonNode;
import io.promptfuzzer.core.ComparisonOptions;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Structural comparison of an expected JSON value with an actual one. Produces a list of differences with
 * JSON paths so a failure can be explained precisely ("$.room: expected 'kitchen', got 'living room'").
 */
public final class JsonDiff {

    private JsonDiff() {
    }

    public enum Kind {
        /** Present in expected, absent in actual. */
        MISSING,
        /** Present in actual, absent in expected. */
        UNEXPECTED,
        /** Same type, different value. */
        MISMATCH,
        /** Different JSON types. */
        TYPE_MISMATCH
    }

    public record Difference(String path, Kind kind, JsonNode expected, JsonNode actual) {
        public String describe() {
            return switch (kind) {
                case MISSING -> path + ": missing (expected " + expected + ")";
                case UNEXPECTED -> path + ": unexpected value " + actual;
                case MISMATCH -> path + ": expected " + expected + ", got " + actual;
                case TYPE_MISMATCH -> path + ": expected " + SchemaValidator.typeOf(expected) + " "
                        + expected + ", got " + SchemaValidator.typeOf(actual) + " " + actual;
            };
        }
    }

    public static List<Difference> compare(JsonNode expected, JsonNode actual, ComparisonOptions options) {
        return compare(expected, actual, options, Set.of());
    }

    /** Compares, skipping the named top-level fields entirely. */
    public static List<Difference> compare(JsonNode expected, JsonNode actual, ComparisonOptions options,
                                           Set<String> skipTopLevel) {
        List<Difference> out = new ArrayList<>();
        diff(expected, actual, "$", options, skipTopLevel, out);
        return out;
    }

    /**
     * True when every field in {@code pattern} appears in {@code actual} with an equal value. Extra fields in
     * {@code actual} are allowed. Used to recognise "clarify" and "refuse" responses.
     */
    public static boolean containsPattern(JsonNode pattern, JsonNode actual, ComparisonOptions options) {
        if (pattern == null || actual == null) {
            return false;
        }
        return compare(pattern, actual, options).stream().allMatch(d -> d.kind() == Kind.UNEXPECTED);
    }

    private static void diff(JsonNode expected, JsonNode actual, String path, ComparisonOptions options,
                             Set<String> skip, List<Difference> out) {
        if (expected.isObject() && actual.isObject()) {
            diffObjects(expected, actual, path, options, skip, out);
        } else if (expected.isArray() && actual.isArray()) {
            if (options.unorderedArrays()) {
                diffUnordered(expected, actual, path, options, out);
            } else {
                diffOrdered(expected, actual, path, options, out);
            }
        } else if (!sameType(expected, actual)) {
            out.add(new Difference(path, Kind.TYPE_MISMATCH, expected, actual));
        } else if (!scalarEquals(expected, actual, options)) {
            out.add(new Difference(path, Kind.MISMATCH, expected, actual));
        }
    }

    private static void diffObjects(JsonNode expected, JsonNode actual, String path, ComparisonOptions options,
                                    Set<String> skip, List<Difference> out) {
        Iterator<Map.Entry<String, JsonNode>> it = expected.fields();
        while (it.hasNext()) {
            Map.Entry<String, JsonNode> e = it.next();
            if (skip.contains(e.getKey())) {
                continue;
            }
            String childPath = path + "." + e.getKey();
            JsonNode a = actual.get(e.getKey());
            boolean actualAbsent = a == null || (options.nullEqualsMissing() && a.isNull());
            boolean expectedAbsent = e.getValue().isNull() && options.nullEqualsMissing();
            if (actualAbsent && expectedAbsent) {
                continue;
            }
            if (actualAbsent) {
                out.add(new Difference(childPath, Kind.MISSING, e.getValue(), a));
            } else if (expectedAbsent) {
                out.add(new Difference(childPath, Kind.UNEXPECTED, e.getValue(), a));
            } else {
                diff(e.getValue(), a, childPath, options, Set.of(), out);
            }
        }
        Iterator<Map.Entry<String, JsonNode>> extra = actual.fields();
        while (extra.hasNext()) {
            Map.Entry<String, JsonNode> e = extra.next();
            if (skip.contains(e.getKey()) || expected.has(e.getKey())) {
                continue;
            }
            if (options.nullEqualsMissing() && e.getValue().isNull()) {
                continue;
            }
            out.add(new Difference(path + "." + e.getKey(), Kind.UNEXPECTED, null, e.getValue()));
        }
    }

    private static void diffOrdered(JsonNode expected, JsonNode actual, String path, ComparisonOptions options,
                                    List<Difference> out) {
        int n = Math.max(expected.size(), actual.size());
        for (int i = 0; i < n; i++) {
            String childPath = path + "[" + i + "]";
            if (i >= actual.size()) {
                out.add(new Difference(childPath, Kind.MISSING, expected.get(i), null));
            } else if (i >= expected.size()) {
                out.add(new Difference(childPath, Kind.UNEXPECTED, null, actual.get(i)));
            } else {
                diff(expected.get(i), actual.get(i), childPath, options, Set.of(), out);
            }
        }
    }

    /** Multiset comparison: each expected element is matched to an equal, not-yet-used actual element. */
    private static void diffUnordered(JsonNode expected, JsonNode actual, String path, ComparisonOptions options,
                                      List<Difference> out) {
        boolean[] used = new boolean[actual.size()];
        for (int i = 0; i < expected.size(); i++) {
            boolean matched = false;
            for (int j = 0; j < actual.size(); j++) {
                if (!used[j] && compare(expected.get(i), actual.get(j), options).isEmpty()) {
                    used[j] = true;
                    matched = true;
                    break;
                }
            }
            if (!matched) {
                out.add(new Difference(path + "[" + i + "]", Kind.MISSING, expected.get(i), null));
            }
        }
        for (int j = 0; j < actual.size(); j++) {
            if (!used[j]) {
                out.add(new Difference(path + "[" + j + "]", Kind.UNEXPECTED, null, actual.get(j)));
            }
        }
    }

    private static boolean sameType(JsonNode a, JsonNode b) {
        if (a.isNumber() && b.isNumber()) {
            return true;
        }
        return a.getNodeType() == b.getNodeType();
    }

    private static boolean scalarEquals(JsonNode expected, JsonNode actual, ComparisonOptions options) {
        if (expected.isNumber()) {
            return Math.abs(expected.asDouble() - actual.asDouble()) <= options.numericTolerance();
        }
        if (expected.isTextual() && options.caseInsensitiveStrings()) {
            return normalize(expected.asText()).equals(normalize(actual.asText()));
        }
        return expected.equals(actual);
    }

    private static String normalize(String s) {
        return s.strip().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }
}
