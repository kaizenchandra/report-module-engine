package com.acme.reports;

import static org.assertj.core.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.w3c.dom.Element;

class TemplateNestedSubreportsTest {
  @TempDir Path root;

  static String parent() throws Exception {
    return Files.readString(Path.of("../../examples/nested-subreports.jrxml"));
  }

  static byte[] section() throws Exception {
    return Files.readAllBytes(Path.of("../../examples/nested-section.jrxml"));
  }

  static Map<String, byte[]> assets() throws Exception {
    return Map.of(
        "assets/nested-section.jrxml",
        section(),
        "assets/subreport-row.jrxml",
        TemplateSubreportsTest.child());
  }

  static byte[] repeat(byte[] source, int count, int height) throws Exception {
    var report = TrustedTemplate.parse(source);
    // Whitespace-independent selection of the detail band.
    var band = (Element) report.getElementsByTagName("band").item(0);
    var original = (Element) band.getElementsByTagName("element").item(0);
    original.setAttribute("height", String.valueOf(height));
    band.setAttribute("height", String.valueOf(count * height));
    for (int i = 1; i < count; i++) {
      var copy = (Element) original.cloneNode(true);
      copy.setAttribute("y", String.valueOf(i * height));
      band.appendChild(copy);
    }
    return TemplateAssets.xml(report);
  }

  @Test
  void nestedDefaultsAreIsolatedAndEveryFormatRendersGrandchildRows() throws Exception {
    var content = TemplateAssets.validate(parent().getBytes(StandardCharsets.UTF_8), assets());
    var renderer = new Renderer(root.toString());
    for (var format : ReportFormat.values()) {
      byte[] bytes = renderer.renderRegistered(content.renderSource(), "Nested title", format);
      assertThat(bytes).isNotEmpty();
      if (format == ReportFormat.PDF) {
        try (var pdf = Loader.loadPDF(bytes)) {
          String text = new PDFTextStripper().getText(pdf);
          assertThat(text)
              .contains("Child: Atlas notebook", "Child: Cedar pen", "Child: Harbor planner");
          assertThat(text.split("Child:", -1)).hasSize(4);
        }
      }
      if (format == ReportFormat.CSV)
        assertThat(new String(bytes, StandardCharsets.UTF_8))
            .contains("Child: 'Atlas notebook", "Child: 'Cedar pen");
    }
    var inherited = new HashMap<String, Object>();
    inherited.put("R_SUBREPORT_0", "wrong parent report");
    inherited.put("TITLE", "Keep title");
    inherited.put("P_LABEL", "Keep typed value");
    inherited.put(net.sf.jasperreports.engine.JRParameter.REPORT_LOCALE, Locale.GERMANY);
    var copied = TemplateSubreports.parameters(inherited);
    assertThat(copied)
        .doesNotContainKey("R_SUBREPORT_0")
        .containsEntry("TITLE", "Keep title")
        .containsEntry("P_LABEL", "Keep typed value")
        .containsEntry(net.sf.jasperreports.engine.JRParameter.REPORT_LOCALE, Locale.GERMANY);
    assertThat(inherited).containsKey("R_SUBREPORT_0");
  }

  @Test
  void grandchildReceivesTypedValuesLocaleZoneAndItsOwnAssets() throws Exception {
    String declarations =
        "<parameter name=\"P_LABEL\" class=\"java.lang.String\"/><parameter name=\"P_DAY\""
            + " class=\"java.util.Date\"/>";
    String parent =
        parent().replace("  <field name=\"product\"", declarations + "  <field name=\"product\"");
    String middle =
        new String(section(), StandardCharsets.UTF_8)
            .replace("  <field name=\"product\"", declarations + "  <field name=\"product\"");
    String leaf =
        new String(TemplateSubreportsTest.child(), StandardCharsets.UTF_8)
            .replace("  <parameter", "<template>\"assets/brand.jrtx\"</template>\n  <parameter")
            .replace("  <field name=\"product\"", declarations + "  <field name=\"product\"")
            .replace("width=\"270\" height=\"22\"", "width=\"270\" height=\"22\" style=\"Accent\"")
            .replace("\"Child: \" + $F{product}", "$P{P_LABEL} + \": \" + $F{product}")
            .replace(
                "$F{quantity}",
                "java.time.LocalDate.ofInstant($P{P_DAY}.toInstant(),"
                    + " $P{REPORT_TIME_ZONE}.toZoneId()).toString()")
            .replace(
                "</band>",
                "<element kind=\"image\" x=\"490\" y=\"0\" width=\"16\""
                    + " height=\"16\"><expression>\"assets/logo.png\"</expression></element></band>");
    var content =
        TemplateAssets.validate(
            parent.getBytes(StandardCharsets.UTF_8),
            Map.of(
                "assets/nested-section.jrxml", middle.getBytes(StandardCharsets.UTF_8),
                "assets/subreport-row.jrxml", leaf.getBytes(StandardCharsets.UTF_8),
                "assets/brand.jrtx",
                    new String(TemplateStylesTest.styles("#ff0000"), StandardCharsets.UTF_8)
                        .replace("fontSize=\"18\"", "fontSize=\"10\"")
                        .getBytes(StandardCharsets.UTF_8),
                "assets/logo.png", TemplateAssetsTest.png(16, 16, java.awt.Color.BLUE)));
    var factory = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance;
    var parameters =
        new ReportRequest.Parameters(
            "Nested typed values",
            "de-DE",
            "Europe/Berlin",
            Map.of(
                "P_LABEL", new ReportRequest.ParameterValue("TEXT", factory.textNode("Grandchild")),
                "P_DAY", new ReportRequest.ParameterValue("DATE", factory.textNode("2026-10-15"))));
    var rows =
        List.<Map<String, ?>>of(
            Map.of(
                "product",
                "Only row",
                "quantity",
                1,
                "amount",
                new java.math.BigDecimal("1234.50")));
    var renderer = new Renderer(root.toString());
    byte[] bytes =
        renderer.renderRequest(content.renderSource(), parameters, ReportFormat.PDF, rows, null);
    assertThat(TemplateStylesTest.redPixels(bytes)).isPositive();
    try (var pdf = Loader.loadPDF(bytes)) {
      assertThat(new PDFTextStripper().getText(pdf))
          .contains("Grandchild: Only row", "2026-10-15", "1.234,50");
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
    assertThat(
            new String(
                renderer.renderRequest(
                    content.renderSource(), parameters, ReportFormat.CSV, rows, null),
                StandardCharsets.UTF_8))
        .contains("'Grandchild: 'Only row");
  }

  @Test
  void exactFanoutLimitAllowsSharedGrandchildrenWithoutDuplicatingParentData() throws Exception {
    byte[] parent = repeat(parent().getBytes(StandardCharsets.UTF_8), 2, 75);
    byte[] section = repeat(section(), 3, 25);
    var content =
        TemplateAssets.validate(
            parent,
            Map.of(
                "assets/nested-section.jrxml",
                section,
                "assets/subreport-row.jrxml",
                TemplateSubreportsTest.child()));
    var rows =
        List.<Map<String, ?>>of(
            Map.of(
                "product", "Only row", "quantity", 1, "amount", new java.math.BigDecimal("2.00")));
    byte[] bytes =
        new Renderer(root.toString())
            .renderData(content.renderSource(), "Eight components", ReportFormat.PDF, rows);
    try (var pdf = Loader.loadPDF(bytes)) {
      String text = new PDFTextStripper().getText(pdf);
      assertThat(text.split("Child: Only row", -1)).hasSize(7);
      assertThat(text).doesNotContain("Atlas notebook");
    }
    byte[] excessive = repeat(parent().getBytes(StandardCharsets.UTF_8), 3, 50);
    byte[] twice = repeat(section(), 2, 25);
    assertThatThrownBy(
            () ->
                TemplateAssets.validate(
                    excessive,
                    Map.of(
                        "assets/nested-section.jrxml",
                        twice,
                        "assets/subreport-row.jrxml",
                        TemplateSubreportsTest.child())))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void cyclesDepthMissingAndIntermediateParameterMismatchFailClosed() throws Exception {
    byte[] source = parent().getBytes(StandardCharsets.UTF_8);
    byte[] cyclic =
        new String(section(), StandardCharsets.UTF_8)
            .replace("assets/subreport-row.jrxml", "assets/nested-section.jrxml")
            .getBytes(StandardCharsets.UTF_8);
    assertThatThrownBy(
            () -> TemplateAssets.validate(source, Map.of("assets/nested-section.jrxml", cyclic)))
        .isInstanceOf(IllegalArgumentException.class);
    // A -> B -> A cycle also fails even when every named file is present.
    assertThatThrownBy(
            () ->
                TemplateAssets.validate(
                    source,
                    Map.of(
                        "assets/nested-section.jrxml",
                        section(),
                        "assets/subreport-row.jrxml",
                        cyclic)))
        .isInstanceOf(IllegalArgumentException.class);
    byte[] deeper =
        new String(section(), StandardCharsets.UTF_8)
            .replace("assets/subreport-row.jrxml", "assets/deeper.jrxml")
            .getBytes(StandardCharsets.UTF_8);
    assertThatThrownBy(
            () ->
                TemplateAssets.validate(
                    source,
                    Map.of(
                        "assets/nested-section.jrxml",
                        section(),
                        "assets/subreport-row.jrxml",
                        deeper,
                        "assets/deeper.jrxml",
                        TemplateSubreportsTest.child())))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () -> TemplateAssets.validate(source, Map.of("assets/nested-section.jrxml", section())))
        .isInstanceOf(IllegalArgumentException.class);
    String declaration = "<parameter name=\"P_LABEL\" class=\"java.lang.String\"/>";
    byte[] parentParameter =
        parent()
            .replace("  <field name=\"product\"", declaration + "  <field name=\"product\"")
            .getBytes(StandardCharsets.UTF_8);
    byte[] leafParameter =
        new String(TemplateSubreportsTest.child(), StandardCharsets.UTF_8)
            .replace("  <field name=\"product\"", declaration + "  <field name=\"product\"")
            .getBytes(StandardCharsets.UTF_8);
    assertThatThrownBy(
            () ->
                TemplateAssets.validate(
                    parentParameter,
                    Map.of(
                        "assets/nested-section.jrxml",
                        section(),
                        "assets/subreport-row.jrxml",
                        leafParameter)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void nestedIdentityPinsAllDescendantsAndPreservesFlatDomain() throws Exception {
    var content = TemplateAssets.validate(parent().getBytes(StandardCharsets.UTF_8), assets());
    var changed = new TreeMap<>(assets());
    changed.put(
        "assets/subreport-row.jrxml",
        new String(TemplateSubreportsTest.child(), StandardCharsets.UTF_8)
            .replace("Child:", "Revised:")
            .getBytes(StandardCharsets.UTF_8));
    assertThat(content.fingerprint())
        .isNotEqualTo(TemplateAssets.validate(content.source(), changed).fingerprint());
    var flat =
        TemplateAssets.validate(
            TemplateSubreportsTest.parent().getBytes(StandardCharsets.UTF_8),
            Map.of("assets/subreport-row.jrxml", TemplateSubreportsTest.child()));
    String manifest =
        "template-subreport-assets-v3\n"
            + Renderer.sha256(flat.source())
            + "\nassets/subreport-row.jrxml:"
            + Renderer.sha256(TemplateSubreportsTest.child())
            + "\n";
    assertThat(flat.fingerprint())
        .isEqualTo(Renderer.sha256(manifest.getBytes(StandardCharsets.UTF_8)));
    assertThat(TemplateSubreports.nested(content.assets())).isTrue();
    assertThat(TemplateSubreports.nested(flat.assets())).isFalse();
  }
}
