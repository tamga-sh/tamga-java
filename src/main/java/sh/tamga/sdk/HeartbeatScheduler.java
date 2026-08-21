package sh.tamga.sdk;

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import sh.tamga.sdk.model.HeartbeatStatus;
import sh.tamga.sdk.model.Machine;

/**
 * Pings a machine's heartbeat on a timer.
 *
 * <p>The server's heartbeat window is the policy's {@code heartbeat_duration} when that field is
 * set, and 600 seconds only when it is null. {@link #DEFAULT_INTERVAL} is a third of that 600s
 * fallback, which tolerates two consecutive failed pings before the machine starts reporting
 * {@link HeartbeatStatus#DEAD} -- on a policy that leaves {@code heartbeat_duration} unset.
 *
 * <p><b>The default interval does not adapt to a shorter policy window.</b> On a policy whose
 * {@code heartbeat_duration} is below 600 seconds the default ping rate is too slow, and the
 * machine will read {@link HeartbeatStatus#DEAD} between pings. Such callers must set
 * {@link Builder#interval(Duration)} themselves. This SDK cannot tell them what their window is:
 * it exposes no policy read, and {@link Machine#nextHeartbeatAt()} is no substitute -- see that
 * method. The window has to be learned out of band.
 *
 * <pre>{@code
 * HeartbeatScheduler scheduler = HeartbeatScheduler.builder(client, machineId)
 *     .onTick((machine, error) -> {
 *       // DEAD is a staleness report, not a tombstone. This very ping already revived the
 *       // machine, so log it and let the scheduler carry on -- never stop or re-activate here.
 *       if (machine != null && machine.heartbeatStatus() == HeartbeatStatus.DEAD) {
 *         logStaleHeartbeat();
 *       }
 *       // A 404 is the only signal that the row is actually gone. Re-activate off that.
 *       if (error instanceof TamgaApiException.NotFoundException) {
 *         reactivate();
 *       }
 *     })
 *     .build();
 * scheduler.start();
 * }</pre>
 *
 * <p><b>{@link HeartbeatStatus#DEAD} does not mean the machine was culled.</b> It means only that
 * the last ping is older than the window. The server computes {@code heartbeat_status} from
 * {@code last_heartbeat_at} against that window and never consults the policy's
 * {@code require_heartbeat}, which defaults to {@code false} -- and the cull job early-returns
 * unless that flag is set. On a default policy nothing is ever culled, so a machine can report
 * {@code DEAD} indefinitely while its row and its seat are both still there. Pinging a
 * {@code DEAD} machine also succeeds and revives it: the write is a bare
 * {@code SET last_heartbeat_at = NOW()} with no resurrection check. So <b>keep pinging through
 * {@code DEAD}</b> -- this scheduler does, and stopping is what would actually lose the machine.
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
   * <p>The outcome never gates the next tick. A {@link HeartbeatStatus#DEAD} reading in particular
   * is not a stop condition -- it only says the previous ping was older than the window, and the
   * ping that observed it has already revived the machine. Short-circuiting the loop on
   * {@code DEAD} would strand a machine whose row is still very much alive.
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
