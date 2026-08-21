package sh.tamga.sdk.model;

/**
 * The outcome of an upgrade check: either a release is on offer, or none is.
 *
 * <p><b>"No release on offer" is not the same as "you are up to date", and the server cannot tell
 * you which it meant.</b> {@code 204 No Content} answers two different situations by design:
 *
 * <ol>
 *   <li>there is no newer release than the version supplied, or
 *   <li>there <em>is</em> a newer release, but this license is not entitled to it -- an expired
 *       license whose policy stopped its access at expiry.
 * </ol>
 *
 * <p>The server's own comment gives the reason: refusing the second case outright would leak that
 * a newer version exists, and {@code 204} is the honest answer to "what may I have next?" in both.
 * There is deliberately no client-side way to separate them, and there should not be one.
 *
 * <p>So phrase what this reports as <b>no update is available to you</b>. An updater that renders
 * {@link #updateOffered()} {@code false} as "you're on the latest version" will tell a customer
 * with a lapsed license that nothing has shipped since, which is the one thing the response was
 * shaped not to say.
 *
 * <p>Two further outcomes are <em>not</em> represented here because they arrive as exceptions
 * rather than as this type: a <b>suspended</b> license gets {@code 403}, not {@code 204} -- so a
 * suspension does surface, unlike an expiry -- and an unknown product id gets {@code 404}.
 */
public final class UpgradeCheckResult {

  private static final UpgradeCheckResult NONE = new UpgradeCheckResult(null);

  private final Release release;

  private UpgradeCheckResult(Release release) {
    this.release = release;
  }

  /** Returns the result for a {@code 204}: nothing is being offered, for either of two reasons. */
  public static UpgradeCheckResult none() {
    return NONE;
  }

  /** Returns a result carrying the release the server offered. */
  public static UpgradeCheckResult of(Release release) {
    return release == null ? NONE : new UpgradeCheckResult(release);
  }

  /**
   * Reports whether a release was offered.
   *
   * <p>{@code false} means "nothing is available to you", never "you are current" -- see this
   * class's note.
   */
  public boolean updateOffered() {
    return release != null;
  }

  /** Returns the offered release, or {@code null} when none was. */
  public Release release() {
    return release;
  }
}
