package sh.tamga.sdk.model;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Query parameters for the upgrade check -- "is there a newer build of this product for me?".
 *
 * <p>The server requires {@code product}, {@code platform}, {@code filetype} and {@code version},
 * and treats {@code channel} and {@code constraint} as optional.
 *
 * <p><b>{@code channel} is required here even though the server allows it to be omitted.</b>
 * Omitting it server-side does not mean "stable": it matches <em>every</em> channel, alpha and dev
 * included, so an updater that leaves it out can be offered a prerelease it was never meant to
 * see. Naming the channel at the API boundary makes that a decision rather than an accident.
 *
 * <p>{@code constraint} defaults server-side to patch-only ({@code ~x.y.z}) when it is absent, so
 * a caller who wants minor or major upgrades has to say so.
 *
 * <p><b>The query is not validated here.</b> The upgrade handler parses it with a bare extractor
 * rather than this API's JSON:API-aware one, so a missing or malformed parameter comes back as a
 * plain-text {@code 400} instead of an error document -- it surfaces through the usual error path
 * as a synthetic {@code UNKNOWN} code.
 */
public final class UpgradeCheckOptions {

  private final String productId;
  private final String platform;
  private final String filetype;
  private final String version;
  private final String channel;
  private final String constraint;

  @SuppressWarnings("checkstyle:ParameterNumber")
  private UpgradeCheckOptions(String productId, String platform, String filetype, String version,
      String channel, String constraint) {
    this.productId = productId;
    this.platform = platform;
    this.filetype = filetype;
    this.version = version;
    this.channel = channel;
    this.constraint = constraint;
  }

  /**
   * Creates options for a product, platform, file type, currently-installed version and channel.
   *
   * @param productId the product whose releases to search
   * @param platform the platform string the release was published for
   * @param filetype the artifact file type, such as {@code dmg} or {@code exe}
   * @param version the version currently installed -- what a newer release is measured against
   * @param channel the release channel to search; see this class's note on why it is not optional
   */
  public static UpgradeCheckOptions of(String productId, String platform, String filetype,
      String version, String channel) {
    return new UpgradeCheckOptions(productId, platform, filetype, version, channel, null);
  }

  /**
   * Returns a copy carrying a semver range that bounds how far an upgrade may go, such as
   * {@code ^1.2.0}. Absent, the server allows patch releases only.
   */
  public UpgradeCheckOptions withConstraint(String value) {
    return new UpgradeCheckOptions(productId, platform, filetype, version, channel, value);
  }

  /** Returns the product id being checked. */
  public String productId() {
    return productId;
  }

  /** Returns the currently-installed version the check is measured against. */
  public String version() {
    return version;
  }

  /** Renders these options as query parameters, omitting an unset {@code constraint}. */
  public Map<String, String> toQuery() {
    Map<String, String> query = new LinkedHashMap<>();
    query.put("product", productId);
    query.put("platform", platform);
    query.put("filetype", filetype);
    query.put("version", version);
    query.put("channel", channel);
    if (constraint != null && !constraint.isEmpty()) {
      query.put("constraint", constraint);
    }
    return query;
  }
}
