package sh.tamga.sdk.error;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * A single JSON:API error object as returned by the Tamga API.
 *
 * <p>The wire shape is:
 *
 * <pre>{@code
 * {"errors": [{id, status, code, title, detail, source: {pointer}}]}
 * }</pre>
 *
 * <p>{@link #code()} is stable and is what matching logic must use. {@link #detail()} is
 * human-readable text that may be reworded between server versions -- never match on it.
 */
public final class TamgaError {

  /** The code used when the server's response body is absent, unreadable, or not JSON:API. */
  public static final String UNKNOWN_CODE = "UNKNOWN";

  private final String id;
  private final String status;
  private final String code;
  private final String title;
  private final String detail;
  private final String pointer;

  /** Creates an error object from already-extracted fields. */
  public TamgaError(String id, String status, String code, String title, String detail,
      String pointer) {
    this.id = id;
    this.status = status;
    this.code = code == null || code.isEmpty() ? UNKNOWN_CODE : code;
    this.title = title;
    this.detail = detail;
    this.pointer = pointer;
  }

  /**
   * Decodes the first entry of a {@code {"errors": [...]}} document.
   *
   * <p>Returns a synthetic {@link #UNKNOWN_CODE} error when the body is null, is not a JSON:API
   * error document, or carries an empty {@code errors} array. Error bodies come from the network
   * and are untrusted -- decoding one must never throw.
   */
  public static TamgaError fromErrorDocument(JsonNode document, String fallbackDetail) {
    JsonNode errors = document == null ? null : document.get("errors");
    if (errors == null || !errors.isArray() || errors.size() == 0) {
      return new TamgaError(null, null, UNKNOWN_CODE, "Unknown Error", fallbackDetail, null);
    }
    JsonNode first = errors.get(0);
    JsonNode source = first.get("source");
    return new TamgaError(
        textOrNull(first, "id"),
        textOrNull(first, "status"),
        textOrNull(first, "code"),
        textOrNull(first, "title"),
        textOrNull(first, "detail"),
        source == null ? null : textOrNull(source, "pointer"));
  }

  private static String textOrNull(JsonNode parent, String field) {
    JsonNode node = parent == null ? null : parent.get(field);
    return node == null || node.isNull() ? null : node.asText();
  }

  /** Returns the server-assigned error id, or {@code null}. */
  public String id() {
    return id;
  }

  /** Returns the HTTP status as the server reported it in the body, or {@code null}. */
  public String status() {
    return status;
  }

  /** Returns the stable error code. This is what callers should match on. */
  public String code() {
    return code;
  }

  /** Returns the short error title, or {@code null}. */
  public String title() {
    return title;
  }

  /** Returns human-readable detail. Never match on this -- match on {@link #code()}. */
  public String detail() {
    return detail;
  }

  /** Returns the JSON pointer identifying the offending request field, or {@code null}. */
  public String pointer() {
    return pointer;
  }
}
