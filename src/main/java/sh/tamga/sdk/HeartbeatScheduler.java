package sh.tamga.sdk;

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import sh.tamga.sdk.model.HeartbeatStatus;
import sh.tamga.sdk.model.Machine;
import sh.tamga.sdk.model.Policy;

/**
 * Pings a machine's heartbeat on a timer.
 *
 * <p>The server's heartbeat window is the policy's {@code heartbeat_duration} when that field is
 * set, and 600 seconds only when it is null. {@link #DEFAULT_INTERVAL} is a third of that 600s
 * fallback, which tolerates two consecutive failed pings before the machine's window lapses --
 * on a policy that leaves {@code heartbeat_duration} unset.
 *
 * <p><b>The default interval does not adapt to a shorter policy window.</b> On a policy whose
 * {@code heartbeat_duration} is below 600 seconds the default ping rate is too slow and the
 * machine goes {@link HeartbeatStatus#DEAD} between pings -- server-side state, not something the
 * ping response ever shows.
 *
 * <p><b>Read the policy and size the interval from it.</b> {@link TamgaClient#getLicensePolicy}
 * returns the policy a license runs under, and {@link Builder#policy(Policy)} turns its
 * {@link Policy#effectiveHeartbeatWindow()} into an interval:
 *
 * <pre>{@code
 * HeartbeatScheduler.builder(client, machineId)
 *     .policy(client.getLicensePolicy(licenseId))
 *     .build();
 * }</pre>
 *
 * <p>Note it is {@code getLicensePolicy}, not {@code getPolicy}: the standalone policy read wants
 * the {@code policy.read} permission, which a license-key credential does not hold, while the
 * nested one is authorised as a license read and works. Do <em>not</em> try to recover the window
 * from a ping instead -- {@link Machine#nextHeartbeatAt()} carries the true value only on the
 * checkout-family responses and the 600-second fallback everywhere else, with nothing on the wire
 * to say which one arrived. See that method. {@link TamgaClient#updateMachine} is on the wrong
 * side of that split too: it is a write, but one that reports the 600-second fallback and can
 * still answer {@link HeartbeatStatus#DEAD}, so nothing it returns is usable for sizing either.
 *
 * <pre>{@code
 * HeartbeatScheduler scheduler = HeartbeatScheduler.builder(client, machineId)
 *     .onTick((machine, error) -> {
 *       // Do not branch on the status to stop the loop. A ping answers ALIVE or RESURRECTED and
 *       // nothing else, so a `== DEAD` branch here would simply be dead code (see below).
 *       // A 404 is the only signal that the row is actually gone. Re-activate off that.
 *       if (error instanceof TamgaApiException.NotFoundException) {
 *         reactivate();
 *       }
 *     })
 *     .build();
 * scheduler.start();
 * }</pre>
 *
 * <p><b>Never stop the loop on a status -- any status.</b> The only terminal signal from a ping
 * is a 404. This scheduler never gates a tick on the previous outcome, and stopping is what would
 * actually lose the machine: a stale machine is always one successful ping away from
 * {@link HeartbeatStatus#ALIVE} again, because the ping write is a bare
 * {@code SET last_heartbeat_at = NOW()} with no resurrection check.
 *
 * <p><b>A ping response can never say {@link HeartbeatStatus#DEAD}.</b> The endpoint writes
 * {@code last_heartbeat_at = NOW()} and then derives {@code heartbeat_status} from that same
 * timestamp, so the age it measures is ~0 and the answer is always {@code ALIVE} or
 * {@code RESURRECTED}. Earlier guidance here framed the keep-pinging rule around "a {@code DEAD}
 * reading from a ping" -- an observation that cannot occur on this route. The rule is right; that
 * premise was not.
 *
 * <p><b>{@code DEAD} is still a real server state</b>, just not one a ping reports. It means the
 * last ping is older than the window, nothing more: the server derives it from
 * {@code last_heartbeat_at} and never consults the policy's {@code require_heartbeat}, which
 * defaults to {@code false} and is exactly what the cull job requires before removing anything.
 * On a default policy nothing is ever culled, so a machine can sit in {@code DEAD} indefinitely
 * with its row and its seat both still there. In this SDK it surfaces only through the
 * checkout-family reads, which resolve the machine through a policy-joined query:
 * {@link sh.tamga.sdk.checkout.MachineFile#verifyAndDecrypt} and
 * {@link TamgaClient#generateOfflineProof}. A dedicated machine read would show it too; this SDK
 * does not expose one yet.
 *
 * <p><b>A 404 from the ping is the row-is-gone signal.</b> That, not {@code DEAD}, is what
 * re-activation belongs on. It arrives in the callback's {@code error} argument as a
 * {@link sh.tamga.sdk.error.TamgaApiException.NotFoundException}.
 *
 * <p><b>Handle the tick callback.</b> It is the only way to observe either signal, which is why
 * failures are reported rather than swallowed.
 *
 * <p>This class is {@link AutoCloseable}, so a try-with-resources block stops it reliably.
 */
public final class HeartbeatScheduler implements AutoCloseable {

  /**
   * The server's <b>default</b> machine heartbeat window, which applies only when the policy's
   * {@code heartbeat_duration} is null. A policy that sets that field overrides the window, and
   * this constant does not track the override.
   */
  public static final Duration WINDOW = Duration.ofSeconds(600);

  /**
   * A third of {@link #WINDOW}, leaving room for two consecutive failures -- but only on a policy
   * that leaves {@code heartbeat_duration} unset. See the class Javadoc before relying on it.
   */
  public static final Duration DEFAULT_INTERVAL = Duration.ofSeconds(WINDOW.getSeconds() / 3);

  /**
   * Returns the ping interval for a given server heartbeat window: a third of it, the same ratio
   * {@link #DEFAULT_INTERVAL} applies to {@link #WINDOW}, leaving room for two consecutive failed
   * pings before the window lapses.
   *
   * <p>A null or non-positive window falls back to {@link #DEFAULT_INTERVAL} rather than producing
   * a zero interval that would busy-loop the timer. Combine with
   * {@link Policy#effectiveHeartbeatWindow()} -- or use {@link Builder#policy(Policy)}, which does
   * both.
   *
   * @param window the server's effective heartbeat window for the machine
   * @return a third of {@code window}, or {@link #DEFAULT_INTERVAL} if it is null or non-positive
   */
  public static Duration intervalForWindow(Duration window) {
    if (window == null || window.isNegative() || window.isZero()) {
      return DEFAULT_INTERVAL;
    }
    return window.dividedBy(3);
  }

  private final TamgaClient client;
  private final String machineId;
  private final Duration interval;
  private final BiConsumer<Machine, Throwable> onTick;
  /**
   * Guards {@link #executor}, which is the single source of truth for whether this scheduler is
   * running.
   *
   * <p>Lifecycle state used to be split across an {@code AtomicBoolean} and a volatile executor
   * reference, which could be observed out of sync: a {@code stop()} landing between the flag
   * flip and the executor assignment saw a null executor, did nothing, and left a live timer
   * behind that no later {@code stop()} could ever reach. One lock over one field removes the
   * window rather than narrowing it.
   */
  private final Object lifecycleLock = new Object();

  private ScheduledExecutorService executor;

  private HeartbeatScheduler(TamgaClient client, String machineId, Duration interval,
      BiConsumer<Machine, Throwable> onTick) {
    this.client = client;
    this.machineId = machineId;
    this.interval = interval;
    this.onTick = onTick;
  }

  /** Starts building a scheduler for the given machine. */
  public static Builder builder(TamgaClient client, String machineId) {
    return new Builder(client, machineId);
  }

  /**
   * Starts pinging on a daemon thread. The first ping fires after one interval, not immediately --
   * a machine has just been activated when a scheduler is created, so its heartbeat is already
   * fresh.
   *
   * <p>Calling this on an already-running scheduler does nothing.
   */
  public void start() {
    synchronized (lifecycleLock) {
      if (executor != null) {
        return;
      }
      executor = newTimer();
      executor.scheduleAtFixedRate(this::tick, interval.toMillis(), interval.toMillis(),
          TimeUnit.MILLISECONDS);
    }
  }

  private ScheduledExecutorService newTimer() {
    ThreadFactory factory = runnable -> {
      Thread thread = new Thread(runnable, "tamga-heartbeat-" + machineId);
      thread.setDaemon(true);
      return thread;
    };
    return Executors.newSingleThreadScheduledExecutor(factory);
  }

  /** Stops pinging. Safe to call more than once, and safe to call from a tick callback. */
  public void stop() {
    synchronized (lifecycleLock) {
      if (executor == null) {
        return;
      }
      executor.shutdownNow();
      executor = null;
    }
  }

  @Override
  public void close() {
    stop();
  }

  /** Returns whether the scheduler is currently running. */
  public boolean running() {
    synchronized (lifecycleLock) {
      return executor != null;
    }
  }

  /**
   * Sends one ping and reports the outcome. Package-private so tests can drive it directly.
   *
   * <p>The outcome never gates the next tick, and no status is a stop condition. A ping response
   * cannot even carry {@link HeartbeatStatus#DEAD} -- the endpoint writes
   * {@code last_heartbeat_at = NOW()} before deriving the status from it -- so short-circuiting on
   * that value would be unreachable code, and short-circuiting on any status this SDK does not yet
   * know about is what would strand a machine whose row is still very much alive.
   */
  void tick() {
    Machine machine = null;
    Throwable failure = null;
    try {
      machine = client.pingHeartbeat(machineId);
    } catch (RuntimeException e) {
      failure = e;
    }
    if (onTick != null) {
      try {
        onTick.accept(machine, failure);
      } catch (RuntimeException ignored) {
        // A throwing callback must not kill the scheduled task: ScheduledExecutorService
        // silently cancels all future runs once a task throws, which would stop heartbeats
        // permanently with no signal.
      }
    }
  }

  /** Builds a {@link HeartbeatScheduler}. */
  public static final class Builder {

    private final TamgaClient client;
    private final String machineId;
    private Duration interval = DEFAULT_INTERVAL;
    private BiConsumer<Machine, Throwable> onTick;

    private Builder(TamgaClient client, String machineId) {
      this.client = client;
      this.machineId = machineId;
    }

    /**
     * Overrides the ping interval. A non-positive value falls back to {@link #DEFAULT_INTERVAL}.
     * Keep it comfortably below the policy's effective window -- {@link #WINDOW} is only the
     * window a policy gets when it leaves {@code heartbeat_duration} unset.
     */
    public Builder interval(Duration value) {
      this.interval = value == null || value.isNegative() || value.isZero()
          ? DEFAULT_INTERVAL : value;
      return this;
    }

    /**
     * Sizes the interval from a server heartbeat window, via
     * {@link HeartbeatScheduler#intervalForWindow(Duration)}.
     *
     * <p>Use this when the window is known from somewhere other than a {@link Policy} -- an
     * operator-configured value, say. With a policy in hand, prefer {@link #policy(Policy)}.
     */
    public Builder window(Duration value) {
      return interval(intervalForWindow(value));
    }

    /**
     * Sizes the interval from the policy's {@link Policy#effectiveHeartbeatWindow()}.
     *
     * <p>This is the only reliable way to get the window: the policy states it directly, whereas
     * {@link Machine#nextHeartbeatAt()} means different things on different routes. Fetch the
     * policy with {@link TamgaClient#getLicensePolicy(String)} -- the nested read is authorised as
     * a license read, so a license-key credential can call it, while the standalone
     * {@link TamgaClient#getPolicy(String)} needs a permission that credential does not hold.
     *
     * <p>A null policy leaves the interval unchanged, so a caller whose policy read failed keeps
     * whatever default or explicit interval was already set instead of losing it.
     */
    public Builder policy(Policy value) {
      return value == null ? this : window(value.effectiveHeartbeatWindow());
    }

    /**
     * Sets the per-tick observer, receiving the updated machine or the failure that occurred.
     * Exactly one of the two is non-null.
     */
    public Builder onTick(BiConsumer<Machine, Throwable> value) {
      this.onTick = value;
      return this;
    }

    /** Builds the scheduler. It is not started until {@link HeartbeatScheduler#start()}. */
    public HeartbeatScheduler build() {
      return new HeartbeatScheduler(client, machineId, interval, onTick);
    }
  }
}
