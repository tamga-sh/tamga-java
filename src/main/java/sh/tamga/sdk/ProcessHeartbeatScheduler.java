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
 * <p>The process window is a <b>hardcoded 30 seconds</b> -- genuinely fixed, unlike a machine's,
 * which the owning policy can set via {@code heartbeat_duration} -- and has no resurrection grace
 * period at all: once a process misses its window the row is deleted outright rather than being
 * marked dead and revivable.
 *
 * <p>That makes the tick callback more important here than for machines. A failed ping is much
 * closer to losing the process registration entirely, and the correct recovery is usually to
 * re-create the process rather than keep pinging a row that no longer exists.
 *
 * <p><b>{@link #close()} stops pinging; it does not delete the row.</b> Use {@link #dispose()} at
 * shutdown when the process registration should go away with the process -- see that method for
 * why leaving it behind is not harmless.
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
  /**
   * Claims the one deletion {@link #dispose()} is allowed to perform.
   *
   * <p>Deliberately not guarded by {@link #lifecycleLock}: the deletion is a network round trip,
   * and holding the lifecycle monitor across it would block {@link #stop()} and {@link #running()}
   * for the duration of an HTTP call. A compare-and-set claim gives the same "at most one DELETE"
   * guarantee without a lock spanning I/O. The claim is released again if the delete throws, so a
   * failed dispose stays retryable.
   */
  private final AtomicBoolean deletionClaimed = new AtomicBoolean();

  private ScheduledExecutorService executor;

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
      Thread thread = new Thread(runnable, "tamga-process-heartbeat-" + processId);
      thread.setDaemon(true);
      return thread;
    };
    return Executors.newSingleThreadScheduledExecutor(factory);
  }

  /** Stops pinging. Safe to call more than once. */
  public void stop() {
    synchronized (lifecycleLock) {
      if (executor == null) {
        return;
      }
      executor.shutdownNow();
      executor = null;
    }
  }

  /**
   * Stops pinging. <b>Does not delete the process row</b> -- see {@link #dispose()}.
   *
   * <p>Deliberately kept to stopping the timer: {@code close()} runs implicitly at the end of a
   * try-with-resources block, and a method that quietly performs a network write there would make
   * a scoped block delete server state the caller never asked it to.
   */
  @Override
  public void close() {
    stop();
  }

  /**
   * Stops pinging <b>and deletes the process row</b>.
   *
   * <p><b>Something has to.</b> The server's reaper for expired process rows is not wired up, so a
   * row created by {@code createProcess} survives the process it describes indefinitely and goes
   * on counting against the license's process limit. An application that registers a process per
   * run and never deletes one accumulates rows until activation starts failing on a limit no
   * running process is actually using. Stopping the pings alone does not release anything.
   *
   * <p>At most one {@code DELETE} is ever issued: a second call, or a concurrent one that loses
   * the race, returns without touching the network. A call that <b>fails</b> releases that claim
   * again, so a dispose that hit a transient network error can simply be retried.
   *
   * <p>A tick already in flight when this is called may still complete its ping. That is harmless:
   * it either lands before the delete, or answers {@code 404} afterwards, which the tick callback
   * reports like any other failure.
   *
   * @throws sh.tamga.sdk.error.TamgaApiException if the server refused the deletion. A
   *     {@link sh.tamga.sdk.error.TamgaApiException.NotFoundException} means the row was already
   *     gone, which for a deletion is usually the outcome the caller wanted.
   */
  public void dispose() {
    stop();
    if (!deletionClaimed.compareAndSet(false, true)) {
      return;
    }
    try {
      client.deleteProcess(processId);
    } catch (RuntimeException e) {
      deletionClaimed.set(false);
      throw e;
    }
  }

  /** Returns whether the scheduler is currently running. */
  public boolean running() {
    synchronized (lifecycleLock) {
      return executor != null;
    }
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
