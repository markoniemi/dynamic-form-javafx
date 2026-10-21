package com.example.desktop.json;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

/**
 * Builds the {@link ObjectMapper} every HTTP call uses.
 *
 * <p>The backend serializes with Jackson 3 and this client reads with Jackson 2; the shared DTOs in
 * {@code com.example.dynamicform.api} are the compatibility contract. Those DTOs are Lombok
 * {@code @Value} classes with no no-argument constructor, and Jackson 2 can still construct them
 * only because the api module sets {@code lombok.anyConstructor.addConstructorProperties}, which
 * emits {@code @java.beans.ConstructorProperties}. No mixins or extra modules are needed here.
 */
public final class JsonMapperFactory {

  private JsonMapperFactory() {}

  /**
   * A mapper that reads and writes the backend's wire format without altering it. No coercion and
   * no naming strategy: the DTO property names are already the wire names, and a submission value's
   * JSON type is meaningful, because nothing in the backend converts it.
   */
  public static ObjectMapper create() {
    return new ObjectMapper()
        // FormDataDto.submittedAt is a LocalDateTime; without this module it cannot be read at all
        .registerModule(new JavaTimeModule())
        // the backend sends an ISO-8601 string, not an epoch number
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
  }
}
