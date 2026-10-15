package com.acme.reports;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Validation;
import java.io.*;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.zip.ZipInputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OutputOptionsTest {
  @TempDir Path root;

  @Test
  void csvDelimitersQuoteEmbeddedSeparatorsAndKeepFormulaProtection() throws Exception {
    var renderer = new Renderer(root.toString());
    for (var delimiter : Map.of("COMMA", ",", "SEMICOLON", ";", "TAB", "\t").entrySet()) {
      String text = "=SUM(1" + delimiter.getValue() + "2)";
      String csv = new String(renderer.renderConfigured(null, text, ReportFormat.CSV,
          List.of(Map.of("product", text, "quantity", 1, "amount", new BigDecimal("240.00"))),
          "de-DE", "UTC", new ReportRequest.OutputOptions(delimiter.getKey(), null)),
          StandardCharsets.UTF_8);
      assertThat(csv).contains("\"'" + text + "\"", delimiter.getValue(), "\r\n", "240,00");
      assertThat(csv).doesNotContain("\n=");
    }
  }

  @Test
  void xlsxPageLayoutChangesActualWorksheetCountWithoutFormulas() throws Exception {
    var renderer = new Renderer(root.toString());
    var rows = new ArrayList<Map<String, ?>>();
    for (int i = 0; i < 100; i++)
      rows.add(Map.of("product", "Product " + i, "quantity", i, "amount", BigDecimal.TEN));
    int splitCount = 0;
    for (boolean split : List.of(false, true)) {
      byte[] bytes = renderer.renderConfigured(null, "=1+1", ReportFormat.XLSX, rows,
          "en-US", "UTC", new ReportRequest.OutputOptions(null, split));
      int count = 0;
      try (var zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
        for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
          String xml = new String(zip.readAllBytes(), StandardCharsets.UTF_8);
          if (entry.getName().matches("xl/worksheets/sheet[0-9]+\\.xml")) {
            count++;
            assertThat(xml).doesNotContain("<f>");
          }
          if (entry.getName().equals("xl/sharedStrings.xml"))
            assertThat(xml).contains("=1+1", "Product 99");
        }
      }
      if (split) splitCount = count;
      else assertThat(count).isEqualTo(1);
    }
    assertThat(splitCount).isGreaterThan(1);
  }

  @Test
  void strictOptionsRoundTripAndRejectNullsTypesUnknownFieldsAndWrongFormats() throws Exception {
    var json = new ObjectMapper();
    try (var factory = Validation.buildDefaultValidatorFactory()) {
      var codec = new ConfigurationCodec(json, factory.getValidator());
      String base = json.writeValueAsString(new ReportRequest("1", "sales-summary", 1,
          new ReportRequest.Parameters("Title"), List.of("CSV"), "local-s3"));
      assertThat(base).doesNotContain("outputOptions");
      String valid = base.substring(0, base.length() - 1) + ",\"outputOptions\":{\"csvDelimiter\":\"SEMICOLON\"}}";
      var request = codec.read(valid.getBytes(StandardCharsets.UTF_8), false);
      var yaml = codec.convert(valid.getBytes(StandardCharsets.UTF_8), false, "yaml");
      assertThat(codec.read(yaml.configuration().getBytes(StandardCharsets.UTF_8), true)).isEqualTo(request);
      for (String options : List.of("null", "{}", "[]", "{\"csvDelimiter\":null}",
          "{\"csvDelimiter\":\"PIPE\"}", "{\"csvDelimiter\":1}",
          "{\"xlsxOnePagePerSheet\":true}", "{\"xlsxOnePagePerSheet\":\"true\"}",
          "{\"xlsxOnePagePerSheet\":null}", "{\"escapeFormula\":false}")) {
        String invalid = base.substring(0, base.length() - 1) + ",\"outputOptions\":" + options + "}";
        assertThatThrownBy(() -> codec.read(invalid.getBytes(StandardCharsets.UTF_8), false))
            .isInstanceOf(RuntimeException.class);
      }
      assertThatThrownBy(() -> codec.read(valid.replace("[\"CSV\"]", "[\"PDF\"]").getBytes(StandardCharsets.UTF_8), false))
          .isInstanceOf(jakarta.validation.ConstraintViolationException.class);
    }
  }
}
