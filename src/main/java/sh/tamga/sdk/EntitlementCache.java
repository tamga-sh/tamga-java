package sh.tamga.sdk;

import java.time.Duration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.LongSupplier;

/**
 * A short-lived, per-license cache of entitlement codes backing
 * {@link TamgaClient#hasEntitlement(String, String)}.
 *
 * <p>Entries live for {@link #TTL}. Eviction happens on read plus explicit invalidation; there is
 * no background sweep, no size bound, and no eviction policy. That is deliberate -- entries are
 * keyed by license id, and an embedded SDK sees a small, usually singleton set.
 *
 * <p><b>The network call never happens while the lock is held.</b> Two concurrent misses for the
 * same license will therefore both fetch, and the last writer wins. That is accepted rather than
 * deduplicated: single-flighting would mean holding a lock across a network round trip, which is a
 * far worse failure mode than one redundant request.
 */
final class EntitlementCache {

  /** How long a fetched entitlement set stays fresh. */
  static final Duration TTL = Duration.ofSeconds(60);

  private final Map<String, Entry> entries = new HashMap<>();
  private final LongSupplier clock;

  EntitlementCache(LongSupplier clock) {
    this.clock = clock;
  }

  /** Returns the cached codes for a license, or {@code null} when absent or stale. */
  synchronized Set<String> fresh(String licenseId) {
    Entry entry = entries.get(licenseId);
    if (entry == null) {
      return null;
    }
    if (clock.getAsLong() - entry.fetchedAtMillis >= TTL.toMillis()) {
      return null;
    }
    return entry.codes;
  }

  /** Stores a freshly fetched code set for a license. */
  synchronized void put(String licenseId, Set<String> codes) {
    entries.put(licenseId, new Entry(new HashSet<>(codes), clock.getAsLong()));
  }

  /** Drops the cached entry for a license, forcing the next lookup to refetch. */
  synchronized void invalidate(String licenseId) {
    entries.remove(licenseId);
  }

  private static final class Entry {
    private final Set<String> codes;
    private final long fetchedAtMillis;

    Entry(Set<String> codes, long fetchedAtMillis) {
      this.codes = codes;
      this.fetchedAtMillis = fetchedAtMillis;
    }
  }
}
