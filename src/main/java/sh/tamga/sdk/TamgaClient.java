package sh.tamga.sdk;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import sh.tamga.sdk.error.TamgaApiException;
import sh.tamga.sdk.error.TamgaMachineOverLimitException;
import sh.tamga.sdk.model.ActivationResult;
import sh.tamga.sdk.model.CheckOutOptions;
import sh.tamga.sdk.model.Component;
import sh.tamga.sdk.model.CreateComponentOptions;
import sh.tamga.sdk.model.CreateMachineOptions;
import sh.tamga.sdk.model.CreateProcessOptions;
import sh.tamga.sdk.model.Entitlement;
import sh.tamga.sdk.model.License;
import sh.tamga.sdk.model.ListOptions;
import sh.tamga.sdk.model.Machine;
import sh.tamga.sdk.model.OfflineProofResult;
import sh.tamga.sdk.model.Page;
import sh.tamga.sdk.model.Process;
import sh.tamga.sdk.model.Scope;
import sh.tamga.sdk.model.ValidateOptions;
import sh.tamga.sdk.model.ValidationMeta;
import sh.tamga.sdk.model.ValidationResult;

/**
 * The Tamga API client: one blocking method per server endpoint.
 *
 * <p>Build one with {@link #builder(String)}, supplying the account id and an
 * {@link AuthTransport}. A client is immutable and thread-safe, and holds a connection pool, so
 * create one per application rather than one per call.
 *
 * <pre>{@code
 * TamgaClient client = TamgaClient.builder("acct-123")
 *     .auth(AuthTransport.licenseKey("LICENSE-KEY"))
 *     .build();
 *
 * ValidationResult result = client.validateByKey("LICENSE-KEY");
 * if (result.meta().code() == ValidationCode.EXPIRED) {
 *   // ...
 * }
 * }</pre>
 *
 * <p>Every method throws {@link TamgaApiException} for a non-2xx response and
 * {@code TamgaTransportException} when no response arrived at all. That distinction matters:
 * a transport failure says nothing about the license, whereas an API error does.
 *
 * <p>HTTP 429 is retried transparently for safe requests -- see {@link Transport}. Machine
 * creation is deliberately excluded, so a rate-limited activation surfaces rather than silently
 * burning a second seat.
 *
 * <p>Offline verification does not go through this class and needs no client at all -- see
 * {@link sh.tamga.sdk.checkout.LicenseFile}, {@link sh.tamga.sdk.checkout.MachineFile} and
 * {@link sh.tamga.sdk.proof.OfflineProof}.
 */
public final class TamgaClient {

  /** The production API host, used unless {@link Builder#host(String)} overrides it. */
  public static final String DEFAULT_HOST = "https://api.tamga.sh";

  /** Default per-request timeout. */
  static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(30);

  /**
   * The page size {@link #hasEntitlement} requests. This is the server's maximum, and it fetches a
   * single page -- see that method's note on the resulting limitation.
   */
  static final int ENTITLEMENT_LOOKUP_PAGE_SIZE = 100;

  private final Transport transport;
  private final EntitlementCache entitlementCache;

  private TamgaClient(Transport transport, EntitlementCache entitlementCache) {
    this.transport = transport;
    this.entitlementCache = entitlementCache;
  }

  /** Starts building a client for the given account id, which is always required. */
  public static Builder builder(String accountId) {
    return new Builder(accountId);
  }

  // ---------------------------------------------------------------- licenses

  /**
   * Validates a license by its raw key.
   *
   * <p>This endpoint takes no scope -- use {@link #validateById} for scoped validation.
   */
  public ValidationResult validateByKey(String key) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("key", key);
    JsonNode root = transport.postJson(Arrays.asList("licenses", "actions", "validate-key"), body);
    return new ValidationResult(License.fromResourceNode(root.get("data")),
        ValidationMeta.fromJson(root.get("meta")));
  }

  /** Validates a license by id, optionally constrained by a {@link Scope}. */
  public ValidationResult validateById(String licenseId, ValidateOptions options) {
    ValidateOptions opts = options == null ? ValidateOptions.defaults() : options;
    Map<String, Object> meta = new LinkedHashMap<>();
    meta.put("skip_touch", opts.skipTouch());
    Scope scope = opts.scope();
    // An unset scope is omitted entirely rather than sent as null: the server treats a present
    // key as a constraint to evaluate.
    if (scope != null && !scope.isEmpty()) {
      meta.put("scope", scope.toRequestMap());
    }
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("meta", meta);
    JsonNode root =
        transport.postJson(Arrays.asList("licenses", licenseId, "actions", "validate"), body);
    return new ValidationResult(License.fromResourceNode(root.get("data")),
        ValidationMeta.fromJson(root.get("meta")));
  }

  /**
   * Validates a license by id without touching it, returning only the verdict.
   *
   * <p>This is the one endpoint whose response is <b>flat</b>: there is no {@code data} envelope
   * and no license resource, just the four validation fields at the top level.
   */
  public ValidationMeta quickValidate(String licenseId) {
    JsonNode root =
        transport.getJson(Arrays.asList("licenses", licenseId, "actions", "validate"), null);
    return ValidationMeta.fromJson(root);
  }

  /**
   * Checks a license in.
   *
   * <p>Gate this on the policy's {@code requireCheckIn} rather than calling it unconditionally and
   * catching {@link TamgaApiException.CheckInNotRequiredException}.
   */
  public License checkIn(String licenseId) {
    JsonNode root =
        transport.postJson(Arrays.asList("licenses", licenseId, "actions", "check-in"), null);
    return License.fromResourceNode(root.get("data"));
  }

  // ---------------------------------------------------------------- checkout

  /**
   * Downloads an offline {@code .lic} certificate and returns its PEM text.
   *
   * <p>Verify the result with {@link sh.tamga.sdk.checkout.LicenseFile}, which needs no network
   * access.
   */
  public String checkOutLicense(String licenseId, CheckOutOptions options) {
    return checkOut(Arrays.asList("licenses", licenseId, "actions", "check-out"), options);
  }

  /**
   * Downloads an offline {@code .machine} certificate and returns its PEM text.
   *
   * <p>Verify the result with {@link sh.tamga.sdk.checkout.MachineFile}, passing the owning
   * license's scheme -- the algorithm comes from the license, never from the file's own
   * {@code alg} field.
   */
  public String checkOutMachine(String machineId, CheckOutOptions options) {
    return checkOut(Arrays.asList("machines", machineId, "actions", "check-out"), options);
  }

  private String checkOut(List<String> segments, CheckOutOptions options) {
    CheckOutOptions opts = options == null ? CheckOutOptions.defaults() : options;
    if (opts.usingPost()) {
      Map<String, Object> meta = new LinkedHashMap<>();
      meta.put("encrypt", opts.encrypt());
      meta.put("ttl", opts.ttl());
      Map<String, Object> body = new LinkedHashMap<>();
      body.put("meta", meta);
      JsonNode root = transport.postJson(segments, body);
      JsonNode certificate = root.path("data").path("attributes").get("certificate");
      return certificate == null || certificate.isNull() ? "" : certificate.asText();
    }
    Map<String, String> query = new LinkedHashMap<>();
    query.put("encrypt", Boolean.toString(opts.encrypt()));
    if (opts.ttl() != null) {
      query.put("ttl", Integer.toString(opts.ttl()));
    }
    return transport.getText(segments, query);
  }

  // ---------------------------------------------------------------- machines

  /**
   * Registers a machine against a license.
   *
   * <p>No policy limit is checked here -- limits surface later, through validation. Prefer
   * {@link #activateMachine} when the desired behaviour is "reject an over-limit activation".
   */
  public Machine createMachine(CreateMachineOptions options) {
    JsonNode root = transport.postJson(Collections.singletonList("machines"),
        options.toRequestBody());
    return Machine.fromResourceNode(root.get("data"));
  }

  /** Deletes a machine, freeing its seat. */
  public void deleteMachine(String machineId) {
    transport.deleteNoContent(Arrays.asList("machines", machineId));
  }

  /**
   * Registers a machine and validates the license in one step, rolling the machine back if the
   * license turns out to be over a policy limit.
   *
   * <p>This is a composite, not a single endpoint: create, then validate, then delete on an
   * over-limit verdict. Machine creation itself enforces nothing, so without the rollback an
   * over-limit activation would leave a row behind that still consumes a seat.
   *
   * <p><b>Divergence from tamga-go, deliberate:</b> Go rolls back only on an over-limit code and
   * hands a failed validate's error back alongside the created machine. This method also rolls
   * back when the validate call itself fails, because throwing leaves no way to return the machine
   * -- propagating without deleting would strand a seat whose id the caller never received. See
   * the divergence register in {@code docs/api-client-contract.md}.
   *
   * @throws TamgaMachineOverLimitException if validation reported an over-limit code. The machine
   *     has already been deleted by the time this is thrown; the exception carries the validation
   *     meta so the caller can tell which limit was exceeded.
   */
  public ActivationResult activateMachine(CreateMachineOptions options, Scope scope) {
    Machine machine = createMachine(options);
    ValidationResult validation;
    try {
      validation = validateById(options.licenseId(), ValidateOptions.defaults().withScope(scope));
    } catch (RuntimeException e) {
      // Validation failed outright, so we cannot tell whether the machine is permitted. Roll it
      // back anyway rather than leaking a seat, and let the original failure propagate.
      deleteQuietly(machine);
      throw e;
    }

    ValidationMeta meta = validation.meta();
    if (meta != null && meta.code() != null && meta.code().overLimit()) {
      deleteQuietly(machine);
      throw new TamgaMachineOverLimitException(meta);
    }
    return new ActivationResult(machine, meta);
  }

  /**
   * Deletes a machine during activation rollback, ignoring a failure to do so.
   *
   * <p>The caller is already throwing; a rollback failure must not mask the original cause. The
   * worst case is an orphaned machine row, which the operator can see and remove, whereas a
   * swallowed root cause leaves nothing to act on.
   */
  private void deleteQuietly(Machine machine) {
    if (machine == null || machine.id() == null) {
      return;
    }
    try {
      deleteMachine(machine.id());
    } catch (RuntimeException ignored) {
      // Intentionally ignored -- see this method's Javadoc.
    }
  }

  /**
   * Sends a heartbeat ping for a machine.
   *
   * <p>The server's heartbeat window is a hardcoded 600 seconds regardless of the policy's
   * {@code heartbeat_duration}. Use {@link HeartbeatScheduler} rather than driving this by hand.
   */
  public Machine pingHeartbeat(String machineId) {
    JsonNode root = transport.postJson(
        Arrays.asList("machines", machineId, "actions", "ping-heartbeat"), null);
    return Machine.fromResourceNode(root.get("data"));
  }

  /** Resets a machine's heartbeat, returning it to the not-started state. */
  public Machine resetHeartbeat(String machineId) {
    JsonNode root = transport.postJson(
        Arrays.asList("machines", machineId, "actions", "reset-heartbeat"), null);
    return Machine.fromResourceNode(root.get("data"));
  }

  /**
   * Generates a signed offline proof for a machine over the supplied dataset.
   *
   * <p>Verify it later with {@code sh.tamga.sdk.proof.OfflineProof} against the same dataset. The
   * signature covers a canonical, recursively key-sorted rendering, so the dataset must round-trip
   * byte-identically.
   */
  public OfflineProofResult generateOfflineProof(String machineId, Map<String, Object> dataset) {
    Map<String, Object> meta = new LinkedHashMap<>();
    meta.put("dataset", dataset == null ? new LinkedHashMap<String, Object>() : dataset);
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("meta", meta);
    JsonNode root = transport.postJson(
        Arrays.asList("machines", machineId, "actions", "generate-offline-proof"), body);
    JsonNode proof = root.path("meta").get("proof");
    return new OfflineProofResult(Machine.fromResourceNode(root.get("data")),
        proof == null || proof.isNull() ? null : proof.asText());
  }

  // ------------------------------------------------- components and processes

  /** Registers a component against a machine. */
  public Component createComponent(CreateComponentOptions options) {
    JsonNode root = transport.postJson(Collections.singletonList("components"),
        options.toRequestBody());
    return Component.fromResourceNode(root.get("data"));
  }

  /** Lists a machine's components, one keyset-paginated page at a time. */
  public Page<Component> listComponents(String machineId, ListOptions options) {
    ListOptions opts = options == null ? ListOptions.defaults() : options;
    JsonNode root = transport.getJson(Arrays.asList("machines", machineId, "components"),
        pageQuery(opts));
    List<Component> items = new ArrayList<>();
    for (JsonNode node : root.path("data")) {
      items.add(Component.fromResourceNode(node));
    }
    return new Page<>(synthesizeCursor(items.size(), opts, lastId(root)), items);
  }

  /** Registers a running process against a machine. */
  public Process createProcess(CreateProcessOptions options) {
    JsonNode root = transport.postJson(Collections.singletonList("processes"),
        options.toRequestBody());
    return Process.fromResourceNode(root.get("data"));
  }

  /**
   * Sends a heartbeat ping for a process.
   *
   * <p>The process window is a hardcoded 30 seconds with no resurrection grace: a dead process row
   * is deleted outright. Use {@link ProcessHeartbeatScheduler} rather than driving this by hand.
   */
  public Process pingProcess(String processId) {
    JsonNode root =
        transport.postJson(Arrays.asList("processes", processId, "actions", "ping"), null);
    return Process.fromResourceNode(root.get("data"));
  }

  // ------------------------------------------------------------ entitlements

  /** Lists a license's entitlements, one keyset-paginated page at a time. */
  public Page<Entitlement> listEntitlements(String licenseId, ListOptions options) {
    ListOptions opts = options == null ? ListOptions.defaults() : options;
    JsonNode root = transport.getJson(Arrays.asList("licenses", licenseId, "entitlements"),
        pageQuery(opts));
    List<Entitlement> items = new ArrayList<>();
    for (JsonNode node : root.path("data")) {
      items.add(Entitlement.fromResourceNode(node));
    }
    return new Page<>(synthesizeCursor(items.size(), opts, lastId(root)), items);
  }

  /** Fetches a single entitlement of a license by id. */
  public Entitlement getEntitlement(String licenseId, String entitlementId) {
    JsonNode root = transport.getJson(
        Arrays.asList("licenses", licenseId, "entitlements", entitlementId), null);
    return Entitlement.fromResourceNode(root.get("data"));
  }

  /**
   * Reports whether a license carries an entitlement with the given code, caching the result for
   * 60 seconds.
   *
   * <p>Matching is on {@code code}, the stable developer-facing identifier. Never match on
   * {@code name}, which is a display label that may collide or change.
   *
   * <p><b>Known limitation:</b> this fetches a single page of
   * {@value #ENTITLEMENT_LOOKUP_PAGE_SIZE} entitlements, the server's maximum. A license carrying
   * more than that is silently truncated here; paginate {@link #listEntitlements} directly if that
   * is a possibility.
   */
  public boolean hasEntitlement(String licenseId, String code) {
    Set<String> cached = entitlementCache.fresh(licenseId);
    if (cached == null) {
      Page<Entitlement> page =
          listEntitlements(licenseId, ListOptions.ofLimit(ENTITLEMENT_LOOKUP_PAGE_SIZE));
      Set<String> codes = new HashSet<>();
      for (Entitlement entitlement : page.items()) {
        if (entitlement != null && entitlement.code() != null) {
          codes.add(entitlement.code());
        }
      }
      entitlementCache.put(licenseId, codes);
      cached = codes;
    }
    return cached.contains(code);
  }

  /** Drops the cached entitlement set for a license, forcing the next lookup to refetch. */
  public void invalidateEntitlementCache(String licenseId) {
    entitlementCache.invalidate(licenseId);
  }

  // ----------------------------------------------------------------- helpers

  private static Map<String, String> pageQuery(ListOptions opts) {
    Map<String, String> query = new LinkedHashMap<>();
    if (opts.pageSize() > 0) {
      query.put("limit", Integer.toString(opts.pageSize()));
    }
    if (opts.afterCursor() != null) {
      query.put("page[after]", opts.afterCursor());
    }
    return query;
  }

  /**
   * Derives the next cursor for a page.
   *
   * <p>These endpoints return no cursor metadata or links, so the cursor is synthesized: the last
   * item's id, and only when the page came back full. A short or empty page means there is nothing
   * further to fetch, so the cursor is null.
   */
  private static String synthesizeCursor(int returned, ListOptions opts, String lastId) {
    if (opts.pageSize() <= 0 || returned < opts.pageSize()) {
      return null;
    }
    return lastId;
  }

  private static String lastId(JsonNode root) {
    JsonNode data = root.path("data");
    if (!data.isArray() || data.size() == 0) {
      return null;
    }
    JsonNode id = data.get(data.size() - 1).get("id");
    return id == null || id.isNull() ? null : id.asText();
  }

  /** Builds a {@link TamgaClient}. The account id and an {@link AuthTransport} are required. */
  public static final class Builder {

    private final String accountId;
    private String host = DEFAULT_HOST;
    private String apiVersion = Transport.DEFAULT_API_VERSION;
    private String otp;
    private AuthTransport auth;
    private int maxRetries = Transport.DEFAULT_MAX_RETRIES;
    private Duration timeout = DEFAULT_TIMEOUT;
    private OkHttpClient httpClient;
    private Random jitter;
    private long maxResponseBytes = Transport.MAX_RESPONSE_BYTES;

    private Builder(String accountId) {
      this.accountId = accountId;
    }

    /**
     * Overrides the API host. Accepts a bare host or a full URL; a trailing slash is trimmed and an
     * explicit {@code http://} scheme is preserved rather than upgraded, so a local mock server
     * works without a test-only code path.
     */
    public Builder host(String value) {
      this.host = value;
      return this;
    }

    /** Sets the authentication transport. Required. */
    public Builder auth(AuthTransport value) {
      this.auth = value;
      return this;
    }

    /**
     * Overrides the {@code Tamga-Version} header. Defaults to the version this SDK release was
     * built against -- override it only deliberately.
     */
    public Builder apiVersion(String value) {
      this.apiVersion = value;
      return this;
    }

    /** Sets a TOTP code, sent as {@code Tamga-OTP} on every request. */
    public Builder otp(String value) {
      this.otp = value;
      return this;
    }

    /** Sets how many times a rate-limited request is retried. Zero disables retrying. */
    public Builder maxRetries(int value) {
      this.maxRetries = Math.max(0, value);
      return this;
    }

    /** Overrides the per-request timeout. A null or non-positive value keeps the default. */
    public Builder timeout(Duration value) {
      this.timeout = value == null || value.isNegative() || value.isZero()
          ? DEFAULT_TIMEOUT : value;
      return this;
    }

    /** Supplies a preconfigured HTTP client, for callers that need proxies or custom TLS. */
    public Builder httpClient(OkHttpClient value) {
      this.httpClient = value;
      return this;
    }

    /** Injects a jitter source so retry backoff is deterministic under test. */
    Builder jitter(Random value) {
      this.jitter = value;
      return this;
    }

    /** Lowers the response-body ceiling so tests can exercise it without allocating megabytes. */
    Builder maxResponseBytes(long value) {
      this.maxResponseBytes = value;
      return this;
    }

    /**
     * Builds the client.
     *
     * @throws IllegalStateException if the account id or auth transport is missing, or the host is
     *     not a usable URL. The account segment is required in every server mode, so there is no
     *     valid client without one.
     */
    public TamgaClient build() {
      if (accountId == null || accountId.isEmpty()) {
        throw new IllegalStateException("accountId is required.");
      }
      if (auth == null) {
        throw new IllegalStateException(
            "An AuthTransport is required -- see AuthTransport.licenseKey(String).");
      }
      HttpUrl parsed = HttpUrl.parse(normalizeHost(host));
      if (parsed == null) {
        throw new IllegalStateException("host is not a valid URL: " + host);
      }
      OkHttpClient client = httpClient != null ? httpClient
          : new OkHttpClient.Builder()
              .callTimeout(timeout.toMillis(), TimeUnit.MILLISECONDS)
              // SECURITY: redirects are not followed.
              //
              // This client only ever calls a small fixed set of paths under one configured host,
              // so a 3xx is never a legitimate response -- but following one is actively unsafe.
              // OkHttp strips the Authorization header on a cross-origin redirect, and it does
              // NOT do the same for a Cookie header set directly on the request, which is exactly
              // how AuthTransport.sessionCookie sends its credential. Confirmed against okhttp
              // 5.4.0 with a two-server probe: the session cookie was replayed verbatim to the
              // redirect target while Authorization was correctly withheld. An open redirect on
              // the API host, or an injected 3xx on a plaintext connection, would hand a session
              // id to whatever host the Location header names.
              //
              // A caller supplying their own OkHttpClient opts out of this and owns the decision.
              .followRedirects(false)
              .followSslRedirects(false)
              .build();
      Transport transport = new Transport(client, parsed, accountId, apiVersion, otp,
          userAgent(), auth, maxRetries, jitter, maxResponseBytes);
      return new TamgaClient(transport, new EntitlementCache(System::currentTimeMillis));
    }

    private static String normalizeHost(String host) {
      String trimmed = host == null ? "" : host.trim();
      while (trimmed.endsWith("/")) {
        trimmed = trimmed.substring(0, trimmed.length() - 1);
      }
      if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
        return trimmed;
      }
      return "https://" + trimmed;
    }

    private static String userAgent() {
      String version = TamgaClient.class.getPackage().getImplementationVersion();
      return "tamga-java/" + (version == null ? "dev" : version);
    }
  }
}
