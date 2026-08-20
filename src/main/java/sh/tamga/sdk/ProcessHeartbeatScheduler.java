package sh.tamga.sdk;

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import sh.tamga.sdk.model.Process;

/**
 * Pings a process's heartbeat on a timer.
 *
 * <p>The process window is a <b>hardcoded 30 seconds</b> -- far shorter than a machine's 600 -- and
 * has no resurrection grace period at all: once a process misses its window the row is deleted
 * outright rather than being marked dead and revivable.
 *
 * <p>That makes the tick callback more important here than for machines. A failed ping is much
 * closer to losing the process registration entirely, and the correct recovery is usually to
 * re-create the process rather than keep pinging a row that no longer exists.
 */
public final class ProcessHeartbeatScheduler implements AutoCloseable {

  /** The server's hardcoded process heartbeat window. */
  public static final Duration WINDOW = Duration.ofSeconds(30);

  /** A third of {@link #WINDOW}, leaving room for two consecutive failures. */
  public static final Duration DEFAULT_INTERVAL = Duration.ofSeconds(WINDOW.getSeconds() / 3);

  private final TamgaClient client;
  private final String processId;
  private final Duration interval;
  private final BiConsumer<Process, Throwable> onTick;
  private final AtomicBoolean running = new AtomicBoolean(false);
  private volatile ScheduledExecutorService executor;

  private ProcessHeartbeatScheduler(TamgaClient client, String processId, Duration interval,
      BiConsumer<Process, Throwable> onTick) {
    this.client = client;
    this.processId = processId;
    this.interval = interval;
    this.onTick = onTick;
  }

  /** Starts building a scheduler for the given process. */
  public static Builder builder(TamgaClient client, String processId) {
    return new Builder(client, processId);
  }

  /** Starts pinging on a daemon thread, with the first ping one interval from now. */
  public void start() {
    if (!running.compareAndSet(false, true)) {
      return;
    }
    ThreadFactory factory = runnable -> {
      Thread thread = new Thread(runnable, "tamga-process-heartbeat-" + processId);
      thread.setDaemon(true);
      return thread;
    };
    ScheduledExecutorService service = Executors.newSingleThreadScheduledExecutor(factory);
    executor = service;
    service.scheduleAtFixedRate(this::tick, interval.toMillis(), interval.toMillis(),
        TimeUnit.MILLISECONDS);
  }

  /** Stops pinging. Safe to call more than once. */
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
    Process process = null;
    Throwable failure = null;
    try {
      process = client.pingProcess(processId);
    } catch (RuntimeException e) {
      failure = e;
    }
    if (onTick != null) {
      try {
        onTick.accept(process, failure);
      } catch (RuntimeException ignored) {
        // See HeartbeatScheduler.tick -- a throwing callback would otherwise cancel all future
        // runs silently.
      }
    }
  }

  /** Builds a {@link ProcessHeartbeatScheduler}. */
  public static final class Builder {

    private final TamgaClient client;
    private final String processId;
    private Duration interval = DEFAULT_INTERVAL;
    private BiConsumer<Process, Throwable> onTick;

    private Builder(TamgaClient client, String processId) {
      this.client = client;
      this.processId = processId;
    }

    /**
     * Overrides the ping interval. A non-positive value falls back to
     * {@link #DEFAULT_INTERVAL}.
     */
    public Builder interval(Duration value) {
      this.interval = value == null || value.isNegative() || value.isZero()
          ? DEFAULT_INTERVAL : value;
      return this;
    }

    /** Sets the per-tick observer, receiving the updated process or the failure that occurred. */
    public Builder onTick(BiConsumer<Process, Throwable> value) {
      this.onTick = value;
      return this;
    }

    /** Builds the scheduler. It is not started until {@link ProcessHeartbeatScheduler#start()}. */
    public ProcessHeartbeatScheduler build() {
      return new ProcessHeartbeatScheduler(client, processId, interval, onTick);
    }
  }
}
