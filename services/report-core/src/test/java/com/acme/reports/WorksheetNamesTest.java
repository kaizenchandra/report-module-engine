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

class WorksheetNamesTest {
  @TempDir Path root;

  static List<String> names(byte[] bytes) throws Exception {
    try (var zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
      for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
        if (!entry.getName().equals("xl/workbook.xml")) continue;
        var nodes = TrustedTemplate.parse(zip.readAllBytes()).getElementsByTagNameNS("*", "sheet");
        var names = new ArrayList<String>();
        for (int i = 0; i < nodes.getLength(); i++)
          names.add(((org.w3c.dom.Element) nodes.item(i)).getAttribute("name"));
        return names;
      }
    }
    throw new AssertionError("Missing workbook");
  }

  @Test
  void workbookNamesAreExactUniqueAndBoundedForCombinedAndSplitPages() throws Exception {
    var renderer = new Renderer(root.toString());
    var rows = new ArrayList<Map<String, ?>>();
    for (int i = 0; i < 400; i++)
      rows.add(Map.of("product", "Product " + i, "quantity", i, "amount", BigDecimal.TEN));
    for (String name : List.of("Quarterly Sales", "A", "A".repeat(31), "A".repeat(29) + " 2")) {
      for (boolean split : List.of(false, true)) {
        var actual =
            names(
                renderer.renderConfigured(
                    null,
                    "Workbook names",
                    ReportFormat.XLSX,
                    rows,
                    "en-US",
                    "UTC",
                    new ReportRequest.OutputOptions(null, split, name)));
        assertThat(actual.getFirst()).isEqualTo(name);
        assertThat(actual).doesNotHaveDuplicates();
        if (split) {
          assertThat(actual.size()).isGreaterThan(9);
          for (int i = 1; i < actual.size(); i++) {
            String suffix = " (" + (i + 1) + ")";
            assertThat(actual.get(i))
                .isEqualTo(
                    name.substring(0, Math.min(name.length(), 31 - suffix.length())) + suffix);
          }
        } else assertThat(actual).hasSize(1);
        assertThat(actual).allMatch(n -> n.length() <= 31);
      }
    }
  }

  @Test
  void namesRoundTripInBothWireVersionsAndInvalidNamesTypesAndFormatsFail() throws Exception {
    var json = new ObjectMapper();
    try (var factory = Validation.buildDefaultValidatorFactory()) {
      var codec = new ConfigurationCodec(json, factory.getValidator());
      for (String version : List.of("1", "2")) {
        var request =
            new ReportRequest(
                version,
                "sales-summary",
                1,
                new ReportRequest.Parameters("Named workbook"),
                List.of("XLSX"),
                version.equals("1") ? "local-s3" : null,
                null,
                version.equals("2")
                    ? List.of(new ReportRequest.Delivery("local-s3", List.of("XLSX"), true))
                    : null,
                new ReportRequest.OutputOptions(null, false, "Sales Q4_2026-10"));
        byte[] wire = json.writeValueAsBytes(request);
        assertThat(codec.read(wire, false)).isEqualTo(request);
        var yaml = codec.convert(wire, false, "yaml");
        assertThat(codec.read(yaml.configuration().getBytes(StandardCharsets.UTF_8), true))
            .isEqualTo(request);
        for (String name :
            List.of(
                "",
                " ",
                " Leading",
                "Trailing ",
                "=formula",
                "'Quoted'",
                "A/B",
                "A\\B",
                "A:B",
                "A*B",
                "A?B",
                "[A]",
                "Sales\nQ4",
                "A".repeat(32),
                "History",
                "hIsToRy",
                "Résumé")) {
          var tree = json.readTree(wire);
          ((com.fasterxml.jackson.databind.node.ObjectNode) tree.path("outputOptions"))
              .put("xlsxSheetName", name);
          assertThatThrownBy(() -> codec.read(json.writeValueAsBytes(tree), false))
              .isInstanceOf(RuntimeException.class);
        }
        for (String value : List.of("null", "true", "12", "[]", "{}")) {
          String invalid =
              new String(wire, StandardCharsets.UTF_8).replace("\"Sales Q4_2026-10\"", value);
          assertThatThrownBy(() -> codec.read(invalid.getBytes(StandardCharsets.UTF_8), false))
              .isInstanceOf(RuntimeException.class);
        }
        String wrongFormat =
            new String(wire, StandardCharsets.UTF_8).replace("\"XLSX\"", "\"PDF\"");
        assertThatThrownBy(() -> codec.read(wrongFormat.getBytes(StandardCharsets.UTF_8), false))
            .isInstanceOf(RuntimeException.class);
      }
    }
  }

  @Test
  void omittedNamesKeepLegacyOptionSerialization() throws Exception {
    var json = new ObjectMapper();
    assertThat(json.writeValueAsString(new ReportRequest.OutputOptions("SEMICOLON", null)))
        .isEqualTo("{\"csvDelimiter\":\"SEMICOLON\"}");
    assertThat(json.writeValueAsString(new ReportRequest.OutputOptions(null, false)))
        .isEqualTo("{\"xlsxOnePagePerSheet\":false}");
  }
}
