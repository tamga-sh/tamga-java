package sh.tamga.sdk.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Generic {@code {"data": <resource>}} JSON:API payload wrapper. Package-private -- an internal
 * wire decoding helper, not part of this SDK's public model surface.
 */
final class JsonApiPayload<A> {

  private final JsonApiResource<A> data;

  @JsonCreator
  JsonApiPayload(@JsonProperty("data") JsonApiResource<A> data) {
    this.data = data;
  }

  JsonApiResource<A> data() {
    return data;
  }
}
