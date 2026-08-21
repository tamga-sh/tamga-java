package sh.tamga.sdk.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Generic {@code {"data": <resource>}} JSON:API payload wrapper. Package-private -- an internal
 * wire decoding helper, not part of this SDK's public model surface.
 */
final class JsonApiPayload<A> {

  private final JsonApiResource<A> data;
  private final LicenseFileClaims meta;

  @JsonCreator
  JsonApiPayload(
      @JsonProperty("data") JsonApiResource<A> data,
      @JsonProperty("meta") LicenseFileClaims meta) {
    this.data = data;
    this.meta = meta;
  }

  JsonApiResource<A> data() {
    return data;
  }

  /**
   * The claims that were covered by the signature. Present on format-v2 license AND machine files
   * -- the server builds both from one {@code LicenseFileClaims} struct -- and absent only on a
   * pre-v2 file, which is rejected.
   */
  LicenseFileClaims meta() {
    return meta;
  }
}
