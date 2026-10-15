package com.acme.reports;

import static org.assertj.core.api.Assertions.*;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TemplateDatasetSubreportsTest {
  @TempDir Path root;

  static byte[] parent() throws Exception {
    return Files.readAllBytes(Path.of("../../examples/dataset-subreport.jrxml"));
  }

  static List<Map<String, ?>> rows() {
    return List.of(
        Map.of("product", "=first", "quantity", 1, "amount", new BigDecimal("12.50")),
        Map.of("product", "Second", "quantity", 2, "amount", new BigDecimal("25.00")));
  }

  static String pdfText(byte[] bytes) throws Exception {
    try (var pdf = Loader.loadPDF(bytes)) {
      return new PDFTextStripper().getText(pdf);
    }
  }

  @Test
  void summaryUsesEntireAcceptedDatasetWithIndependentCursorsAndCsvProtection() throws Exception {
    String parent = new String(parent(), StandardCharsets.UTF_8);
    // Summary-only parents need not declare fields. Two sibling summaries must each read all rows.
    parent =
        parent
            .replaceAll("  <field[^>]+/>\\s*", "")
            .replace("<summary height=\"25\">", "<summary height=\"100\">");
    String element =
        parent.substring(
            parent.indexOf("    <element kind=\"subreport\""), parent.indexOf("  </summary>"));
    parent =
        parent.replace("  </summary>", element.replace("y=\"0\"", "y=\"50\"") + "  </summary>");
    var content =
        TemplateAssets.validate(
            parent.getBytes(StandardCharsets.UTF_8),
            Map.of("assets/subreport-row.jrxml", TemplateSubreportsTest.child()));
    var renderer = new Renderer(root.toString());
    for (var format : ReportFormat.values()) {
      byte[] bytes = renderer.renderData(content.renderSource(), "Dataset", format, rows());
      assertThat(bytes).isNotEmpty();
      if (format == ReportFormat.PDF) {
        String text = pdfText(bytes);
        assertThat(text.split("Child: =first", -1)).hasSize(3);
        assertThat(text.split("Child: Second", -1)).hasSize(3);
        assertThat(text).contains("12.50", "25.00").doesNotContain("Atlas notebook");
      }
      if (format == ReportFormat.CSV) {
        String text = new String(bytes, StandardCharsets.UTF_8);
        assertThat(text.split("Child: '=first", -1)).hasSize(3);
        assertThat(text.split("Child: 'Second", -1)).hasSize(3);
      }
    }
    assertThat(
            pdfText(
                renderer.renderData(content.renderSource(), "Empty", ReportFormat.PDF, List.of())))
        .doesNotContain("Child:", "Atlas notebook", "Second");
    assertThat(rows().get(0).get("product")).isEqualTo("=first");
  }

  @Test
  void nestedSummaryIsScopedToItsImmediateParentsRows() throws Exception {
    // A summary invoked by a detail report sees one row; the same summary invoked by a
    // dataset summary sees the entire dataset. Neither cursor is inherited or rewound.
    String middle = new String(parent(), StandardCharsets.UTF_8);
    for (boolean dataset : List.of(false, true)) {
      String outer =
          dataset ? new String(parent(), StandardCharsets.UTF_8) : TemplateSubreportsTest.parent();
      outer = outer.replace("assets/subreport-row.jrxml", "assets/middle.jrxml");
      var content =
          TemplateAssets.validate(
              outer.getBytes(StandardCharsets.UTF_8),
              Map.of(
                  "assets/middle.jrxml", middle.getBytes(StandardCharsets.UTF_8),
                  "assets/subreport-row.jrxml", TemplateSubreportsTest.child()));
      var renderer = new Renderer(root.resolve(Boolean.toString(dataset)).toString());
      String text =
          pdfText(renderer.renderData(content.renderSource(), "Nested", ReportFormat.PDF, rows()));
      assertThat(text.split("Child: =first", -1)).hasSize(2);
      assertThat(text.split("Child: Second", -1)).hasSize(2);
      assertThat(
              new String(
                  renderer.renderData(content.renderSource(), "Nested", ReportFormat.CSV, rows()),
                  StandardCharsets.UTF_8))
          .contains("Child: '=first", "Child: 'Second");
    }
  }

  @Test
  void bindingsRemainServerControlledAndFingerprintPinsDatasetSemantics() throws Exception {
    var assets = Map.of("assets/subreport-row.jrxml", TemplateSubreportsTest.child());
    var content = TemplateAssets.validate(parent(), assets);
    String manifest =
        "template-dataset-subreport-assets-v6\n"
            + Renderer.sha256(parent())
            + "\nassets/subreport-row.jrxml:"
            + Renderer.sha256(TemplateSubreportsTest.child())
            + "\n";
    assertThat(content.fingerprint())
        .isEqualTo(Renderer.sha256(manifest.getBytes(StandardCharsets.UTF_8)));
    String source = new String(parent(), StandardCharsets.UTF_8);
    for (String bad :
        List.of(
            source
                .replace("<summary height=\"25\">", "<title height=\"25\">")
                .replace("</summary>", "</title>"),
            source.replace(
                "</expression></element>",
                "</expression><dataSourceExpression>$P{REPORT_DATA_SOURCE}</dataSourceExpression></element>"),
            source.replace(
                "<parameter name=\"TITLE\"",
                "<parameter name=\"R_REPORT_ROWS\" class=\"java.util.Collection\"/><parameter"
                    + " name=\"TITLE\""))) {
      assertThatThrownBy(
              () -> TemplateAssets.validate(bad.getBytes(StandardCharsets.UTF_8), assets))
          .isInstanceOf(IllegalArgumentException.class);
    }
    assertThat(TemplateSubreports.datasetScoped(content.source(), content.assets())).isTrue();
    var inherited = new HashMap<String, Object>();
    inherited.put("R_REPORT_ROWS", rows());
    inherited.put("R_SUBREPORT_0", "parent compiled report");
    inherited.put("TITLE", "preserved");
    var child = TemplateSubreports.parameters(inherited, List.of(rows().get(0)));
    assertThat(child)
        .containsEntry("R_REPORT_ROWS", List.of(rows().get(0)))
        .containsEntry("TITLE", "preserved")
        .doesNotContainKey("R_SUBREPORT_0");
    assertThat(inherited.get("R_REPORT_ROWS")).isEqualTo(rows());
  }
}
