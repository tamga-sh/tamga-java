package sh.tamga.sdk.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Generic {@code {id, attributes}} JSON:API resource wrapper. Package-private -- an internal wire
 * decoding helper, not part of this SDK's public model surface.
 */
final class JsonApiResource<A> {

  private final String id;
  private final A attributes;

  @JsonCreator
  JsonApiResource(@JsonProperty("id") String id, @JsonProperty("attributes") A attributes) {
    this.id = id;
    this.attributes = attributes;
  }

  String id() {
    return id;
  }

  A attributes() {
    return attributes;
  }
}
