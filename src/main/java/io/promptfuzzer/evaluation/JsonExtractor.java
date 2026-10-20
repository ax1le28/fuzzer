package io.promptfuzzer.evaluation;

import com.fasterxml.jackson.databind.JsonNode;
import io.promptfuzzer.core.Json;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds the JSON value in a model response and records how cleanly it was delivered. A response wrapped in
 * markdown fences or surrounded by prose still yields a value, so content can be judged separately from
 * formatting.
 */
public final class JsonExtractor {

    private static final Pattern FENCE = Pattern.compile("```(?:json|JSON)?\\s*\\n?(.*?)\\n?```", Pattern.DOTALL);

    private JsonExtractor() {
    }

    public enum Format {
        /** The whole response is a JSON value. */
        PURE_JSON,
        /** JSON inside a markdown code fence. */
        FENCED,
        /** JSON embedded in surrounding prose. */
        EMBEDDED,
        /** No JSON value could be found. */
        NONE
    }

    public record Extraction(JsonNode value, Format format) {
        public boolean found() {
            return format != Format.NONE;
        }
    }

    public static Extraction extract(String text) {
        String trimmed = text == null ? "" : text.strip();
        if (trimmed.isEmpty()) {
            return new Extraction(null, Format.NONE);
        }
        JsonNode direct = tryParse(trimmed);
        if (direct != null && (direct.isObject() || direct.isArray())) {
            return new Extraction(direct, Format.PURE_JSON);
        }
        Matcher fence = FENCE.matcher(trimmed);
        if (fence.find()) {
            JsonNode fenced = tryParse(fence.group(1).strip());
            if (fenced != null && (fenced.isObject() || fenced.isArray())) {
                return new Extraction(fenced, Format.FENCED);
            }
        }
        String embedded = firstBalancedObject(trimmed);
        if (embedded != null) {
            JsonNode node = tryParse(embedded);
            if (node != null) {
                return new Extraction(node, Format.EMBEDDED);
            }
        }
        return new Extraction(null, Format.NONE);
    }

    private static JsonNode tryParse(String s) {
        try {
            return Json.MAPPER.readTree(s);
        } catch (Exception e) {
            return null;
        }
    }

    /** Returns the first {@code {...}} span with balanced braces, honouring string literals. */
    static String firstBalancedObject(String s) {
        int start = s.indexOf('{');
        while (start >= 0) {
            int depth = 0;
            boolean inString = false;
            boolean escaped = false;
            for (int i = start; i < s.length(); i++) {
                char c = s.charAt(i);
                if (inString) {
                    if (escaped) {
                        escaped = false;
                    } else if (c == '\\') {
                        escaped = true;
                    } else if (c == '"') {
                        inString = false;
                    }
                } else if (c == '"') {
                    inString = true;
                } else if (c == '{') {
                    depth++;
                } else if (c == '}') {
                    depth--;
                    if (depth == 0) {
                        String candidate = s.substring(start, i + 1);
                        if (tryParse(candidate) != null) {
                            return candidate;
                        }
                        break;
                    }
                }
            }
            start = s.indexOf('{', start + 1);
        }
        return null;
    }
}
