package sh.tamga.sdk.model;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Recursively alphabetically-key-sorted (by UTF-8 byte order), whitespace-free JSON serialization
 * -- reproduces {@code serde_json::Value}'s {@code BTreeMap}-backed serialization order (see
 * {@code proof.OfflineProof}'s remarks). Arrays keep their original element order (JSON arrays are
 * ordered by spec; only object keys get sorted).
 *
 * <p>Walks the natural {@code Object}-boxed tree Jackson produces when binding into {@code
 * Map<String, Object>}/{@code List<Object>} ({@link String}, {@link Integer}/{@link Long}, {@link
 * Double}, {@link Boolean}, {@link Map}, {@link List}, {@code null} -- confirmed empirically this
 * is Jackson's actual default boxing, not assumed) rather than introducing a parallel sealed
 * value type.
 *
 * <p><b>SECURITY</b> -- sorts object keys by UTF-8 byte order via {@link Arrays#compareUnsigned},
 * NOT {@code String.compareTo()}/{@code TreeMap}'s natural ordering. Confirmed empirically that
 * these genuinely diverge in Java for the same class of adversarial pair that broke tamga-js's
 * {@code canonicalJson.ts}: an astral-plane character (U+10000, a UTF-16 surrogate pair) sorts
 * BEFORE a BMP private-use character (U+E000) under {@code String.compareTo()} (UTF-16 code-unit
 * order: {@code 0xD800 < 0xE000}), but AFTER it under UTF-8 byte order ({@code 0xF0 > 0xEE}) --
 * the exact opposite order. Unlike Swift, where the equivalent check did NOT reproduce this
 * divergence for the pairs tested, Java's default {@link String} ordering genuinely gets this
 * wrong, so the explicit byte-order comparator here is required, not defensive-only.
 */
public final class CanonicalJson {

  private CanonicalJson() {
  }

  /**
   * Serializes a JSON value tree ({@link Map}, {@link List}, {@link String}, {@link Integer},
   * {@link Long}, {@link Double}, {@link Boolean}, or {@code null}) to canonical JSON text.
   */
  public static String serialize(Object value) {
    StringBuilder out = new StringBuilder();
    write(value, out);
    return out.toString();
  }

  private static void write(Object value, StringBuilder out) {
    if (value == null) {
      out.append("null");
    } else if (value instanceof String) {
      writeEscapedString((String) value, out);
    } else if (value instanceof Boolean) {
      out.append(((Boolean) value).booleanValue());
    } else if (value instanceof Integer || value instanceof Long) {
      out.append(value);
    } else if (value instanceof Double) {
      writeDouble((Double) value, out);
    } else if (value instanceof Map) {
      writeObject((Map<?, ?>) value, out);
    } else if (value instanceof List) {
      writeArray((List<?>) value, out);
    } else {
      throw new IllegalArgumentException(
          "Unsupported JSON value type for canonical serialization: " + value.getClass());
    }
  }

  private static void writeDouble(Double value, StringBuilder out) {
    // NOTE: uses Java's default Double.toString() shortest-round-trip formatting, not a
    // byte-for-byte port of Rust's ryu algorithm. Both target the same "shortest decimal that
    // round-trips to the exact same IEEE 754 bit pattern" property, so common values format
    // identically, but this is not exhaustively verified against serde_json across every float
    // edge case. Prefer Integer/Long dataset fields where possible.
    out.append(value);
  }

  private static void writeArray(List<?> elements, StringBuilder out) {
    out.append('[');
    for (int i = 0; i < elements.size(); i++) {
      if (i > 0) {
        out.append(',');
      }
      write(elements.get(i), out);
    }
    out.append(']');
  }

  private static void writeObject(Map<?, ?> fields, StringBuilder out) {
    out.append('{');
    String[] sortedKeys = fields.keySet().stream().map(Object::toString)
        .sorted(CanonicalJson::compareUtf8Bytes).toArray(String[]::new);
    for (int i = 0; i < sortedKeys.length; i++) {
      if (i > 0) {
        out.append(',');
      }
      String key = sortedKeys[i];
      writeEscapedString(key, out);
      out.append(':');
      write(fields.get(key), out);
    }
    out.append('}');
  }

  private static int compareUtf8Bytes(String first, String second) {
    byte[] firstBytes = first.getBytes(StandardCharsets.UTF_8);
    byte[] secondBytes = second.getBytes(StandardCharsets.UTF_8);
    return Arrays.compareUnsigned(firstBytes, secondBytes);
  }

  /**
   * JSON string escaping matching serde_json's default: {@code "} and {@code \} are escaped, the
   * standard short escapes ({@code \n}/{@code \r}/{@code \t}/{@code \b}/{@code \f}) are used for
   * their respective control characters, remaining control characters (0x00-0x1F) become a
   * numeric escape (backslash, lowercase u, 4 hex digits), and everything else -- including
   * non-ASCII characters -- is emitted as raw UTF-8, NOT given a numeric escape. serde_json does
   * not escape {@code /}.
   */
  private static void writeEscapedString(String string, StringBuilder out) {
    out.append('"');
    int length = string.length();
    for (int i = 0; i < length; i++) {
      char c = string.charAt(i);
      switch (c) {
        case '"':
          out.append("\\\"");
          break;
        case '\\':
          out.append("\\\\");
          break;
        case '\b':
          out.append("\\b");
          break;
        case '\f':
          out.append("\\f");
          break;
        case '\n':
          out.append("\\n");
          break;
        case '\r':
          out.append("\\r");
          break;
        case '\t':
          out.append("\\t");
          break;
        default:
          if (c < 0x20) {
            out.append(String.format("\\u%04x", (int) c));
          } else {
            out.append(c);
          }
      }
    }
    out.append('"');
  }
}
