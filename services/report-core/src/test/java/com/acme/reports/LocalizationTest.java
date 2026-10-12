package com.acme.reports;

import static org.assertj.core.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LocalizationTest {
  @TempDir Path root;

  @Test
  void localeFormatsRealNumbersAndTimeZoneUsesDstRules() throws Exception {
    var renderer = new Renderer(root.toString());
    String german =
        new String(
            renderer.renderConfigured(
                null, "Locale", ReportFormat.CSV, null, "de-DE", "Europe/Berlin"),
            StandardCharsets.UTF_8);
    assertThat(german).contains("240,00", "90,00");
    String source =
        new String(renderer.bundledTemplate(), StandardCharsets.UTF_8)
            .replace(
                "<expression><![CDATA[$P{TITLE}]]></expression>",
                "<expression><![CDATA[new java.util.Date(1710055800000L)]]></expression>")
            .replace("fontSize=\"22.0\"", "pattern=\"yyyy-MM-dd HH:mm XXX\" fontSize=\"12.0\"");
    byte[] bytes = TrustedTemplate.validate(source);
    String ny =
        new String(
            renderer.renderConfigured(
                bytes, "Date", ReportFormat.CSV, null, "en-US", "America/New_York"),
            StandardCharsets.UTF_8);
    String utc =
        new String(
            renderer.renderConfigured(bytes, "Date", ReportFormat.CSV, null, "en-US", "UTC"),
            StandardCharsets.UTF_8);
    assertThat(ny).contains("2024-03-10 03:30 -04:00");
    assertThat(utc).contains("2024-03-10 07:30 Z");
  }

  @Test
  void settingsAreBoundedAndLegacyParameterSerializationIsUnchanged() throws Exception {
    var json = new com.fasterxml.jackson.databind.ObjectMapper();
    assertThat(json.writeValueAsString(new ReportRequest.Parameters("Title")))
        .isEqualTo("{\"title\":\"Title\"}");
    try (var factory = jakarta.validation.Validation.buildDefaultValidatorFactory()) {
      var validator = factory.getValidator();
      assertThat(validator.validate(new ReportRequest.Parameters("Title", "de-DE", "Asia/Kolkata")))
          .isEmpty();
      assertThat(
              validator.validate(new ReportRequest.Parameters("Title", "unknown", "Unknown/Zone")))
          .hasSize(2);
    }
  }
}
