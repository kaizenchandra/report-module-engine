package com.acme.reports;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import jakarta.validation.Validation;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TypedParameterTest {
  @TempDir Path root;

  static byte[] fixture() throws Exception {
    return Files.readAllBytes(Path.of("../../examples/typed-parameters.jrxml"));
  }

  static Map<String, ReportRequest.ParameterValue> values() {
    return Map.of(
        "P_NOTE", new ReportRequest.ParameterValue("TEXT", TextNode.valueOf("=SUM(1,2)")),
        "P_COUNT", new ReportRequest.ParameterValue("INTEGER", IntNode.valueOf(7)),
        "P_RATE", new ReportRequest.ParameterValue("DECIMAL", TextNode.valueOf("12.3400")),
        "P_APPROVED", new ReportRequest.ParameterValue("BOOLEAN", BooleanNode.FALSE),
        "P_AS_OF", new ReportRequest.ParameterValue("DATE", TextNode.valueOf("2024-03-10")));
  }

  @Test
  void declaredTypesRenderAsRealJavaValuesWithDateZoneAndCsvProtection() throws Exception {
    byte[] source = fixture();
    TrustedTemplate.validateValues(source, values());
    var renderer = new Renderer(root.toString());
    var parameters =
        new ReportRequest.Parameters("Typed report", "en-US", "America/New_York", values());
    try (var pdf =
        Loader.loadPDF(renderer.renderRequest(source, parameters, ReportFormat.PDF, null, null))) {
      assertThat(new PDFTextStripper().getText(pdf))
          .contains(
              "=SUM(1,2)",
              "Count=7:Integer",
              "Rate=12.3400:BigDecimal",
              "Approved=false:Boolean",
              "2024-03-10 00:00 -05:00");
    }
    String csv =
        new String(
            renderer.renderRequest(
                source,
                parameters,
                ReportFormat.CSV,
                null,
                new ReportRequest.OutputOptions("SEMICOLON", null)),
            StandardCharsets.UTF_8);
    assertThat(csv).contains("'=SUM(1,2)", "Rate=12.3400:BigDecimal", "\r\n");
    byte[] xlsx = renderer.renderRequest(source, parameters, ReportFormat.XLSX, null, null);
    try (var zip = new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(xlsx))) {
      for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
        String xml = new String(zip.readAllBytes(), StandardCharsets.UTF_8);
        if (entry.getName().equals("xl/sharedStrings.xml")) assertThat(xml).contains("=SUM(1,2)");
        if (entry.getName().startsWith("xl/worksheets/")) assertThat(xml).doesNotContain("<f>");
      }
    }
    var utc = new ReportRequest.Parameters("UTC date", null, "UTC", values());
    try (var pdf =
        Loader.loadPDF(renderer.renderRequest(source, utc, ReportFormat.PDF, null, null))) {
      assertThat(new PDFTextStripper().getText(pdf)).contains("2024-03-10 00:00 Z");
    }
  }

  @Test
  void valuesRoundTripCanonicallyWithoutChangingLegacyRequests() throws Exception {
    var json = new ObjectMapper();
    try (var factory = Validation.buildDefaultValidatorFactory()) {
      var codec = new ConfigurationCodec(json, factory.getValidator());
      var request =
          new ReportRequest(
              "1",
              "sales-summary",
              1,
              new ReportRequest.Parameters("Typed", null, null, values()),
              List.of("PDF"),
              "local-s3");
      byte[] bytes = json.writeValueAsBytes(request);
      var converted = codec.convert(bytes, false, "yaml");
      assertThat(codec.read(converted.configuration().getBytes(StandardCharsets.UTF_8), true))
          .isEqualTo(request);
      var reverse = new LinkedHashMap<String, ReportRequest.ParameterValue>();
      values().keySet().stream()
          .sorted(Comparator.reverseOrder())
          .forEach(k -> reverse.put(k, values().get(k)));
      assertThat(
              json.writeValueAsString(new ReportRequest.Parameters("Typed", null, null, reverse)))
          .isEqualTo(json.writeValueAsString(request.parameters()));
      assertThat(json.writeValueAsString(new ReportRequest.Parameters("Legacy")))
          .isEqualTo("{\"title\":\"Legacy\"}");
      for (String invalid :
          List.of(
              "null",
              "{}",
              "[]",
              "{\"TITLE\":{\"type\":\"TEXT\",\"value\":\"x\"}}",
              "{\"P_A\":null}",
              "{\"P_A\":{\"type\":\"BOOLEAN\",\"value\":\"true\"}}",
              "{\"P_A\":{\"type\":\"INTEGER\",\"value\":1.1}}",
              "{\"P_A\":{\"type\":\"INTEGER\",\"value\":1000001}}",
              "{\"P_A\":{\"type\":\"DECIMAL\",\"value\":1.2}}",
              "{\"P_A\":{\"type\":\"DECIMAL\",\"value\":\"1.00001\"}}",
              "{\"P_A\":{\"type\":\"DATE\",\"value\":\"2024-02-30\"}}",
              "{\"P_A\":{\"type\":\"DATE\",\"value\":\"1800-01-01\"}}",
              "{\"P_A\":{\"type\":\"TEXT\",\"value\":null}}",
              "{\"P_A\":{\"type\":\"TEXT\",\"value\":\"x\",\"unknown\":true}}")) {
        var tree = json.valueToTree(request);
        ((ObjectNode) tree.path("parameters")).set("values", json.readTree(invalid));
        assertThatThrownBy(() -> codec.read(json.writeValueAsBytes(tree), false))
            .isInstanceOf(RuntimeException.class);
      }
      for (String text : List.of("x".repeat(201), "new\nline", "control\u0085"))
        assertThat(new ReportRequest.ParameterValue("TEXT", TextNode.valueOf(text)).isValueValid())
            .isFalse();
    }
  }

  @Test
  void templateDeclarationsRejectReservedDuplicateUnsupportedOrExcessiveNames() throws Exception {
    String good = new String(fixture(), StandardCharsets.UTF_8);
    assertThat(TrustedTemplate.parameters(fixture())).hasSize(5);
    for (String bad :
        List.of(
            good.replace("P_NOTE", "REPORT_CONNECTION"),
            good.replace("P_NOTE", "TITLE"),
            good.replace("java.lang.Boolean", "java.lang.Object"),
            good.replace("P_NOTE", "p_note")))
      assertThatThrownBy(() -> TrustedTemplate.validate(bad))
          .isInstanceOf(IllegalArgumentException.class);
    String excess = "";
    for (int i = 0; i < 4; i++) excess += "<parameter name=\"P_EXTRA" + i + "\" class=\"java.lang.String\"/>";
    String excessive = good.replace("  <field name=\"product\"", excess + "  <field name=\"product\"");
    assertThatThrownBy(() -> TrustedTemplate.validate(excessive)).isInstanceOf(IllegalArgumentException.class);
    try (var factory = Validation.buildDefaultValidatorFactory()) {
      var excessValues = new HashMap<>(values());
      for (int i = 0; i < 4; i++) excessValues.put("P_EXTRA" + i,
          new ReportRequest.ParameterValue("TEXT", TextNode.valueOf("extra")));
      assertThat(factory.getValidator().validate(new ReportRequest.Parameters("Excess", null, null, excessValues)))
          .isNotEmpty();
    }
    assertThatThrownBy(() -> TrustedTemplate.validateValues(fixture(), Map.of()))
        .hasMessageContaining("exactly match");
    var wrong = new HashMap<>(values());
    wrong.put("P_COUNT", new ReportRequest.ParameterValue("TEXT", TextNode.valueOf("7")));
    assertThatThrownBy(() -> TrustedTemplate.validateValues(fixture(), wrong))
        .hasMessageContaining("exactly match");
    var extra = new HashMap<>(values());
    extra.put("P_EXTRA", new ReportRequest.ParameterValue("INTEGER", IntNode.valueOf(1)));
    assertThatThrownBy(() -> TrustedTemplate.validateValues(fixture(), extra))
        .hasMessageContaining("exactly match");
  }
}
