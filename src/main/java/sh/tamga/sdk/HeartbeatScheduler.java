package sh.tamga.sdk;

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import sh.tamga.sdk.model.HeartbeatStatus;
import sh.tamga.sdk.model.Machine;

/**
 * Pings a machine's heartbeat on a timer.
 *
 * <p>The server's heartbeat window is a <b>hardcoded 600 seconds</b>, not driven by the policy's
 * {@code heartbeat_duration} despite that field existing. {@link #DEFAULT_INTERVAL} is a third of
 * the window, which tolerates two consecutive failed pings before the machine goes dead.
 *
 * <pre>{@code
 * HeartbeatScheduler scheduler = HeartbeatScheduler.builder(client, machineId)
 *     .onTick((machine, error) -> {
 *       if (machine != null && machine.heartbeatStatus() == HeartbeatStatus.DEAD) {
 *         reactivate();
 *       }
 *     })
 *     .build();
 * scheduler.start();
 * }</pre>
 *
 * <p><b>Handle the tick callback.</b> It is the only way to observe the machine going
 * {@link HeartbeatStatus#DEAD}, which means the row was culled server-side and the correct response
 * is to re-activate, not to keep pinging. Errors are reported rather than swallowed for the same
 * reason.
 *
 * <p>This class is {@link AutoCloseable}, so a try-with-resources block stops it reliably.
 */
public final class HeartbeatScheduler implements AutoCloseable {

  /** The server's hardcoded machine heartbeat window. */
  public static final Duration WINDOW = Duration.ofSeconds(600);

  /** A third of {@link #WINDOW}, leaving room for two consecutive failures. */
  public static final Duration DEFAULT_INTERVAL = Duration.ofSeconds(WINDOW.getSeconds() / 3);

  private final TamgaClient client;
  private final String machineId;
  private final Duration interval;
  private final BiConsumer<Machine, Throwable> onTick;
  private final AtomicBoolean running = new AtomicBoolean(false);
  private volatile ScheduledExecutorService executor;

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
    if (!running.compareAndSet(false, true)) {
      return;
    }
    ThreadFactory factory = runnable -> {
      Thread thread = new Thread(runnable, "tamga-heartbeat-" + machineId);
      thread.setDaemon(true);
      return thread;
    };
    ScheduledExecutorService service = Executors.newSingleThreadScheduledExecutor(factory);
    executor = service;
    service.scheduleAtFixedRate(this::tick, interval.toMillis(), interval.toMillis(),
        TimeUnit.MILLISECONDS);
  }

  /** Stops pinging. Safe to call more than once, and safe to call from a tick callback. */
  public void stop() {
    if (!running.compareAndSet(true, false)) {
      return;
    }
    ScheduledExecutorService service = executor;
    if (service != null) {
      service.shutdownNow();
      executor = null;
    }
  }

  @Override
  public void close() {
    stop();
  }

  /** Returns whether the scheduler is currently running. */
  public boolean running() {
    return running.get();
  }

  /** Sends one ping and reports the outcome. Package-private so tests can drive it directly. */
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
     * Keep it comfortably below {@link #WINDOW}.
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
