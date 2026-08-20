package sh.tamga.sdk.model;

import java.util.Collections;
import java.util.List;

/**
 * A single page of a keyset-paginated list.
 *
 * <p><b>Pagination is synthetic.</b> These endpoints carry no cursor metadata or links of their
 * own, so {@link #nextCursor()} is set to the last item's id if and only if a full page was
 * returned. It is {@code null} on a short or empty page, which is the signal that there is nothing
 * further to fetch.
 *
 * @param <T> the resource type carried in this page
 */
public final class Page<T> {

  private final String nextCursor;
  private final List<T> items;

  /** Creates a page. {@code nextCursor} may be {@code null} to signal the end of the list. */
  public Page(String nextCursor, List<T> items) {
    this.nextCursor = nextCursor;
    this.items = items == null ? Collections.emptyList() : items;
  }

  /** Returns the cursor to pass as the next request's {@code after}, or {@code null} if done. */
  public String nextCursor() {
    return nextCursor;
  }

  /** Returns an unmodifiable view of this page's items. */
  public List<T> items() {
    return Collections.unmodifiableList(items);
  }
}
