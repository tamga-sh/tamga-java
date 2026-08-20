package sh.tamga.sdk.model;

/**
 * Keyset-pagination request options shared by {@code listComponents} and {@code listEntitlements}.
 *
 * <p>{@code after} is an opaque cursor obtained from a previous {@link Page#nextCursor()}. The
 * server caps {@code limit} at 100.
 */
public final class ListOptions {

  private final String after;
  private final int limit;

  private ListOptions(String after, int limit) {
    this.after = after;
    this.limit = limit;
  }

  /** Returns options requesting the first page at the server's default page size. */
  public static ListOptions defaults() {
    return new ListOptions(null, 0);
  }

  /** Returns options requesting the first page of at most {@code limit} items. */
  public static ListOptions ofLimit(int limit) {
    return new ListOptions(null, limit);
  }

  /** Returns a copy of these options starting after the given cursor. */
  public ListOptions after(String cursor) {
    return new ListOptions(cursor, limit);
  }

  /** Returns a copy of these options with the given page size. */
  public ListOptions limit(int newLimit) {
    return new ListOptions(after, newLimit);
  }

  /** Returns the cursor to start after, or {@code null} for the first page. */
  public String afterCursor() {
    return after;
  }

  /** Returns the requested page size, or {@code 0} to accept the server default. */
  public int pageSize() {
    return limit;
  }
}
