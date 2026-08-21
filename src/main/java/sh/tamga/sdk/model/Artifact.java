package sh.tamga.sdk.model;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.Map;

/**
 * An artifact resource -- one uploaded file belonging to a release, such as an installer or an
 * update package.
 *
 * <p><b>Its attribute keys are camelCase, and the two timestamps are exceptions on top of that.</b>
 * The serialiser carries {@code rename_all = "camelCase"}
 * ({@code artifacts/serializer.rs:20}) so the presigned URL arrives as {@code redirectUrl}, but
 * {@code created_at} and {@code updated_at} are renamed individually to {@code created} and
 * {@code updated} ({@code :34-37}) -- <em>not</em> {@code createdAt}/{@code updatedAt}. A decoder
 * that applies the camelCase rule uniformly reads two null timestamps and nothing else complains.
 * {@link Release} carries the same pair of rules.
 *
 * <p>{@link #redirectUrl()} is populated only by
 * {@link sh.tamga.sdk.TamgaClient#requestArtifactDownload(String)}. The list and show routes omit
 * the field entirely ({@code skip_serializing_if = "Option::is_none"}), so it reads {@code null}
 * there -- absence is normal, not an error.
 *
 * <p><b>Read and download are the only artifact operations a license key can perform.</b>
 * {@code Role::LicenseToken} carries {@code artifact.read} and {@code artifact.download}
 * ({@code authz/mod.rs:264-265}) -- the first was always there, the second was granted by
 * {@code e6d317b} -- and does not carry {@code artifact.create},
 * {@code artifact.update} or {@code artifact.delete}, so creating, replacing, uploading or
 * deleting an artifact is refused to every credential this SDK issues. Those routes are
 * deliberately not modelled here.
 */
public final class Artifact {

  /**
   * Shortest presigned-URL lifetime the server accepts, one minute
   * ({@code artifacts/service.rs:15}).
   *
   * <p>Anything below it is refused with {@code 422 PRESIGN_TTL_INVALID} rather than clamped, on
   * the download route.
   */
  public static final Duration MIN_DOWNLOAD_TTL = Duration.ofSeconds(60);

  /** Longest presigned-URL lifetime the server accepts, one week ({@code service.rs:17}). */
  public static final Duration MAX_DOWNLOAD_TTL = Duration.ofSeconds(604_800);

  /**
   * The lifetime the server applies when the caller names none: five minutes
   * ({@code service.rs:20}).
   *
   * <p>Short enough that a URL captured from a log is usually already dead, and short enough that
   * a large download over a slow link can outlive it -- name a longer one explicitly for a big
   * artifact rather than assuming this is generous.
   */
  public static final Duration DEFAULT_DOWNLOAD_TTL = Duration.ofSeconds(300);

  private final String id;
  private final String filename;
  private final String filetype;
  private final Long filesize;
  private final String checksum;
  private final String platform;
  private final String arch;
  private final String signature;
  private final String status;
  private final String redirectUrl;
  private final Map<String, Object> metadata;
  private final Instant created;
  private final Instant updated;

  @SuppressWarnings("checkstyle:ParameterNumber")
  private Artifact(String id, String filename, String filetype, Long filesize, String checksum,
      String platform, String arch, String signature, String status, String redirectUrl,
      Map<String, Object> metadata, Instant created, Instant updated) {
    this.id = id;
    this.filename = filename;
    this.filetype = filetype;
    this.filesize = filesize;
    this.checksum = checksum;
    this.platform = platform;
    this.arch = arch;
    this.signature = signature;
    this.status = status;
    this.redirectUrl = redirectUrl;
    this.metadata = metadata;
    this.created = created;
    this.updated = updated;
  }

  /**
   * Decodes a single {@code {id, type, attributes}} artifact resource node. Returns {@code null}
   * for a null or absent node.
   */
  public static Artifact fromResourceNode(JsonNode resource) {
    if (resource == null || resource.isNull()) {
      return null;
    }
    JsonNode attrs = resource.path("attributes");
    return new Artifact(
        WireNodes.text(resource, "id"),
        WireNodes.text(attrs, "filename"),
        WireNodes.text(attrs, "filetype"),
        WireNodes.longValue(attrs, "filesize"),
        WireNodes.text(attrs, "checksum"),
        WireNodes.text(attrs, "platform"),
        WireNodes.text(attrs, "arch"),
        WireNodes.text(attrs, "signature"),
        WireNodes.text(attrs, "status"),
        // camelCase, unlike most of this API -- see this class's note.
        WireNodes.text(attrs, "redirectUrl"),
        WireNodes.objectMap(attrs, "metadata"),
        // Renamed individually on top of the camelCase rule, so NOT createdAt/updatedAt.
        WireNodes.instant(attrs, "created"),
        WireNodes.instant(attrs, "updated"));
  }

  /** Returns the artifact's unique id. */
  public String id() {
    return id;
  }

  /** Returns the artifact's filename, such as {@code app-1.2.0-x86_64.dmg}. */
  public String filename() {
    return filename;
  }

  /** Returns the artifact's content type, or {@code null} when the upload declared none. */
  public String filetype() {
    return filetype;
  }

  /**
   * Returns the artifact's size in bytes, or {@code null} when the server has not recorded one.
   *
   * <p>Bytes here, unlike {@code Machine.memory()} and {@code Machine.disk()}, which are megabytes.
   */
  public Long filesize() {
    return filesize;
  }

  /**
   * Returns the checksum of the uploaded bytes, or {@code null} when none was recorded.
   *
   * <p>Worth verifying after a download: the bytes arrive from object storage over a presigned URL
   * this SDK never sees the contents of, so this is the only end-to-end integrity signal the API
   * offers for them.
   */
  public String checksum() {
    return checksum;
  }

  /** Returns the target platform, or {@code null} when the artifact is platform-neutral. */
  public String platform() {
    return platform;
  }

  /** Returns the target architecture, or {@code null} when the artifact is arch-neutral. */
  public String arch() {
    return arch;
  }

  /** Returns the publisher's detached signature over the artifact, or {@code null}. */
  public String signature() {
    return signature;
  }

  /** Returns the artifact status as a raw wire string. */
  public String status() {
    return status;
  }

  /**
   * Returns the short-lived presigned storage URL, or {@code null} when this artifact did not come
   * from a download request.
   *
   * <p><b>Fetch it with no credentials.</b> It points at the object store, not at the API, and the
   * presigned query string is the whole authorisation. Attaching this SDK's licence key or session
   * cookie to that request hands the credential to a third-party host. See
   * {@link sh.tamga.sdk.TamgaClient#requestArtifactDownload(String)}.
   */
  public String redirectUrl() {
    return redirectUrl;
  }

  /** Returns an unmodifiable view of arbitrary artifact metadata, or {@code null}. */
  public Map<String, Object> metadata() {
    return metadata == null ? null : Collections.unmodifiableMap(metadata);
  }

  /** Returns when the artifact was created, or {@code null}. */
  public Instant created() {
    return created;
  }

  /** Returns when the artifact was last updated, or {@code null}. */
  public Instant updated() {
    return updated;
  }
}
