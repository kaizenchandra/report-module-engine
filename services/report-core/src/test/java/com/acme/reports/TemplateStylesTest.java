package com.acme.reports;

import static org.assertj.core.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TemplateStylesTest {
  @TempDir Path root;

  static String source() throws Exception {
    try (var in = TemplateStylesTest.class.getResourceAsStream("/templates/sales-summary.jrxml")) {
      return new String(in.readAllBytes(), StandardCharsets.UTF_8)
          .replace(
              "  <parameter",
              "  <template><![CDATA[\"assets/brand.jrtx\"]]></template>\n  <parameter")
          .replace("fontSize=\"22.0\" bold=\"true\"", "style=\"Accent\"");
    }
  }

  static byte[] styles(String color) {
    return ("<jasperTemplate><style name=\"Base\" fontName=\"SansSerif\" fontSize=\"18\""
                + " bold=\"true\"/><style name=\"Accent\" style=\"Base\" forecolor=\""
            + color
            + "\"/></jasperTemplate>")
        .getBytes(StandardCharsets.UTF_8);
  }

  static int redPixels(byte[] bytes) throws Exception {
    try (var pdf = Loader.loadPDF(bytes)) {
      var image = new PDFRenderer(pdf).renderImage(0, 0.75f);
      int count = 0;
      for (int x = 0; x < image.getWidth(); x++)
        for (int y = 0; y < image.getHeight(); y++) {
          int rgb = image.getRGB(x, y), r = (rgb >> 16) & 255, g = (rgb >> 8) & 255, b = rgb & 255;
          if (r > 150 && g < 100 && b < 100) count++;
        }
      return count;
    }
  }

  @Test
  void inheritedStylesRenderActualPdfColorAndHtmlWithoutExternalResolution() throws Exception {
    var content =
        TemplateAssets.validate(
            source().getBytes(StandardCharsets.UTF_8),
            Map.of("assets/brand.jrtx", styles("#ff0000")));
    var renderer = new Renderer(root.toString());
    assertThat(
            redPixels(
                renderer.renderRegistered(
                    content.renderSource(), "Styled heading", ReportFormat.PDF)))
        .isGreaterThan(50);
    assertThat(new String(content.renderSource(), StandardCharsets.UTF_8))
        .doesNotContain("<template>");
    String combinedSource =
        TemplateAssetsTest.source()
            .replace("  <parameter", "  <template>\"assets/brand.jrtx\"</template>\n  <parameter")
            .replace("fontSize=\"22.0\" bold=\"true\"", "style=\"Accent\"");
    var combined =
        TemplateAssets.validate(
            combinedSource.getBytes(StandardCharsets.UTF_8),
            Map.of(
                "assets/brand.jrtx",
                styles("#ff0000"),
                "assets/logo.png",
                TemplateAssetsTest.png(16, 16, java.awt.Color.BLUE)));
    byte[] mixed =
        renderer.renderRegistered(combined.renderSource(), "Style and PNG", ReportFormat.PDF);
    assertThat(redPixels(mixed)).isGreaterThan(50);
    try (var pdf = Loader.loadPDF(mixed)) {
      int found = 0;
      for (var page : pdf.getPages())
        for (var name : page.getResources().getXObjectNames()) {
          var value = page.getResources().getXObject(name);
          if (value instanceof org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject image) {
            found++;
            assertThat(image.getImage().getRGB(0, 0) & 0xffffff).isEqualTo(0x0000ff);
          }
        }
      assertThat(found).isPositive();
    }
    var cold = new Renderer(root.resolve("cold").toString());
    assertThat(
            redPixels(
                cold.renderRegistered(content.renderSource(), "Cold heading", ReportFormat.PDF)))
        .isGreaterThan(50);
    byte[] html =
        renderer.renderRegistered(content.renderSource(), "Styled heading", ReportFormat.HTML);
    try (var zip = new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(html))) {
      for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
        if (entry.getName().equals("index.html"))
          assertThat(new String(zip.readAllBytes(), StandardCharsets.UTF_8).toLowerCase())
              .contains("#ff0000");
      }
    }
  }

  @Test
  void fingerprintsIncludeExactStyleBytesAndPreserveExistingPngFingerprintDomain()
      throws Exception {
    var a =
        TemplateAssets.validate(
            source().getBytes(StandardCharsets.UTF_8),
            Map.of("assets/brand.jrtx", styles("#ff0000")));
    var b =
        TemplateAssets.validate(
            source().getBytes(StandardCharsets.UTF_8),
            Map.of("assets/brand.jrtx", styles("#0000ff")));
    assertThat(a.fingerprint()).isNotEqualTo(b.fingerprint());
    var png =
        TemplateAssets.validate(
            TemplateAssetsTest.source().getBytes(StandardCharsets.UTF_8),
            Map.of("assets/logo.png", TemplateAssetsTest.png(16, 16, java.awt.Color.RED)));
    String manifest =
        "template-png-assets-v1\n"
            + Renderer.sha256(png.source())
            + "\nassets/logo.png:"
            + Renderer.sha256(png.assets().get("assets/logo.png"))
            + "\n";
    assertThat(png.fingerprint())
        .isEqualTo(Renderer.sha256(manifest.getBytes(StandardCharsets.UTF_8)));
  }

  @Test
  void missingDuplicateCyclicExternalAndUnsupportedStylesFailClosed() throws Exception {
    String source = source();
    assertThatThrownBy(() -> TemplateAssets.decode(source, null))
        .isInstanceOf(IllegalArgumentException.class);
    for (String bad :
        List.of(
            "<jasperTemplate><style name=\"Accent\" style=\"Missing\"/></jasperTemplate>",
            "<jasperTemplate><style name=\"Accent\" style=\"Base\"/><style name=\"Base\""
                + " style=\"Accent\"/></jasperTemplate>",
            "<jasperTemplate><style name=\"Accent\"/><style name=\"Accent\"/></jasperTemplate>",
            "<jasperTemplate><style name=\"Accent\" fontName=\"ExternalFont\"/></jasperTemplate>",
            "<jasperTemplate><style name=\"Accent\" fontSize=\"NaN\"/></jasperTemplate>",
            "<jasperTemplate><style name=\"Accent\"><conditionalStyle/></style></jasperTemplate>",
            "<jasperTemplate><template>https://example.invalid/styles</template></jasperTemplate>",
            "<!DOCTYPE jasperTemplate SYSTEM 'http://127.0.0.1:9/never'><jasperTemplate><style"
                + " name=\"Accent\"/></jasperTemplate>")) {
      assertThatThrownBy(
              () ->
                  TemplateAssets.validate(
                      source.getBytes(StandardCharsets.UTF_8),
                      Map.of("assets/brand.jrtx", bad.getBytes(StandardCharsets.UTF_8))))
          .isInstanceOf(IllegalArgumentException.class);
    }
    String excessive =
        "<jasperTemplate>"
            + java.util.stream.IntStream.range(0, 9)
                .mapToObj(i -> "<style name=\"Style" + i + "\"/>")
                .collect(java.util.stream.Collectors.joining())
            + "</jasperTemplate>";
    assertThatThrownBy(() -> TemplateStyles.read(excessive.getBytes(StandardCharsets.UTF_8)))
        .isInstanceOf(IllegalArgumentException.class);
    String duplicate = source.replace("  <parameter", "  <style name=\"Accent\"/>\n  <parameter");
    assertThatThrownBy(
            () ->
                TemplateAssets.validate(
                    duplicate.getBytes(StandardCharsets.UTF_8),
                    Map.of("assets/brand.jrtx", styles("#ff0000"))))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                TrustedTemplate.validate(
                    source.replace("assets/brand.jrtx", "https://example.invalid/brand.jrtx")))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
