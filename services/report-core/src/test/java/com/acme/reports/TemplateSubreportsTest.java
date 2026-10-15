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

class TemplateSubreportsTest {
  @TempDir Path root;

  static String parent() throws Exception {
    return Files.readString(Path.of("../../examples/subreport-assets.jrxml"));
  }

  static byte[] child() throws Exception {
    return Files.readAllBytes(Path.of("../../examples/subreport-row.jrxml"));
  }

  @Test
  void childRendersEachCurrentRowAcrossAllFormatsAndColdCache() throws Exception {
    var content =
        TemplateAssets.validate(
            parent().getBytes(StandardCharsets.UTF_8),
            Map.of("assets/subreport-row.jrxml", child()));
    var renderer = new Renderer(root.toString());
    var rows =
        List.<Map<String, ?>>of(
            Map.of("product", "=danger", "quantity", 7, "amount", new BigDecimal("31.00")),
            Map.of("product", "Second row", "quantity", 9, "amount", new BigDecimal("45.00")));
    for (var format : ReportFormat.values()) {
      byte[] bytes = renderer.renderData(content.renderSource(), "Subreport title", format, rows);
      assertThat(bytes).isNotEmpty();
      if (format == ReportFormat.PDF) {
        try (var pdf = Loader.loadPDF(bytes)) {
          String text = new PDFTextStripper().getText(pdf);
          assertThat(text).contains("Child: =danger", "Child: Second row", "31.00", "45.00");
          assertThat(text.split("Child:", -1)).hasSize(3);
        }
      }
      if (format == ReportFormat.CSV)
        assertThat(new String(bytes, StandardCharsets.UTF_8))
            .contains("Child: '=danger", "Child: 'Second row");
    }
    var cold = new Renderer(root.resolve("cold").toString());
    try (var pdf =
        Loader.loadPDF(cold.renderRegistered(content.renderSource(), "Cold", ReportFormat.PDF))) {
      assertThat(new PDFTextStripper().getText(pdf))
          .contains("Child: Atlas notebook", "Child: Cedar pen", "Child: Harbor planner");
    }
  }

  @Test
  void childReceivesTypedParametersAndItsOwnPngAndStyleDependencies() throws Exception {
    String declaration = "<parameter name=\"P_LABEL\" class=\"java.lang.String\"/>";
    String parent =
        parent().replace("  <field name=\"product\"", declaration + "  <field name=\"product\"");
    String child =
        new String(child(), StandardCharsets.UTF_8)
            .replace("  <parameter", "  <template>\"assets/brand.jrtx\"</template>\n  <parameter")
            .replace("  <field name=\"product\"", declaration + "  <field name=\"product\"")
            .replace("width=\"270\" height=\"22\"", "width=\"270\" height=\"22\" style=\"Accent\"")
            .replace("\"Child: \" + $F{product}", "$P{P_LABEL} + \": \" + $F{product}")
            .replace(
                "</band>",
                "<element kind=\"image\" x=\"490\" y=\"0\" width=\"16\""
                    + " height=\"16\"><expression>\"assets/logo.png\"</expression></element></band>");
    var content =
        TemplateAssets.validate(
            parent.getBytes(StandardCharsets.UTF_8),
            Map.of(
                "assets/subreport-row.jrxml", child.getBytes(StandardCharsets.UTF_8),
                "assets/brand.jrtx",
                    new String(TemplateStylesTest.styles("#ff0000"), StandardCharsets.UTF_8)
                        .replace("fontSize=\"18\"", "fontSize=\"10\"")
                        .getBytes(StandardCharsets.UTF_8),
                "assets/logo.png", TemplateAssetsTest.png(16, 16, java.awt.Color.BLUE)));
    var json = new com.fasterxml.jackson.databind.ObjectMapper();
    var parameters =
        new ReportRequest.Parameters(
            "Typed child",
            "en-US",
            "UTC",
            Map.of(
                "P_LABEL",
                new ReportRequest.ParameterValue(
                    "TEXT", json.getNodeFactory().textNode("Passed parameter"))));
    byte[] bytes =
        new Renderer(root.toString())
            .renderRequest(content.renderSource(), parameters, ReportFormat.PDF, null, null);
    assertThat(TemplateStylesTest.redPixels(bytes)).isPositive();
    try (var pdf = Loader.loadPDF(bytes)) {
      assertThat(new PDFTextStripper().getText(pdf)).contains("Passed parameter: Atlas notebook");
      boolean found = false;
      for (var page : pdf.getPages())
        for (var name : page.getResources().getXObjectNames()) {
          var object = page.getResources().getXObject(name);
          if (object instanceof org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject image) {
            found = true;
            assertThat(image.getImage().getRGB(0, 0) & 0xffffff).isEqualTo(0x0000ff);
          }
        }
      assertThat(found).isTrue();
    }
  }

  @Test
  void cyclicMissingUnusedExternalAndCustomBindingsAreRejected() throws Exception {
    String parent = parent();
    byte[] child = child();
    assertThatThrownBy(() -> TemplateAssets.decode(parent, null))
        .isInstanceOf(IllegalArgumentException.class);
    for (String bad :
        List.of(
            parent.replace("assets/subreport-row.jrxml", "https://example.invalid/row.jasper"),
            parent.replace("assets/subreport-row.jrxml", "assets/../row.jrxml"),
            parent.replace(
                "</expression></element>",
                "</expression><connectionExpression>$P{REPORT_CONNECTION}</connectionExpression></element>"),
            parent
                .replace("kind=\"subreport\"", "kind=\"subreport\"")
                .replace("<detail><band", "<summary><band")
                .replace("</band></detail>", "</band></summary>"))) {
      assertThatThrownBy(
              () ->
                  TemplateAssets.validate(
                      bad.getBytes(StandardCharsets.UTF_8),
                      Map.of("assets/subreport-row.jrxml", child)))
          .isInstanceOf(IllegalArgumentException.class);
    }
    assertThatThrownBy(
            () ->
                TemplateAssets.validate(
                    parent.getBytes(StandardCharsets.UTF_8),
                    Map.of("assets/subreport-row.jrxml", parent.getBytes(StandardCharsets.UTF_8))))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                TemplateAssets.validate(
                    parent.getBytes(StandardCharsets.UTF_8),
                    Map.of("assets/subreport-row.jrxml", child, "assets/unused.jrxml", child)))
        .isInstanceOf(IllegalArgumentException.class);
    byte[] incompatible =
        new String(child, StandardCharsets.UTF_8)
            .replace(
                "<field name=\"product\"",
                "<parameter name=\"P_EXTRA\" class=\"java.lang.Integer\"/><field name=\"product\"")
            .getBytes(StandardCharsets.UTF_8);
    assertThatThrownBy(
            () ->
                TemplateAssets.validate(
                    parent.getBytes(StandardCharsets.UTF_8),
                    Map.of("assets/subreport-row.jrxml", incompatible)))
        .isInstanceOf(IllegalArgumentException.class);
    String element =
        "<element kind=\"subreport\" x=\"0\" y=\"0\" width=\"515\""
            + " height=\"25\"><expression><![CDATA[\"assets/subreport-row.jrxml\"]]></expression></element>";
    String tooMany = parent.replace(element, element.repeat(5));
    assertThat(tooMany).isNotEqualTo(parent);
    assertThatThrownBy(
            () ->
                TemplateAssets.validate(
                    tooMany.getBytes(StandardCharsets.UTF_8),
                    Map.of("assets/subreport-row.jrxml", child)))
        .isInstanceOf(IllegalArgumentException.class);
    byte[] invalidUtf8 = child.clone();
    invalidUtf8[invalidUtf8.length - 1] = (byte) 0xff;
    assertThatThrownBy(
            () ->
                TemplateAssets.validate(
                    parent.getBytes(StandardCharsets.UTF_8),
                    Map.of("assets/subreport-row.jrxml", invalidUtf8)))
        .isInstanceOf(IllegalArgumentException.class);
    var changed =
        TemplateAssets.validate(
            parent.getBytes(StandardCharsets.UTF_8),
            Map.of(
                "assets/subreport-row.jrxml",
                new String(child, StandardCharsets.UTF_8)
                    .replace("Child:", "Changed:")
                    .getBytes(StandardCharsets.UTF_8)));
    assertThat(changed.fingerprint())
        .isNotEqualTo(
            TemplateAssets.validate(
                    parent.getBytes(StandardCharsets.UTF_8),
                    Map.of("assets/subreport-row.jrxml", child))
                .fingerprint());
  }
}
