package sh.tamga.sdk.model;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;

/**
 * The {@code {ts, valid, detail, code}} object returned alongside a license resource by
 * validate-by-key and validate-by-id, and returned as the <em>entire flat body</em> of
 * quick-validate.
 *
 * <p>Branch on {@link #code()}, which is stable. {@link #detail()} is human-readable text that may
 * be reworded between server versions.
 */
public final class ValidationMeta {

  private final Instant ts;
  private final boolean valid;
  private final String detail;
  private final ValidationCode code;

  ValidationMeta(Instant ts, boolean valid, String detail, ValidationCode code) {
    this.ts = ts;
    this.valid = valid;
    this.detail = detail;
    this.code = code;
  }

  /**
   * Builds a meta from already-known values, for the one case where a verdict is reached without a
   * validation response: a machine creation rejected by the server's own limit check.
   *
   * <p>{@code ts} is the moment the verdict was reached locally, not a server timestamp, and is
   * allowed to be {@code null}.
   */
  public static ValidationMeta of(Instant ts, boolean valid, String detail, ValidationCode code) {
    return new ValidationMeta(ts, valid, detail, code == null ? ValidationCode.UNKNOWN : code);
  }

  /**
   * Decodes a validation meta object. Accepts both the {@code meta} block of a JSON:API validation
   * response and quick-validate's flat top-level body, which have the same four fields.
   */
  public static ValidationMeta fromJson(JsonNode node) {
    if (node == null || node.isNull()) {
      return new ValidationMeta(null, false, null, ValidationCode.UNKNOWN);
    }
    JsonNode tsNode = node.get("ts");
    Instant ts = null;
    if (tsNode != null && tsNode.isTextual()) {
      try {
        ts = Instant.parse(tsNode.asText());
      } catch (java.time.format.DateTimeParseException ignored) {
        ts = null;
      }
    }
    JsonNode detailNode = node.get("detail");
    JsonNode codeNode = node.get("code");
    return new ValidationMeta(
        ts,
        node.path("valid").asBoolean(false),
        detailNode == null || detailNode.isNull() ? null : detailNode.asText(),
        ValidationCode.fromWireValue(
            codeNode == null || codeNode.isNull() ? null : codeNode.asText()));
  }

  /** Returns the server timestamp at which validation ran, or {@code null} if unparseable. */
  public Instant ts() {
    return ts;
  }

  /** Returns whether the license passed validation. */
  public boolean valid() {
    return valid;
  }

  /** Returns human-readable detail text. Never match on this -- match on {@link #code()}. */
  public String detail() {
    return detail;
  }

  /** Returns the stable validation result code. */
  public ValidationCode code() {
    return code;
  }
}
