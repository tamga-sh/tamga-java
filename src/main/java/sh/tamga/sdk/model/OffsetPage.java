package sh.tamga.sdk.model;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A single page of an <b>offset</b>-paginated list, carrying the server's own {@code meta.page}
 * block.
 *
 * <p><b>This is not {@link Page}.</b> The two coexist because the server genuinely paginates two
 * different ways and neither shape can be faked from the other:
 *
 * <table border="1">
 *   <caption>Which listing uses which</caption>
 *   <tr><th>Listing</th><th>Style</th><th>Request</th><th>End-of-list signal</th></tr>
 *   <tr><td>{@code GET /machines}</td><td>offset, this type</td>
 *       <td>{@code page[number]}, {@code page[size]}</td>
 *       <td>{@code meta.page.totalPages}, sent by the server</td></tr>
 *   <tr><td>{@code GET /machines/{id}/components}</td><td>keyset, {@link Page}</td>
 *       <td>{@code limit}, {@code page[after]}</td>
 *       <td>a short page -- the cursor is synthesized client-side</td></tr>
 *   <tr><td>{@code GET /machines/{id}/processes}</td><td>keyset, {@link Page}</td>
 *       <td>{@code limit}, {@code page[after]}</td>
 *       <td>a short page</td></tr>
 *   <tr><td>{@code GET /licenses/{id}/entitlements}</td><td>neither -- unpaginated</td>
 *       <td>{@code limit} only</td><td>none; truncated silently past 100</td></tr>
 * </table>
 *
 * <p><b>The wire keys inside {@code meta.page} mix two conventions</b>, which is real server
 * behaviour rather than a transcription slip: {@code number}, {@code size} and {@code total} are
 * bare lowercase, while {@code total_pages} is renamed to {@code totalPages}. Anything that
 * assumes one casing for the whole object reads three fields and misses the fourth.
 *
 * <p>{@code total} counts the rows matching the request's filters, not the whole table.
 *
 * @param <T> the resource type carried in this page
 */
public final class OffsetPage<T> {

  private final List<T> items;
  private final int number;
  private final int size;
  private final long total;
  private final int totalPages;

  /**
   * Creates a page from an already-decoded item list and the four {@code meta.page} counters.
   *
   * <p>The item list is copied; {@link #items()} returns an unmodifiable view of the copy.
   */
  public OffsetPage(List<T> items, int number, int size, long total, int totalPages) {
    this.items = items == null ? Collections.<T>emptyList() : new ArrayList<>(items);
    this.number = number;
    this.size = size;
    this.total = total;
    this.totalPages = totalPages;
  }

  /**
   * Builds a page from decoded items and the response document's {@code meta} node.
   *
   * <p>A missing or malformed {@code meta.page} degrades to zeroed counters rather than throwing:
   * the items are the payload, and losing the whole response because a counter was absent would be
   * worse than reporting a page that cannot say how many others there are. Callers that must know
   * whether more pages exist should check {@link #totalPages()} against {@link #number()} rather
   * than assuming.
   *
   * @param <T> the resource type carried in this page
   * @param items the already-decoded rows of this page
   * @param meta the response document's {@code meta} node, which carries {@code page}
   * @return a page carrying the items and whatever counters {@code meta.page} supplied
   */
  public static <T> OffsetPage<T> fromMetaNode(List<T> items, JsonNode meta) {
    JsonNode page = meta == null ? null : meta.get("page");
    return new OffsetPage<>(items,
        WireNodes.intOrZero(page, "number"),
        WireNodes.intOrZero(page, "size"),
        longOrZero(page, "total"),
        WireNodes.intOrZero(page, "totalPages"));
  }

  private static long longOrZero(JsonNode parent, String field) {
    Long value = WireNodes.longValue(parent, field);
    return value == null ? 0L : value;
  }

  /** Returns an unmodifiable view of this page's items. */
  public List<T> items() {
    return Collections.unmodifiableList(items);
  }

  /** Returns this page's 1-based number, as the server reported it. */
  public int number() {
    return number;
  }

  /** Returns the page size the server applied, which it clamps to at most 100. */
  public int size() {
    return size;
  }

  /** Returns how many rows match the request's filters in total, not the size of the table. */
  public long total() {
    return total;
  }

  /** Returns how many pages the filtered result spans. */
  public int totalPages() {
    return totalPages;
  }

  /**
   * Reports whether another page follows this one.
   *
   * <p>Unlike {@link Page}, this is answered by the server rather than inferred from a full page:
   * a filtered result whose last page happens to be exactly {@code size} rows long is reported
   * correctly here, where the keyset listings would hand back one more cursor and one empty page.
   */
  public boolean hasNextPage() {
    return number > 0 && number < totalPages;
  }
}
