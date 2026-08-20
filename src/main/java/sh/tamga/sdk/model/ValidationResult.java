package sh.tamga.sdk.model;

/** A license together with the validation verdict returned alongside it. */
public final class ValidationResult {

  private final License license;
  private final ValidationMeta meta;

  /** Creates a result pairing a license with its validation meta. */
  public ValidationResult(License license, ValidationMeta meta) {
    this.license = license;
    this.meta = meta;
  }

  /** Returns the license resource. */
  public License license() {
    return license;
  }

  /** Returns the validation verdict. Branch on {@code meta().code()}. */
  public ValidationMeta meta() {
    return meta;
  }

  /** Returns whether validation passed, a shorthand for {@code meta().valid()}. */
  public boolean valid() {
    return meta != null && meta.valid();
  }
}
