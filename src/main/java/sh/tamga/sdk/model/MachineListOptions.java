package sh.tamga.sdk.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Request options for {@code TamgaClient.listMachines} -- <b>offset</b> pagination plus the
 * collection's filters.
 *
 * <p>Deliberately not {@link ListOptions}. The machine collection is the one listing in this SDK's
 * reach that pages by {@code page[number]}/{@code page[size]} and reports {@code meta.page}; the
 * component and process listings under a machine page by {@code limit}/{@code page[after]} and
 * report no metadata at all. Sharing one options type would let a cursor be sent to a route that
 * ignores it, which is exactly the mistake the entitlements listing already cost this fleet once.
 *
 * <p><b>There is no fingerprint filter.</b> The server accepts {@code filter[license]},
 * {@code filter[owner]}, {@code filter[group]}, {@code filter[platform]} and the free-text
 * {@code filter[q]}, and nothing else. {@code filter[q]} is a case-insensitive <em>substring</em>
 * match across {@code name}, {@code hostname} <b>and</b> {@code fingerprint}, so a fingerprint
 * narrows the result but does not select it: a caller looking for one exact machine must compare
 * {@link Machine#fingerprint()} itself on the rows that come back. See
 * {@code TamgaClient.findMachineByFingerprint}, which does precisely that.
 *
 * <p>Unset filters are omitted from the query string entirely. Each list filter accepts at most 50
 * values server-side.
 */
public final class MachineListOptions {

  /** The server's own default page size, applied when {@link #pageSize()} is left at zero. */
  public static final int DEFAULT_PAGE_SIZE = 25;

  /** The largest page the server will serve; a bigger request is clamped down to this. */
  public static final int MAX_PAGE_SIZE = 100;

  private final int pageNumber;
  private final int pageSize;
  private final String search;
  private final List<String> licenseIds;
  private final List<String> platforms;
  private final String sort;
  private final boolean descending;

  @SuppressWarnings("checkstyle:ParameterNumber")
  private MachineListOptions(int pageNumber, int pageSize, String search, List<String> licenseIds,
      List<String> platforms, String sort, boolean descending) {
    this.pageNumber = pageNumber;
    this.pageSize = pageSize;
    this.search = search;
    this.licenseIds = licenseIds;
    this.platforms = platforms;
    this.sort = sort;
    this.descending = descending;
  }

  /** Returns options for the first page at the client's default size. */
  public static MachineListOptions defaults() {
    return new MachineListOptions(0, 0, null, null, null, null, false);
  }

  /**
   * Returns a copy requesting the given <b>1-based</b> page number. A non-positive value means the
   * first page.
   */
  public MachineListOptions page(int value) {
    return new MachineListOptions(value, pageSize, search, licenseIds, platforms, sort, descending);
  }

  /**
   * Returns a copy requesting the given page size. Zero or less accepts this client's default; the
   * server clamps anything above {@value #MAX_PAGE_SIZE} down to it.
   */
  public MachineListOptions size(int value) {
    return new MachineListOptions(pageNumber, value, search, licenseIds, platforms, sort,
        descending);
  }

  /**
   * Returns a copy carrying a free-text {@code filter[q]} term.
   *
   * <p>A case-insensitive substring match over {@code name}, {@code hostname} and
   * {@code fingerprint}. It narrows; it does not identify. The server truncates the term at 200
   * characters.
   */
  public MachineListOptions search(String value) {
    return new MachineListOptions(pageNumber, pageSize, value, licenseIds, platforms, sort,
        descending);
  }

  /** Returns a copy restricted to the given license ids ({@code filter[license]}). */
  public MachineListOptions licenseIds(List<String> values) {
    return new MachineListOptions(pageNumber, pageSize, search, copyOf(values), platforms, sort,
        descending);
  }

  /** Returns a copy restricted to a single license id, the common case of {@link #licenseIds}. */
  public MachineListOptions licenseId(String value) {
    return licenseIds(value == null ? null : Collections.singletonList(value));
  }

  /** Returns a copy restricted to the given platform strings ({@code filter[platform]}). */
  public MachineListOptions platforms(List<String> values) {
    return new MachineListOptions(pageNumber, pageSize, search, licenseIds, copyOf(values), sort,
        descending);
  }

  /**
   * Returns a copy sorted on the given column.
   *
   * <p>The server accepts {@code created_at}, {@code updated_at}, {@code name} and
   * {@code last_heartbeat_at}, defaults to {@code created_at}, and answers an error for anything
   * else -- this SDK does not pre-validate the name, so an unsupported one surfaces as an API
   * error rather than being silently dropped.
   */
  public MachineListOptions sort(String value) {
    return new MachineListOptions(pageNumber, pageSize, search, licenseIds, platforms, value,
        descending);
  }

  /** Returns a copy sorting descending rather than ascending. */
  public MachineListOptions descending(boolean value) {
    return new MachineListOptions(pageNumber, pageSize, search, licenseIds, platforms, sort, value);
  }

  /** Returns the requested 1-based page number, or {@code 0} to accept the first page. */
  public int pageNumber() {
    return pageNumber;
  }

  /** Returns the requested page size, or {@code 0} to accept this client's default. */
  public int pageSize() {
    return pageSize;
  }

  /**
   * Renders these options as query parameters.
   *
   * <p>{@code effectiveSize} is supplied by the client rather than read from {@link #pageSize()}
   * so the size actually sent is always one the caller's page-walking code can see.
   */
  public Map<String, String> toQuery(int effectiveSize) {
    Map<String, String> query = new LinkedHashMap<>();
    query.put("page[number]", Integer.toString(pageNumber > 0 ? pageNumber : 1));
    query.put("page[size]", Integer.toString(effectiveSize));
    if (search != null && !search.isEmpty()) {
      query.put("filter[q]", search);
    }
    putCsv(query, "filter[license]", licenseIds);
    putCsv(query, "filter[platform]", platforms);
    if (sort != null && !sort.isEmpty()) {
      // The server reads a leading '-' as descending and lets it override `order`, so one
      // parameter carries both rather than two that can contradict each other.
      query.put("sort", descending ? "-" + sort : sort);
    } else if (descending) {
      query.put("order", "desc");
    }
    return query;
  }

  private static void putCsv(Map<String, String> query, String key, List<String> values) {
    if (values == null || values.isEmpty()) {
      return;
    }
    StringBuilder joined = new StringBuilder();
    for (String value : values) {
      if (value == null || value.isEmpty()) {
        continue;
      }
      if (joined.length() > 0) {
        joined.append(',');
      }
      joined.append(value);
    }
    if (joined.length() > 0) {
      query.put(key, joined.toString());
    }
  }

  private static List<String> copyOf(List<String> values) {
    return values == null ? null : new ArrayList<>(values);
  }
}
