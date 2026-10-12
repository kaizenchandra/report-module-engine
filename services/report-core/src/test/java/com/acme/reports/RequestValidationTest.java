package com.acme.reports;

import static org.assertj.core.api.Assertions.*;

import jakarta.validation.Validation;
import java.util.*;
import org.junit.jupiter.api.Test;

class RequestValidationTest {
  @Test
  void rejectsNullOutputMalformedDestinationAndBlankTitle() {
    try (var factory = Validation.buildDefaultValidatorFactory()) {
      var validator = factory.getValidator();
      var valid =
          new ReportRequest(
              "1",
              "sales-summary",
              1,
              new ReportRequest.Parameters("Report"),
              List.of("PDF"),
              "local-gcs");
      assertThat(validator.validate(valid)).isEmpty();
      var invalid =
          new ReportRequest(
              "2",
              "https://unregistered",
              0,
              new ReportRequest.Parameters(" "),
              Arrays.asList((String) null),
              "https://unregistered");
      assertThat(validator.validate(invalid))
          .extracting(v -> v.getPropertyPath().toString())
          .contains(
              "definitionId",
              "definitionVersion",
              "parameters.title",
              "outputs[0].<list element>",
              "destinationRef",
              "mappingValid");
    }
  }
}
