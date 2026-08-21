package sh.tamga.sdk;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.time.Instant;
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
import sh.tamga.sdk.error.TamgaActivationValidationException;
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
import sh.tamga.sdk.model.ValidationCode;
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
 * <p><b>Every endpoint is authenticated server-side</b>, and the default license-key transport
 * additionally requires the license's policy to permit license-key authentication -- see
 * {@link AuthTransport}. A policy left at its default answers {@code 401 LICENSE_NOT_ALLOWED} to
 * every call here.
 *
 * <p>Offline verification does not go through this class and needs no client at all -- see
 * {@link sh.tamga.sdk.checkout.LicenseFile}, {@link sh.tamga.sdk.checkout.MachineFile} and
 * {@link sh.tamga.sdk.proof.OfflineProof}.
 */
public final class TamgaClient {

  /** The production API host, used unless {@link Builder#host(String)} overrides it. */
  public static final String DEFAULT_HOST = "https://api.tamga.sh";

  /**
   * Default per-request timeout.
   *
   * <p>Deliberately longer than the server's own 30-second request timeout. Matching it exactly
   * makes the two race on any slow request, and the local timeout usually wins -- which throws
   * away the server's {@code 504} and, with it, the {@code X-Request-Id} that is the only handle
   * support has on a slow request.
   */
  static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(45);

  /**
   * The page size {@link #hasEntitlement} requests. This is the server's maximum, and it fetches a
   * single page -- see that method's note on the resulting limitation.
   */
  static final int ENTITLEMENT_LOOKUP_PAGE_SIZE = 100;

  /**
   * The {@code limit} sent when the caller does not choose one.
   *
   * <p>Not left to the server. Its own default is 25, these listings carry no {@code meta.page}
   * and no {@code links}, and the only end-of-list signal is a page shorter than a limit the
   * client already knows -- so accepting the server default silently truncated at 25 rows with no
   * cursor to continue from. Sending the server maximum explicitly makes the page-full test
   * meaningful again.
   */
  static final int DEFAULT_PAGE_SIZE = 100;

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
    // key as a constraint to evaluate. The emptiness test is on the rendered map, not on
    // Scope.isEmpty(): a scope carrying only the two unsendable fields (version, checksum) renders
    // to nothing, and sending "scope": {} for it would be noise.
    Map<String, Object> scopeMap = scope == null ? null : scope.toRequestMap();
    if (scopeMap != null && !scopeMap.isEmpty()) {
      meta.put("scope", scopeMap);
    }
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("meta", meta);
    JsonNode root =
        transport.postJson(Arrays.asList("licenses", licenseId, "actions", "validate"), body);
    return new ValidationResult(License.fromResourceNode(root.get("data")),
        ValidationMeta.fromJson(root.get("meta")));
  }

  /**
   * Validates a license by id, returning only the verdict.
   *
   * <p>This is the one endpoint whose response is <b>flat</b>: there is no {@code data} envelope
   * and no license resource, just the four validation fields at the top level.
   *
   * <p><b>This does touch the license.</b> It writes {@code last_validated_at} -- unless the
   * request carries an {@code Origin} header, in which case the server skips the write entirely.
   * The response is byte-identical either way, so a caller cannot tell which happened. That
   * matters because a license with no machines and no {@code last_validated_at} reports
   * {@code INACTIVE}, and the check-in-overdue worker measures from the same column: behind a
   * proxy that adds {@code Origin}, this endpoint can never move either. This SDK never sets
   * {@code Origin} itself. For a genuinely side-effect-free check use
   * {@link #validateById} with {@link ValidateOptions#withSkipTouch}, which is honoured
   * unconditionally.
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
   * <p><b>Policy limits are checked here</b>, through the policy's overage strategy: a strict
   * policy rejects with {@code 422} and one of
   * {@link TamgaApiException.MachineLimitExceededException},
   * {@link TamgaApiException.CoreLimitExceededException},
   * {@link TamgaApiException.MemoryLimitExceededException} or
   * {@link TamgaApiException.DiskLimitExceededException}, while a permissive one
   * ({@code ALLOW_ACCESS}, {@code ALLOW_1_25X_OVERAGE}) creates the row and leaves the limit to
   * surface at validation. Uniqueness is checked before all of them, so re-sending a fingerprint
   * that is already activated answers {@code 409 FINGERPRINT_TAKEN} rather than a limit error.
   *
   * <p>Prefer {@link #activateMachine}, which reports both limit paths as one outcome.
   *
   * <p>{@code memory} and {@code disk} on {@link CreateMachineOptions} are <b>megabytes</b>.
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
   * Registers a machine and validates the license in one step, reporting an over-limit license as
   * one outcome however the server chose to report it.
   *
   * <p>This is a composite, not a single endpoint: create, then validate, then delete on an
   * over-limit verdict. <b>Both halves of that are load-bearing</b>, because the server enforces
   * limits twice and which one fires depends on the policy:
   *
   * <ul>
   *   <li>Creation runs the machine/core/memory/disk checks through the policy's overage strategy.
   *       A strict policy rejects the create with {@code 422 MACHINE_LIMIT_EXCEEDED} and friends;
   *       nothing was created, so nothing is rolled back.
   *   <li>Under a permissive strategy ({@code ALLOW_ACCESS}, {@code ALLOW_1_25X_OVERAGE}) that
   *       same check passes and the limit appears only in the validate verdict. The machine row
   *       exists at that point and is deleted, or it would go on consuming a seat.
   * </ul>
   *
   * <p>Either way the caller gets {@link TamgaMachineOverLimitException} carrying a validation
   * code, so the two vocabularies never reach product code. {@code rolledBack()} on the exception
   * says which path ran.
   *
   * <p>If the validation call itself fails, the machine is <b>not</b> deleted: a network blip is
   * not a verdict about the license, and deleting on one destroys a seat the license may well be
   * entitled to. It is handed back on {@link TamgaActivationValidationException} so the caller can
   * retry validation or delete it. This matches {@code tamga-go}.
   *
   * @throws TamgaMachineOverLimitException if the license is over a policy limit, whether the
   *     server said so at creation or at validation. No machine row survives in either case; the
   *     exception carries the validation meta so the caller can tell which limit was exceeded.
   * @throws TamgaActivationValidationException if the validation call itself failed. The machine
   *     still exists and is carried on the exception.
   */
  public ActivationResult activateMachine(CreateMachineOptions options, Scope scope) {
    Machine machine;
    try {
      machine = createMachine(options);
    } catch (TamgaApiException e) {
      // A create-time limit rejection is the same product event as an over-limit validate verdict,
      // so it is translated rather than passed through -- otherwise the caller has to handle two
      // sets of names for one condition, and which one they see depends on a policy setting they
      // do not control. Anything else (FINGERPRINT_TAKEN, auth, transport) is not a limit and is
      // rethrown untouched.
      ValidationCode limit = ValidationCode.fromMachineLimitErrorCode(e.code());
      if (limit == null) {
        throw e;
      }
      throw new TamgaMachineOverLimitException(
          ValidationMeta.of(Instant.now(), false, e.error() == null ? null : e.error().detail(),
              limit),
          false, e);
    }
    ValidationResult validation;
    try {
      validation = validateById(options.licenseId(), ValidateOptions.defaults().withScope(scope));
    } catch (RuntimeException e) {
      // Deliberately NOT rolled back. Whether the machine is permitted is unknown, and a transient
      // failure is not grounds to destroy a seat the license may be entitled to. The machine goes
      // back to the caller on the exception, which is what makes not deleting it safe.
      throw new TamgaActivationValidationException(machine, e);
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

  /**
   * Resets a machine's heartbeat, returning it to the not-started state.
   *
   * <p><b>Always {@code 403} for a license-key credential.</b> The server gates this on role, not
   * on permission: only an admin, developer, product or environment token may call it, and
   * {@link AuthTransport#licenseKey} is none of those. Worth knowing because this is the only
   * server-side way to unstick a machine whose heartbeat job is wedged -- an embedded client
   * cannot perform that recovery itself and should surface it as an operator task.
   */
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
   *
   * <p><b>Always {@code 403} for a license-key credential</b>, the same role gate as
   * {@link #resetHeartbeat} -- and it holds even though that credential carries the
   * {@code machine.proofs.generate} permission. Proofs have to be minted by a back-office
   * credential and shipped to the client.
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

  /**
   * Lists a license's entitlements: direct and policy-inherited, in one unpaginated response.
   *
   * <p><b>This route does not paginate.</b> Its listing is a union of two tables, so a single
   * keyset cursor cannot describe it and the server accepts {@code page[after]} only for wire
   * compatibility -- it is read into a field it never uses. This SDK therefore never sends the
   * parameter (a cursor that is not a UUID would be rejected outright by the server's query
   * decoding) and {@link Page#nextCursor()} is always {@code null} here. {@code limit} still
   * works and is capped at 100.
   *
   * <p><b>Consequence:</b> a license with more than 100 effective entitlements cannot be
   * enumerated completely through this endpoint at all. A short result is not proof that no more
   * exist -- 100 items back means the list was truncated with no way to continue.
   *
   * <p>{@link Entitlement#inherited()} distinguishes the two sources.
   */
  public Page<Entitlement> listEntitlements(String licenseId, ListOptions options) {
    ListOptions opts = options == null ? ListOptions.defaults() : options;
    Map<String, String> query = new LinkedHashMap<>();
    query.put("limit", Integer.toString(effectivePageSize(opts)));
    JsonNode root = transport.getJson(Arrays.asList("licenses", licenseId, "entitlements"), query);
    List<Entitlement> items = new ArrayList<>();
    for (JsonNode node : root.path("data")) {
      items.add(Entitlement.fromResourceNode(node));
    }
    // Unconditionally null: there is nothing to continue from, and synthesizing a cursor here
    // would invite a loop that refetches the same first page forever.
    return new Page<>(null, items);
  }

  /**
   * Fetches a single <b>directly attached</b> entitlement of a license by id.
   *
   * <p>This route joins only the license's own attachments, so an entitlement that
   * {@link #listEntitlements} returned with {@link Entitlement#inherited()} {@code true} answers
   * {@code 404} here. List-then-get-each is not a valid pattern on this resource; take the
   * resources the listing already returned.
   */
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
   * <p><b>Known limitation:</b> this fetches {@value #ENTITLEMENT_LOOKUP_PAGE_SIZE} entitlements,
   * the server's maximum, and that endpoint does not paginate -- so a license carrying more than
   * that is truncated here with no way to read the rest. A {@code true} result is always
   * authoritative; a {@code false} one is authoritative only for a license below that ceiling.
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

  /**
   * Returns the page size to request: the caller's, or {@link #DEFAULT_PAGE_SIZE} when they did not
   * choose one.
   *
   * <p>Never zero, and never left to the server. Cursor synthesis below compares the row count to
   * the requested limit, which is only possible when the limit is one this client picked.
   */
  private static int effectivePageSize(ListOptions opts) {
    return opts.pageSize() > 0 ? opts.pageSize() : DEFAULT_PAGE_SIZE;
  }

  private static Map<String, String> pageQuery(ListOptions opts) {
    Map<String, String> query = new LinkedHashMap<>();
    query.put("limit", Integer.toString(effectivePageSize(opts)));
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
    return returned < effectivePageSize(opts) ? null : lastId;
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
