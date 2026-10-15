package com.acme.reports;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Validation;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CsvBomTest {
  @TempDir Path root;

  @Test
  void bomAddsExactlyThreeBytesWithoutChangingUnicodeQuotingOrFormulaProtection() throws Exception {
    var renderer = new Renderer(root.toString());
    var rows =
        List.<Map<String, ?>>of(
            Map.of(
                "product",
                "=Résumé;東京,\t\"quoted\"",
                "quantity",
                2,
                "amount",
                new BigDecimal("12.50")));
    for (String delimiter : List.of("COMMA", "SEMICOLON", "TAB")) {
      byte[] legacy =
          renderer.renderConfigured(
              null,
              "=Résumé;東京",
              ReportFormat.CSV,
              rows,
              "de-DE",
              "UTC",
              new ReportRequest.OutputOptions(delimiter, null));
      byte[] without =
          renderer.renderConfigured(
              null,
              "=Résumé;東京",
              ReportFormat.CSV,
              rows,
              "de-DE",
              "UTC",
              new ReportRequest.OutputOptions(delimiter, null, null, false));
      byte[] with =
          renderer.renderConfigured(
              null,
              "=Résumé;東京",
              ReportFormat.CSV,
              rows,
              "de-DE",
              "UTC",
              new ReportRequest.OutputOptions(delimiter, null, null, true));
      assertThat(without).isEqualTo(legacy);
      assertThat(with).startsWith((byte) 0xef, (byte) 0xbb, (byte) 0xbf).hasSize(legacy.length + 3);
      assertThat(Arrays.copyOfRange(with, 3, with.length)).isEqualTo(legacy);
      assertThat(new String(with, StandardCharsets.UTF_8))
          .contains("'=", "Résumé", "東京", "\"\"quoted\"\"", "\r\n");
    }
    assertThat(
            new String(
                renderer.renderConfigured(
                    null,
                    "BOM only",
                    ReportFormat.CSV,
                    rows,
                    "en-US",
                    "UTC",
                    new ReportRequest.OutputOptions(null, null, null, true)),
                StandardCharsets.UTF_8))
        .startsWith("\uFEFF")
        .contains("12.50");
  }

  @Test
  void booleanOnlyOptionsRoundTripInBothVersionsAndRejectTypesNullsAndWrongFormats()
      throws Exception {
    var json = new ObjectMapper();
    try (var factory = Validation.buildDefaultValidatorFactory()) {
      var codec = new ConfigurationCodec(json, factory.getValidator());
      for (String version : List.of("1", "2")) {
        for (boolean include : List.of(false, true)) {
          var request =
              new ReportRequest(
                  version,
                  "sales-summary",
                  1,
                  new ReportRequest.Parameters("CSV BOM"),
                  List.of("CSV"),
                  version.equals("1") ? "local-s3" : null,
                  null,
                  version.equals("2")
                      ? List.of(new ReportRequest.Delivery("local-s3", List.of("CSV"), true))
                      : null,
                  new ReportRequest.OutputOptions(null, null, null, include));
          byte[] wire = json.writeValueAsBytes(request);
          assertThat(codec.read(wire, false)).isEqualTo(request);
          var yaml = codec.convert(wire, false, "yaml");
          assertThat(codec.read(yaml.configuration().getBytes(StandardCharsets.UTF_8), true))
              .isEqualTo(request);
          for (String value : List.of("null", "\"true\"", "0", "1", "[]", "{}")) {
            String invalid =
                new String(wire, StandardCharsets.UTF_8)
                    .replace("\"csvIncludeBom\":" + include, "\"csvIncludeBom\":" + value);
            assertThatThrownBy(() -> codec.read(invalid.getBytes(StandardCharsets.UTF_8), false))
                .isInstanceOf(RuntimeException.class);
          }
          String incompatible =
              new String(wire, StandardCharsets.UTF_8).replace("\"CSV\"", "\"XLSX\"");
          assertThatThrownBy(() -> codec.read(incompatible.getBytes(StandardCharsets.UTF_8), false))
              .isInstanceOf(RuntimeException.class);
        }
      }
    }
  }

  @Test
  void omittedBomKeepsLegacyOptionsExactAndFalseRemainsExplicit() throws Exception {
    var json = new ObjectMapper();
    assertThat(json.writeValueAsString(new ReportRequest.OutputOptions("COMMA", false, "Sales")))
        .isEqualTo(
            "{\"csvDelimiter\":\"COMMA\",\"xlsxOnePagePerSheet\":false,\"xlsxSheetName\":\"Sales\"}");
    assertThat(json.writeValueAsString(new ReportRequest.OutputOptions(null, null, null, false)))
        .isEqualTo("{\"csvIncludeBom\":false}");
  }
}
