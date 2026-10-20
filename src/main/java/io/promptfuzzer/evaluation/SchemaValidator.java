package io.promptfuzzer.evaluation;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Validates JSON against the commonly used subset of JSON Schema: {@code type} (single or list),
 * {@code properties}, {@code required}, {@code additionalProperties: false}, {@code enum}, {@code const},
 * {@code items}, {@code minimum}/{@code maximum}, {@code minLength}/{@code maxLength}, {@code pattern},
 * {@code minItems}/{@code maxItems}. Unknown keywords are ignored rather than rejected.
 */
public final class SchemaValidator {

    private SchemaValidator() {
    }

    /** Returns human-readable violations such as {@code $.room: expected string, got number}; empty if valid. */
    public static List<String> validate(JsonNode schema, JsonNode value) {
        List<String> errors = new ArrayList<>();
        if (schema != null && schema.isObject()) {
            validate(schema, value, "$", errors);
        }
        return errors;
    }

    private static void validate(JsonNode schema, JsonNode value, String path, List<String> errors) {
        JsonNode type = schema.get("type");
        if (type != null && !matchesType(type, value)) {
            errors.add(path + ": expected " + describe(type) + ", got " + typeOf(value));
            return;
        }
        JsonNode constant = schema.get("const");
        if (constant != null && !constant.equals(value)) {
            errors.add(path + ": must equal " + constant);
        }
        JsonNode enumValues = schema.get("enum");
        if (enumValues != null && enumValues.isArray()) {
            boolean found = false;
            for (JsonNode allowed : enumValues) {
                if (allowed.equals(value)) {
                    found = true;
                    break;
                }
            }
            if (!found) {
                errors.add(path + ": " + value + " is not one of " + enumValues);
            }
        }
        if (value.isObject()) {
            validateObject(schema, value, path, errors);
        } else if (value.isArray()) {
            validateArray(schema, value, path, errors);
        } else if (value.isNumber()) {
            JsonNode min = schema.get("minimum");
            JsonNode max = schema.get("maximum");
            if (min != null && value.asDouble() < min.asDouble()) {
                errors.add(path + ": " + value + " is below minimum " + min);
            }
            if (max != null && value.asDouble() > max.asDouble()) {
                errors.add(path + ": " + value + " is above maximum " + max);
            }
        } else if (value.isTextual()) {
            validateString(schema, value.asText(), path, errors);
        }
    }

    private static void validateObject(JsonNode schema, JsonNode value, String path, List<String> errors) {
        JsonNode required = schema.get("required");
        if (required != null && required.isArray()) {
            for (JsonNode r : required) {
                if (!value.has(r.asText())) {
                    errors.add(path + ": missing required field '" + r.asText() + "'");
                }
            }
        }
        JsonNode props = schema.get("properties");
        JsonNode additional = schema.get("additionalProperties");
        Iterator<Map.Entry<String, JsonNode>> fields = value.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            JsonNode propSchema = props == null ? null : props.get(field.getKey());
            String childPath = path + "." + field.getKey();
            if (propSchema != null) {
                validate(propSchema, field.getValue(), childPath, errors);
            } else if (additional != null && additional.isBoolean() && !additional.asBoolean()) {
                errors.add(path + ": unexpected field '" + field.getKey() + "'");
            } else if (additional != null && additional.isObject()) {
                validate(additional, field.getValue(), childPath, errors);
            }
        }
    }

    private static void validateArray(JsonNode schema, JsonNode value, String path, List<String> errors) {
        JsonNode minItems = schema.get("minItems");
        JsonNode maxItems = schema.get("maxItems");
        if (minItems != null && value.size() < minItems.asInt()) {
            errors.add(path + ": expected at least " + minItems.asInt() + " items");
        }
        if (maxItems != null && value.size() > maxItems.asInt()) {
            errors.add(path + ": expected at most " + maxItems.asInt() + " items");
        }
        JsonNode items = schema.get("items");
        if (items != null && items.isObject()) {
            for (int i = 0; i < value.size(); i++) {
                validate(items, value.get(i), path + "[" + i + "]", errors);
            }
        }
    }

    private static void validateString(JsonNode schema, String s, String path, List<String> errors) {
        JsonNode minLength = schema.get("minLength");
        JsonNode maxLength = schema.get("maxLength");
        if (minLength != null && s.length() < minLength.asInt()) {
            errors.add(path + ": shorter than " + minLength.asInt() + " characters");
        }
        if (maxLength != null && s.length() > maxLength.asInt()) {
            errors.add(path + ": longer than " + maxLength.asInt() + " characters");
        }
        JsonNode pattern = schema.get("pattern");
        if (pattern != null) {
            try {
                if (!Pattern.compile(pattern.asText()).matcher(s).find()) {
                    errors.add(path + ": '" + s + "' does not match pattern " + pattern.asText());
                }
            } catch (PatternSyntaxException e) {
                errors.add(path + ": schema pattern is invalid (" + pattern.asText() + ")");
            }
        }
    }

    private static boolean matchesType(JsonNode type, JsonNode value) {
        if (type.isArray()) {
            for (JsonNode t : type) {
                if (matchesType(t.asText(), value)) {
                    return true;
                }
            }
            return false;
        }
        return matchesType(type.asText(), value);
    }

    private static boolean matchesType(String type, JsonNode value) {
        return switch (type) {
            case "object" -> value.isObject();
            case "array" -> value.isArray();
            case "string" -> value.isTextual();
            case "number" -> value.isNumber();
            case "integer" -> value.isIntegralNumber() || (value.isNumber() && value.asDouble() == Math.rint(value.asDouble()));
            case "boolean" -> value.isBoolean();
            case "null" -> value.isNull();
            default -> true;
        };
    }

    private static String describe(JsonNode type) {
        if (type.isArray()) {
            List<String> names = new ArrayList<>();
            type.forEach(t -> names.add(t.asText()));
            return String.join(" or ", names);
        }
        return type.asText();
    }

    static String typeOf(JsonNode value) {
        if (value == null || value.isNull()) {
            return "null";
        }
        if (value.isObject()) {
            return "object";
        }
        if (value.isArray()) {
            return "array";
        }
        if (value.isTextual()) {
            return "string";
        }
        if (value.isNumber()) {
            return "number";
        }
        if (value.isBoolean()) {
            return "boolean";
        }
        return value.getNodeType().name().toLowerCase();
    }
}
