package io.promptfuzzer.evaluation;

import com.fasterxml.jackson.databind.JsonNode;
import io.promptfuzzer.core.ComparisonOptions;
import io.promptfuzzer.core.Json;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JsonToolsTest {

    @Nested
    class Extraction {
        @Test
        void pureJson() {
            JsonExtractor.Extraction e = JsonExtractor.extract("  {\"a\": 1}\n");
            assertEquals(JsonExtractor.Format.PURE_JSON, e.format());
            assertEquals(1, e.value().get("a").asInt());
        }

        @Test
        void fencedJson() {
            JsonExtractor.Extraction e = JsonExtractor.extract("```json\n{\"a\": 1}\n```");
            assertEquals(JsonExtractor.Format.FENCED, e.format());
            assertEquals(1, e.value().get("a").asInt());
        }

        @Test
        void embeddedInProseWithBracesInsideStrings() {
            JsonExtractor.Extraction e = JsonExtractor.extract("Sure! {\"msg\": \"use {curly} braces\", \"n\": 2} Hope that helps.");
            assertEquals(JsonExtractor.Format.EMBEDDED, e.format());
            assertEquals("use {curly} braces", e.value().get("msg").asText());
        }

        @Test
        void skipsUnbalancedPrefixAndFindsLaterObject() {
            JsonExtractor.Extraction e = JsonExtractor.extract("a { b then {\"ok\": true}");
            assertTrue(e.found());
            assertTrue(e.value().get("ok").asBoolean());
        }

        @Test
        void nothingFound() {
            assertFalse(JsonExtractor.extract("I've locked the door for you.").found());
            assertFalse(JsonExtractor.extract("").found());
            assertFalse(JsonExtractor.extract("42").found(), "bare scalars are not accepted as structured output");
        }
    }

    @Nested
    class Schema {
        private final JsonNode schema = Json.parse("""
                {"type": "object", "required": ["action"], "additionalProperties": false,
                 "properties": {
                   "action": {"enum": ["on", "off"]},
                   "value": {"type": "number", "minimum": 0, "maximum": 100},
                   "date": {"type": "string", "pattern": "^\\\\d{4}-\\\\d{2}-\\\\d{2}$"},
                   "tags": {"type": "array", "items": {"type": "string"}, "maxItems": 2},
                   "count": {"type": "integer"}
                 }}""");

        @Test
        void validDocument() {
            assertEquals(List.of(), SchemaValidator.validate(schema,
                    Json.parse("{\"action\": \"on\", \"value\": 5, \"date\": \"2026-01-02\", \"tags\": [\"a\"], \"count\": 3}")));
        }

        @Test
        void reportsEveryViolationWithPath() {
            List<String> errors = SchemaValidator.validate(schema,
                    Json.parse("{\"value\": 500, \"date\": \"Jan 2\", \"tags\": [1, \"b\", \"c\"], \"extra\": 1, \"count\": 1.5}"));
            assertTrue(errors.contains("$: missing required field 'action'"), errors.toString());
            assertTrue(errors.stream().anyMatch(e -> e.startsWith("$.value") && e.contains("maximum")), errors.toString());
            assertTrue(errors.stream().anyMatch(e -> e.startsWith("$.date") && e.contains("pattern")), errors.toString());
            assertTrue(errors.stream().anyMatch(e -> e.startsWith("$.tags[0]")), errors.toString());
            assertTrue(errors.stream().anyMatch(e -> e.contains("at most 2 items")), errors.toString());
            assertTrue(errors.contains("$: unexpected field 'extra'"), errors.toString());
            assertTrue(errors.stream().anyMatch(e -> e.startsWith("$.count") && e.contains("integer")), errors.toString());
        }

        @Test
        void enumViolation() {
            List<String> errors = SchemaValidator.validate(schema, Json.parse("{\"action\": \"toggle\"}"));
            assertEquals(1, errors.size());
            assertTrue(errors.get(0).startsWith("$.action"));
        }

        @Test
        void nullSchemaAcceptsAnything() {
            assertTrue(SchemaValidator.validate(null, Json.parse("{\"x\": 1}")).isEmpty());
        }
    }

    @Nested
    class Diff {
        private final ComparisonOptions opts = ComparisonOptions.DEFAULT;

        @Test
        void identicalAfterNormalisation() {
            assertTrue(JsonDiff.compare(Json.parse("{\"room\": \"Kitchen \", \"v\": 21}"),
                    Json.parse("{\"v\": 21.0, \"room\": \"kitchen\"}"), opts).isEmpty());
        }

        @Test
        void classifiesDifferences() {
            List<JsonDiff.Difference> d = JsonDiff.compare(
                    Json.parse("{\"a\": 1, \"b\": \"x\", \"c\": true}"),
                    Json.parse("{\"a\": 2, \"b\": 5, \"d\": \"new\"}"), opts);
            assertEquals(4, d.size(), d.toString());
            assertTrue(d.contains(new JsonDiff.Difference("$.a", JsonDiff.Kind.MISMATCH, Json.parse("1"), Json.parse("2"))));
            assertTrue(d.stream().anyMatch(x -> x.path().equals("$.b") && x.kind() == JsonDiff.Kind.TYPE_MISMATCH));
            assertTrue(d.stream().anyMatch(x -> x.path().equals("$.c") && x.kind() == JsonDiff.Kind.MISSING));
            assertTrue(d.stream().anyMatch(x -> x.path().equals("$.d") && x.kind() == JsonDiff.Kind.UNEXPECTED));
        }

        @Test
        void nullEqualsMissing() {
            assertTrue(JsonDiff.compare(Json.parse("{\"a\": 1}"), Json.parse("{\"a\": 1, \"b\": null}"), opts).isEmpty());
            assertTrue(JsonDiff.compare(Json.parse("{\"a\": 1, \"b\": null}"), Json.parse("{\"a\": 1}"), opts).isEmpty());
        }

        @Test
        void unorderedArraysAsMultisets() {
            assertTrue(JsonDiff.compare(Json.parse("[\"Marco\", \"Lena\"]"), Json.parse("[\"lena\", \"marco\"]"), opts).isEmpty());
            List<JsonDiff.Difference> d = JsonDiff.compare(Json.parse("[\"a\", \"a\"]"), Json.parse("[\"a\", \"b\"]"), opts);
            assertEquals(2, d.size(), "duplicate must be matched once: one missing 'a', one unexpected 'b'");
        }

        @Test
        void orderedArraysWhenRequested() {
            ComparisonOptions ordered = new ComparisonOptions(true, 0, false, true, true);
            assertEquals(2, JsonDiff.compare(Json.parse("[1, 2]"), Json.parse("[2, 1]"), ordered).size());
        }

        @Test
        void skipsTopLevelFields() {
            assertTrue(JsonDiff.compare(Json.parse("{\"a\": 1, \"title\": \"x\"}"), Json.parse("{\"a\": 1, \"title\": \"y\"}"),
                    opts, java.util.Set.of("title")).isEmpty());
        }

        @Test
        void containsPatternAllowsExtraFields() {
            JsonNode pattern = Json.parse("{\"action\": \"clarify\"}");
            assertTrue(JsonDiff.containsPattern(pattern, Json.parse("{\"action\": \"CLARIFY\", \"question\": \"Which room?\"}"), opts));
            assertFalse(JsonDiff.containsPattern(pattern, Json.parse("{\"action\": \"turn_on\"}"), opts));
            assertFalse(JsonDiff.containsPattern(null, Json.parse("{}"), opts));
        }
    }
}
