package com.example.desktop.json;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.example.dynamicform.api.FieldDto;
import com.example.dynamicform.api.FieldOptionDto;
import com.example.dynamicform.api.FormDataDto;
import com.example.dynamicform.api.FormDto;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Pins the wire format the client has to speak. The backend coerces nothing — a submission value is
 * stored as whatever JSON type arrives — so these shapes are the contract, not a preference.
 */
class WireFormatTest {

  private final ObjectMapper mapper = JsonMapperFactory.create();

  @Test
  void formDtoRoundTripsWithFieldsAndOptions() throws Exception {
    FormDto form =
        FormDto.builder()
            .id(1L)
            .formKey("feedback")
            .title("Product Feedback Form")
            .fields(
                List.of(
                    FieldDto.builder()
                        .name("features")
                        .label("Features")
                        .type("checkbox")
                        .required(false)
                        .options(List.of(new FieldOptionDto("feature1", "Feature 1")))
                        .build()))
            .build();

    assertEquals(form, mapper.readValue(mapper.writeValueAsString(form), FormDto.class));
  }

  @Test
  void formDataDtoDeserializesTheLocalDateTimeTheBackendSends() throws Exception {
    String json =
        """
        {"id":7,"formKey":"feedback","data":{"productName":"X"},
         "submittedAt":"2026-01-02T03:04:05","submittedBy":"admin"}""";

    FormDataDto dto = mapper.readValue(json, FormDataDto.class);

    assertEquals(LocalDateTime.of(2026, 1, 2, 3, 4, 5), dto.getSubmittedAt());
    assertEquals("admin", dto.getSubmittedBy());
  }

  @Test
  void submissionValuesKeepTheirJsonTypesInBothDirections() throws Exception {
    // Exactly the shapes the React client sends: a number as a string, a multi-option checkbox as
    // an array, a single-option one as false, a blank date as an empty string.
    String json =
        """
        {"productName":"X","purchaseDate":"2026-01-02","rating":"42",\
        "features":["feature1","feature3"],"newsletter":false,"blankDate":""}""";

    Map<String, Object> data = mapper.readValue(json, new TypeReference<Map<String, Object>>() {});

    assertEquals("42", data.get("rating"));
    assertEquals(List.of("feature1", "feature3"), data.get("features"));
    assertEquals(false, data.get("newsletter"));
    assertEquals("", data.get("blankDate"));
    assertEquals(json.replaceAll("\\s", ""), mapper.writeValueAsString(data).replaceAll("\\s", ""));
  }
}
