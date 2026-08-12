package sh.tamga.sdk.model;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

/**
 * Shared {@link ObjectMapper} configuration: {@code snake_case} wire keys mapped to
 * {@code camelCase} Java fields, ISO-8601 timestamps via {@link java.time.Instant}, and forward
 * compatibility with server-added fields ({@code FAIL_ON_UNKNOWN_PROPERTIES} disabled). Used by
 * both the offline checkout/proof file parsers and (eventually) {@code TamgaClient}'s response
 * mapping, so both paths decode identically -- see {@code Checkout/LicenseFile.java}/{@code
 * Checkout/MachineFile.java}.
 */
public final class TamgaJsonMapper {

  private static final ObjectMapper INSTANCE = build();

  private TamgaJsonMapper() {
  }

  /**
   * Returns the shared, immutable mapper instance -- {@link ObjectMapper} is thread-safe for
   * concurrent reads once configured, so this is safe to reuse across calls/threads.
   */
  public static ObjectMapper instance() {
    return INSTANCE;
  }

  private static ObjectMapper build() {
    ObjectMapper mapper = new ObjectMapper();
    mapper.setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
    mapper.registerModule(new JavaTimeModule());
    mapper.registerModule(new Jdk8Module());
    mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    return mapper;
  }
}
