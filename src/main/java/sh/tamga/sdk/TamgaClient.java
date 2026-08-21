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
import sh.tamga.sdk.error.TamgaTransportException;
import sh.tamga.sdk.model.ActivationOptions;
import sh.tamga.sdk.model.ActivationResult;
import sh.tamga.sdk.model.CheckOutOptions;
import sh.tamga.sdk.model.Component;
import sh.tamga.sdk.model.CreateComponentOptions;
import sh.tamga.sdk.model.CreateMachineOptions;
import sh.tamga.sdk.model.CreateProcessOptions;
import sh.tamga.sdk.model.Entitlement;
import sh.tamga.sdk.model.HealthStatus;
import sh.tamga.sdk.model.HeartbeatStatus;
import sh.tamga.sdk.model.License;
import sh.tamga.sdk.model.ListOptions;
import sh.tamga.sdk.model.Machine;
import sh.tamga.sdk.model.MachineListOptions;
import sh.tamga.sdk.model.OfflineProofResult;
import sh.tamga.sdk.model.OffsetPage;
import sh.tamga.sdk.model.Page;
import sh.tamga.sdk.model.Policy;
import sh.tamga.sdk.model.Process;
import sh.tamga.sdk.model.Release;
import sh.tamga.sdk.model.Scope;
import sh.tamga.sdk.model.UpdateMachineOptions;
import sh.tamga.sdk.model.UpgradeCheckOptions;
import sh.tamga.sdk.model.UpgradeCheckResult;
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

  /**
   * Fetches a license by id.
   *
   * <p>Read-only: unlike {@link #quickValidate}, this touches nothing and returns no verdict. Use
   * it to read the stored fields -- {@code expiry}, {@code status}, {@code machinesCount},
   * {@code maxMachines} -- without asking the server to judge them.
   *
   * <p><b>Do not treat what comes back as scoped to the caller.</b> This route authorises on the
   * {@code license.read} permission alone and never checks that a license-key credential is asking
   * about its own license, so a key that can call this at all can read <em>every</em> license in
   * the account, including each one's {@code key} attribute in plain text. That is a server-side
   * gap, reported upstream; no client can close it. It is documented here so nobody builds a
   * "read your own license" feature on this route and describes it as isolated -- it is not.
   */
  public License getLicense(String licenseId) {
    JsonNode root = transport.getJson(Arrays.asList("licenses", licenseId), null);
    return License.fromResourceNode(root.get("data"));
  }

  // ---------------------------------------------------------------- policies

  /**
   * Fetches the policy a license runs under.
   *
   * <p><b>This, not {@link #getPolicy}, is the policy read an embedded client can perform.</b> The
   * route is authorised as a license read ({@code license.read}), which the license-key credential
   * this SDK defaults to does hold. {@link #getPolicy} wants {@code policy.read}, which it does
   * not.
   *
   * <p>The main reason to call it is {@link Policy#effectiveHeartbeatWindow()}: it is the only
   * dependable way to learn the heartbeat window a machine will be measured against, and
   * {@link HeartbeatScheduler.Builder#policy(Policy)} sizes the ping interval directly from it.
   *
   * <p>{@code max_memory} and {@code max_disk} are absent from every policy response the server
   * serialises, so {@link Policy} does not model them -- those two limits are observable only
   * after the fact, as {@link ValidationCode#TOO_MUCH_MEMORY} or
   * {@link ValidationCode#TOO_MUCH_DISK} from a validation.
   */
  public Policy getLicensePolicy(String licenseId) {
    JsonNode root = transport.getJson(Arrays.asList("licenses", licenseId, "policy"), null);
    return Policy.fromResourceNode(root.get("data"));
  }

  /**
   * Fetches a policy by id.
   *
   * <p><b>A license-key credential cannot call this.</b> The route requires the {@code policy.read}
   * permission, which is not in a license token's set, so
   * {@link AuthTransport#licenseKey(String)} answers {@code 403}
   * ({@link TamgaApiException.ForbiddenException}) here however well-formed the request is. It is
   * exposed for callers holding an admin, developer, product or environment token. Anything
   * running under a license key wants {@link #getLicensePolicy} instead, which reaches the same
   * resource through a route it is allowed to use.
   */
  public Policy getPolicy(String policyId) {
    JsonNode root = transport.getJson(Arrays.asList("policies", policyId), null);
    return Policy.fromResourceNode(root.get("data"));
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
   * Fetches a machine by id.
   *
   * <p><b>This is a read, and that is what makes it different from a ping.</b> The server resolves
   * it through a query that joins {@code policies}, so two fields carry their true values here and
   * only here among the routes an embedded client calls on a timer:
   *
   * <ul>
   *   <li>{@link Machine#heartbeatStatus()} can genuinely report {@link HeartbeatStatus#DEAD}. A
   *       ping cannot: it writes {@code last_heartbeat_at = NOW()} and then derives the status
   *       from that same timestamp, so it always answers {@code ALIVE} or {@code RESURRECTED}.
   *       This route measures against a timestamp nothing just reset.
   *   <li>{@link Machine#nextHeartbeatAt()} is computed against the policy's real heartbeat window
   *       rather than the 600-second fallback the bare-write routes report.
   * </ul>
   *
   * <p>A {@code DEAD} reading still does not mean the row was culled -- the status is derived from
   * the timestamp alone and never consults {@code require_heartbeat}, which defaults to false and
   * is what the cull job requires. Keep pinging; the only row-is-gone signal is a {@code 404} from
   * the ping itself.
   *
   * <p><b>Not scoped to the caller's own license.</b> No machine route applies a license-scope
   * check, so a credential holding {@code machine.read} reads any machine in the account. The
   * resource carries no license id either, so a machine read this way cannot be attributed to a
   * license from its own fields.
   */
  public Machine getMachine(String machineId) {
    JsonNode root = transport.getJson(Arrays.asList("machines", machineId), null);
    return Machine.fromResourceNode(root.get("data"));
  }

  /**
   * Lists the account's machines, one <b>offset</b>-paginated page at a time.
   *
   * <p>The one listing in this SDK that pages this way. It takes {@code page[number]} and
   * {@code page[size]} and answers with a {@code meta.page} block, so
   * {@link OffsetPage#hasNextPage()} is the server's own answer rather than the "was the page
   * full?" guess the keyset listings force. {@link #listComponents} and
   * {@link #listMachineProcesses} are keyset and return {@link Page}; do not interchange them.
   *
   * <p><b>The filters do not include a fingerprint.</b> {@code filter[q]} is a substring match
   * across name, hostname and fingerprint, which narrows a search but never identifies one machine
   * -- use {@link #findMachineByFingerprint} when an exact match is what is wanted.
   */
  public OffsetPage<Machine> listMachines(MachineListOptions options) {
    MachineListOptions opts = options == null ? MachineListOptions.defaults() : options;
    int size = opts.pageSize() > 0 ? opts.pageSize() : DEFAULT_PAGE_SIZE;
    JsonNode root =
        transport.getJson(Collections.singletonList("machines"), opts.toQuery(size));
    List<Machine> items = new ArrayList<>();
    for (JsonNode node : root.path("data")) {
      items.add(Machine.fromResourceNode(node));
    }
    return OffsetPage.fromMetaNode(items, root.get("meta"));
  }

  /**
   * Finds the machine registered under an exact fingerprint, or {@code null} if there is none.
   *
   * <p>Composed rather than an endpoint, because the server has no fingerprint filter. It sends
   * the fingerprint as {@code filter[q]} -- a case-insensitive substring match over name, hostname
   * and fingerprint -- narrowed by {@code filter[license]} when a license id is supplied, and then
   * <b>compares {@link Machine#fingerprint()} exactly</b> on the rows that come back. That second
   * step is not optional: {@code filter[q]} would also match a machine whose <em>hostname</em>
   * merely contains the fingerprint, and a substring hit is not the same machine.
   *
   * <p>Reads one page at the server's maximum size. A fingerprint is unique within its policy's
   * uniqueness scope, so a matching row is on the first page unless more than 100 other machines
   * happen to contain the same substring -- in which case this reports {@code null} rather than
   * paging on, and a caller who needs certainty there should walk {@link #listMachines} itself.
   *
   * @param fingerprint the exact fingerprint to look for; {@code null} or empty returns
   *     {@code null} without a request
   * @param licenseId narrows the search to one license, or {@code null} to search the account
   * @return the matching machine, or {@code null} when no row carries that exact fingerprint
   */
  public Machine findMachineByFingerprint(String fingerprint, String licenseId) {
    if (fingerprint == null || fingerprint.isEmpty()) {
      return null;
    }
    MachineListOptions options = MachineListOptions.defaults()
        .search(fingerprint)
        .size(DEFAULT_PAGE_SIZE);
    if (licenseId != null && !licenseId.isEmpty()) {
      options = options.licenseId(licenseId);
    }
    for (Machine machine : listMachines(options).items()) {
      if (machine != null && fingerprint.equals(machine.fingerprint())) {
        return machine;
      }
    }
    return null;
  }

  /**
   * Updates a machine's mutable attributes.
   *
   * <p><b>Absent means "leave alone", so this cannot clear a field.</b> The server merges with
   * {@code COALESCE}, so a field the request omits keeps its stored value -- and so does a field
   * sent explicitly as {@code null}. {@link UpdateMachineOptions} omits anything the caller did not
   * set, which makes the partial update work but leaves no way to null a column back out through
   * this route.
   *
   * <p>{@code fingerprint} is not updatable, deliberately: it is the machine's identity and the
   * thing uniqueness is enforced on. Neither are the license, policy, owner or group relationships.
   *
   * <p>{@code memory} and {@code disk} are <b>megabytes</b>, exactly as on
   * {@link CreateMachineOptions}.
   *
   * <p><b>This is a write whose response can still report {@link HeartbeatStatus#DEAD}</b>, which
   * makes it the exception to the otherwise reliable rule that a write never can. The rule holds
   * because a write normally sets {@code last_heartbeat_at} and then derives the status from the
   * timestamp it just wrote; this update touches neither, so the status is measured against a
   * clock nothing reset. Its {@code UPDATE ... RETURNING} also does not join {@code policies}, so
   * the {@link Machine#nextHeartbeatAt()} that comes back is computed against the 600-second
   * fallback rather than the policy window -- treat a machine from this route as unusable for
   * sizing a heartbeat interval.
   *
   * <p><b>Not scoped to the caller's own license.</b> The server authorises this on the
   * {@code machine.update} permission alone and applies no license-scope check to any machine
   * route, so a credential that can call this can update any machine in the account. That is a
   * server-side gap, reported upstream; do not build on the assumption that it is confined.
   */
  public Machine updateMachine(String machineId, UpdateMachineOptions options) {
    UpdateMachineOptions opts = options == null ? UpdateMachineOptions.none() : options;
    JsonNode root =
        transport.patchJson(Arrays.asList("machines", machineId), opts.toRequestBody());
    return Machine.fromResourceNode(root.get("data"));
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
    return activateMachine(options, scope, ActivationOptions.defaults());
  }

  /**
   * Registers a machine and validates the license, with control over what happens when the
   * fingerprint is already taken.
   *
   * <p>Identical to {@link #activateMachine(CreateMachineOptions, Scope)} when passed
   * {@link ActivationOptions#defaults()}. With
   * {@link ActivationOptions#reuseTakenFingerprint(boolean) reuseTakenFingerprint(true)} a
   * {@code 409 FINGERPRINT_TAKEN} stops being a dead end: the machine already registered under
   * that fingerprint on this license is fetched and validated in place of a newly created one,
   * which makes re-activation idempotent.
   *
   * <p>That conflict is not an edge case. A fingerprint is stable by design, so an application
   * that activates on every launch gets a 409 on every launch after the first -- the server treats
   * re-registration as a conflict deliberately, and without this the caller is left holding an
   * error where it wanted a machine id.
   *
   * <p><b>A reused machine is never rolled back.</b> If validation reports an over-limit verdict
   * the pre-existing row stays: it predates this call and its seat is not this activation's to
   * release. {@link TamgaMachineOverLimitException#rolledBack()} is {@code false} in that case, and
   * unlike the create-time refusal a machine row does still exist -- see that method.
   *
   * @param options the machine to register
   * @param scope the validation scope, or {@code null} for none
   * @param activationOptions how to handle an already-registered fingerprint; {@code null} means
   *     {@link ActivationOptions#defaults()}
   * @throws TamgaMachineOverLimitException if the license is over a policy limit
   * @throws TamgaActivationValidationException if the validation call itself failed
   */
  public ActivationResult activateMachine(CreateMachineOptions options, Scope scope,
      ActivationOptions activationOptions) {
    ActivationOptions activationOpts =
        activationOptions == null ? ActivationOptions.defaults() : activationOptions;
    Machine machine;
    boolean reused = false;
    try {
      machine = createMachine(options);
    } catch (TamgaApiException e) {
      // A create-time limit rejection is the same product event as an over-limit validate verdict,
      // so it is translated rather than passed through -- otherwise the caller has to handle two
      // sets of names for one condition, and which one they see depends on a policy setting they
      // do not control. Anything else (auth, transport) is not a limit and is rethrown untouched.
      ValidationCode limit = ValidationCode.fromMachineLimitErrorCode(e.code());
      if (limit == null) {
        Machine existing = recoverTakenFingerprint(options, activationOpts, e);
        if (existing == null) {
          throw e;
        }
        machine = existing;
        reused = true;
      } else {
        throw new TamgaMachineOverLimitException(
            ValidationMeta.of(Instant.now(), false, e.error() == null ? null : e.error().detail(),
                limit),
            false, e);
      }
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
      if (reused) {
        // Not ours to delete. The row was there before this call, and destroying a machine the
        // customer already licensed because some *other* machine pushed the license over its
        // limit would free a seat at the wrong machine's expense.
        throw new TamgaMachineOverLimitException(meta, false, null);
      }
      deleteQuietly(machine);
      throw new TamgaMachineOverLimitException(meta);
    }
    return new ActivationResult(machine, meta);
  }

  /**
   * Resolves a {@code 409 FINGERPRINT_TAKEN} to the machine that already holds the fingerprint, or
   * {@code null} when that is not what happened or the row cannot be found on this license.
   *
   * <p>Deliberately narrow. It fires only on that one error code, only when the caller opted in,
   * and only for a machine the {@code filter[license]} narrowing proves belongs to the license
   * being activated against -- a machine resource carries no license id of its own, so a row found
   * any other way could not be shown to be the right one.
   */
  private Machine recoverTakenFingerprint(CreateMachineOptions options, ActivationOptions opts,
      TamgaApiException failure) {
    if (!opts.reusesTakenFingerprint()
        || !(failure instanceof TamgaApiException.FingerprintTakenException)) {
      return null;
    }
    return findMachineByFingerprint(options.fingerprint(), options.licenseId());
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
   * <p>The server's heartbeat window is the policy's {@code heartbeat_duration} when that field is
   * set, and 600 seconds only when it is null. Use {@link HeartbeatScheduler} rather than driving
   * this by hand -- but note its default interval is derived from the 600s fallback, so on a
   * policy with a shorter window the caller must set the interval explicitly.
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

  /**
   * Lists a machine's processes, one keyset-paginated page at a time.
   *
   * <p>Keyset, like {@link #listComponents} and unlike {@link #listMachines}: this route takes
   * {@code limit} and {@code page[after]}, sends no {@code meta.page}, and the next cursor is
   * synthesized from the last row's id when a full page came back.
   *
   * <p>Pair it with {@link #deleteProcess} to find the rows nothing is reaping: a machine whose
   * process list keeps growing across runs is one whose application never disposed of its
   * registrations.
   */
  public Page<Process> listMachineProcesses(String machineId, ListOptions options) {
    ListOptions opts = options == null ? ListOptions.defaults() : options;
    JsonNode root = transport.getJson(Arrays.asList("machines", machineId, "processes"),
        pageQuery(opts));
    List<Process> items = new ArrayList<>();
    for (JsonNode node : root.path("data")) {
      items.add(Process.fromResourceNode(node));
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

  /**
   * Deletes a process registration.
   *
   * <p><b>Call this.</b> The server's own reaper for expired process rows is not wired up, so
   * nothing removes them on its own: a row created by {@link #createProcess} outlives the process
   * it describes and goes on counting against the license's {@code TOO_MANY_PROCESSES} limit until
   * something deletes it. An application that registers a process per run and never deletes one
   * accumulates rows until activation starts failing on a limit no running process is actually
   * using.
   *
   * <p>{@link ProcessHeartbeatScheduler#dispose()} pairs the two: it stops pinging and deletes the
   * row in one call, which is the shape most callers want at shutdown.
   *
   * <p>Answers {@code 204} with no content. A row that is already gone answers
   * {@code 404 NOT_FOUND} ({@link TamgaApiException.NotFoundException}), which for a deletion is
   * usually the outcome the caller wanted anyway.
   */
  public void deleteProcess(String processId) {
    transport.deleteNoContent(Arrays.asList("processes", processId));
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

  // ------------------------------------------------- releases and diagnostics

  /**
   * Asks whether a newer release is available for an installed version.
   *
   * <p><b>Read {@link UpgradeCheckResult} before rendering the answer.</b> The negative case means
   * "nothing is available to you", not "you are up to date": the server answers {@code 204} both
   * when no newer release exists and when one exists that this license is not entitled to, and it
   * does that deliberately so a refusal cannot leak the existence of a build the caller cannot
   * have. There is no client-side way to tell the two apart.
   *
   * <p>This route is {@code OptionalAuth}: a product whose distribution strategy is open answers
   * an unauthenticated caller, so an auto-updater keeps working for an installation with no
   * credential. This SDK sends its credential regardless, per its rule that credentials go on
   * every request.
   *
   * <p>Two outcomes arrive as exceptions rather than as a result: a <b>suspended</b> license is
   * refused with {@code 403} ({@link TamgaApiException.ForbiddenException}) rather than being
   * folded into the {@code 204}, and an unknown product id answers {@code 404}. A malformed query
   * is rejected by a bare extractor that answers <b>plain-text</b> {@code 400}, not a JSON:API
   * error document, so it surfaces with the synthetic {@code UNKNOWN} code the error path uses for
   * any non-JSON:API body.
   *
   * <p>No download URL comes back. The artifact-download route exists, but no credential this SDK
   * issues is permitted to use it, so obtaining the build itself is the application's problem.
   *
   * @param options the product, platform, file type, installed version and channel to check
   * @return the offered release, or {@link UpgradeCheckResult#none()}
   */
  public UpgradeCheckResult checkForUpgrade(UpgradeCheckOptions options) {
    JsonNode root = transport.getJsonOrNoContent(
        Arrays.asList("releases", "actions", "upgrade"), options.toQuery());
    if (root == null) {
      return UpgradeCheckResult.none();
    }
    Release release = Release.fromResourceNode(root.get("data"));
    if (release == null) {
      // A 200 that carried no resource is not the same event as a 204, and collapsing them would
      // report "no update" for what is actually a malformed response.
      throw new TamgaTransportException(
          "The upgrade check answered 200 with no release resource.");
    }
    return UpgradeCheckResult.of(release);
  }

  /**
   * Reads the server's liveness report.
   *
   * <p>The one route this client calls that is <b>not</b> under
   * {@code /v1/accounts/{accountId}}, and the only one whose response is not a JSON:API document
   * -- see {@link HealthStatus}. It is public server-side and exempt from host-header
   * verification.
   *
   * <p><b>That exemption is what makes it a diagnostic.</b> If every other call is failing with
   * {@code 403} and "The Host header does not match any configured host" while this one succeeds,
   * the fault is the server's allowed-hosts configuration rather than the caller's credential. The
   * converse does not hold: this route succeeds for a caller whose credential is wrong, invalid or
   * absent, so a healthy answer says nothing about authentication.
   */
  public HealthStatus health() {
    return HealthStatus.fromJson(transport.getRootJson(Arrays.asList("v1", "health"), null));
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
