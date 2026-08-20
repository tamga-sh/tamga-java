package sh.tamga.sdk.model;

/**
 * Options for validate-by-id.
 *
 * <p>{@code skipTouch} asks the server not to update the license's last-validated timestamp, which
 * is useful for a background check that should not look like user activity.
 */
public final class ValidateOptions {

  private final Scope scope;
  private final boolean skipTouch;

  private ValidateOptions(Scope scope, boolean skipTouch) {
    this.scope = scope;
    this.skipTouch = skipTouch;
  }

  /** Returns options with no scope constraints and the default touch behaviour. */
  public static ValidateOptions defaults() {
    return new ValidateOptions(null, false);
  }

  /** Returns a copy constrained by the given scope. */
  public ValidateOptions withScope(Scope value) {
    return new ValidateOptions(value, skipTouch);
  }

  /** Returns a copy that asks the server not to touch the last-validated timestamp. */
  public ValidateOptions withSkipTouch(boolean value) {
    return new ValidateOptions(scope, value);
  }

  /** Returns the scope constraints, or {@code null} when unconstrained. */
  public Scope scope() {
    return scope;
  }

  /** Returns whether the last-validated timestamp should be left untouched. */
  public boolean skipTouch() {
    return skipTouch;
  }
}
