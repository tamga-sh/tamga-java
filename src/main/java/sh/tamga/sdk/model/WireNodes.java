package sh.tamga.sdk.model;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Null-tolerant readers for JSON:API attribute nodes. Package-private -- an internal wire-decoding
 * helper, not part of this SDK's public model surface.
 *
 * <p>Every reader treats a missing field, an explicit JSON {@code null}, and an unparseable value
 * the same way: as absent. Response decoding must never throw on a field the server omitted or
 * reshaped, because {@code FAIL_ON_UNKNOWN_PROPERTIES} being disabled elsewhere already commits
 * this SDK to tolerating server-side schema drift.
 */
final class WireNodes {

  private WireNodes() {
  }

  static String text(JsonNode parent, String field) {
    JsonNode node = parent == null ? null : parent.get(field);
    return node == null || node.isNull() ? null : node.asText();
  }

  static Instant instant(JsonNode parent, String field) {
    String raw = text(parent, field);
    if (raw == null || raw.isEmpty()) {
      return null;
    }
    try {
      return Instant.parse(raw);
    } catch (DateTimeParseException ignored) {
      return null;
    }
  }

  static Integer integer(JsonNode parent, String field) {
    JsonNode node = parent == null ? null : parent.get(field);
    return node == null || node.isNull() || !node.isNumber() ? null : node.asInt();
  }

  static Long longValue(JsonNode parent, String field) {
    JsonNode node = parent == null ? null : parent.get(field);
    return node == null || node.isNull() || !node.isNumber() ? null : node.asLong();
  }

  static int intOrZero(JsonNode parent, String field) {
    Integer value = integer(parent, field);
    return value == null ? 0 : value;
  }

  /**
   * Reads a boolean that is genuinely absent on some responses, so "not sent" stays distinct from
   * "sent as false". Use {@link #bool} where the server always sends the field.
   */
  static Boolean booleanOrNull(JsonNode parent, String field) {
    JsonNode node = parent == null ? null : parent.get(field);
    return node == null || node.isNull() || !node.isBoolean() ? null : node.asBoolean();
  }

  static boolean bool(JsonNode parent, String field) {
    JsonNode node = parent == null ? null : parent.get(field);
    return node != null && node.asBoolean(false);
  }

  static Map<String, Object> objectMap(JsonNode parent, String field) {
    JsonNode node = parent == null ? null : parent.get(field);
    if (node == null || node.isNull() || !node.isObject()) {
      return null;
    }
    Map<String, Object> map = TamgaJsonMapper.instance().convertValue(node,
        new com.fasterxml.jackson.core.type.TypeReference<LinkedHashMap<String, Object>>() {
        });
    return map;
  }
}
