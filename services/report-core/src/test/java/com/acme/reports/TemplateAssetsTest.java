package com.acme.reports;

import static org.assertj.core.api.Assertions.*;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.zip.ZipInputStream;
import javax.imageio.ImageIO;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TemplateAssetsTest {
  @TempDir Path root;

  static byte[] png(int width, int height, Color color) throws Exception {
    var image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
    for (int x = 0; x < width; x++)
      for (int y = 0; y < height; y++) image.setRGB(x, y, color.getRGB());
    var bytes = new ByteArrayOutputStream();
    ImageIO.write(image, "png", bytes);
    return bytes.toByteArray();
  }

  static String source() throws Exception {
    try (var stream =
        TemplateAssetsTest.class.getResourceAsStream("/templates/sales-summary.jrxml")) {
      return new String(stream.readAllBytes(), StandardCharsets.UTF_8)
          .replace(
              "  </title>",
              "    <element kind=\"image\" x=\"440\" y=\"0\" width=\"60\""
                  + " height=\"35\"><expression><![CDATA[\"assets/logo.png\"]]></expression></element>\n"
                  + "  </title>");
    }
  }

  static int redPixel(byte[] png) throws Exception {
    return ImageIO.read(new ByteArrayInputStream(png)).getRGB(0, 0) & 0xffffff;
  }

  @Test
  void closureRendersActualEmbeddedImagesAcrossExportersAndColdCache() throws Exception {
    byte[] image = png(16, 16, Color.RED);
    var content =
        TemplateAssets.decode(
            source(), Map.of("assets/logo.png", Base64.getEncoder().encodeToString(image)));
    assertThat(content.fingerprint()).isNotEqualTo(Renderer.sha256(content.source()));
    assertThat(content.renderSource()).isEqualTo(content.renderSource());
    var renderer = new Renderer(root.toString());
    try (var pdf =
        Loader.loadPDF(
            renderer.renderRegistered(content.renderSource(), "Image closure", ReportFormat.PDF))) {
      int count = 0;
      for (var page : pdf.getPages())
        for (var name : page.getResources().getXObjectNames()) {
          var object = page.getResources().getXObject(name);
          if (object instanceof PDImageXObject embedded) {
            count++;
            assertThat(embedded.getImage().getRGB(0, 0) & 0xffffff).isEqualTo(0xff0000);
          }
        }
      assertThat(count).isPositive();
    }
    for (var format : List.of(ReportFormat.XLSX, ReportFormat.DOCX, ReportFormat.HTML)) {
      byte[] bytes = renderer.renderRegistered(content.renderSource(), "Image closure", format);
      int count = 0;
      try (var zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
        for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
          byte[] data = zip.readAllBytes();
          if (entry.getName().endsWith(".png")) {
            count++;
            assertThat(redPixel(data)).isEqualTo(0xff0000);
          }
        }
      }
      assertThat(count).as(format.name()).isPositive();
    }
    var cold = new Renderer(root.resolve("cold").toString());
    assertThat(cold.renderRegistered(content.renderSource(), "Cold closure", ReportFormat.PDF))
        .startsWith("%PDF-".getBytes(StandardCharsets.US_ASCII));
    var entries = new TreeMap<String, byte[]>();
    try (var zip = new ZipInputStream(new ByteArrayInputStream(content.archive()))) {
      for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry())
        entries.put(entry.getName(), zip.readAllBytes());
    }
    assertThat(entries.keySet()).containsExactly("assets/logo.png", "report.jrxml");
    assertThat(entries.get("assets/logo.png")).isEqualTo(image);
    assertThat(entries.get("report.jrxml")).isEqualTo(content.source());
  }

  @Test
  void fingerprintPinsAssetBytesNamesAndSourceWithoutChangingStandaloneHashes() throws Exception {
    String source = source();
    var red =
        TemplateAssets.validate(
            source.getBytes(StandardCharsets.UTF_8),
            Map.of("assets/logo.png", png(16, 16, Color.RED)));
    var blue =
        TemplateAssets.validate(
            source.getBytes(StandardCharsets.UTF_8),
            Map.of("assets/logo.png", png(16, 16, Color.BLUE)));
    assertThat(red.fingerprint()).isNotEqualTo(blue.fingerprint());
    var renamed =
        TemplateAssets.validate(
            source.replace("logo.png", "other.png").getBytes(StandardCharsets.UTF_8),
            Map.of("assets/other.png", png(16, 16, Color.RED)));
    assertThat(red.fingerprint()).isNotEqualTo(renamed.fingerprint());
    String standalone = source.replaceAll("(?s)    <element kind=\"image\".*?</element>\\n", "");
    var legacy = TemplateAssets.decode(standalone, null);
    assertThat(legacy.fingerprint()).isEqualTo(Renderer.sha256(legacy.source()));
    assertThat(legacy.renderSource()).isEqualTo(legacy.source());
  }

  @Test
  void invalidClosurePathsExpressionsTypesDimensionsAndBudgetsFailClosed() throws Exception {
    String good = source();
    byte[] image = png(16, 16, Color.RED);
    for (String bad :
        List.of(
            "assets/../logo.png",
            "https://example.invalid/logo.png",
            "assets/logo.svg",
            "logo.png")) {
      assertThatThrownBy(
              () ->
                  TemplateAssets.validate(
                      good.replace("assets/logo.png", bad).getBytes(StandardCharsets.UTF_8),
                      Map.of(bad, image)))
          .isInstanceOf(IllegalArgumentException.class);
    }
    assertThatThrownBy(() -> TemplateAssets.decode(good, null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> TemplateAssets.decode(good, Map.of()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                TemplateAssets.validate(
                    good.getBytes(StandardCharsets.UTF_8),
                    Map.of("assets/logo.png", image, "assets/unused.png", image)))
        .isInstanceOf(IllegalArgumentException.class);
    for (String expression :
        List.of("$P{TITLE}", "new java.net.URL(\"https://example.invalid/image\")"))
      assertThatThrownBy(
              () -> TrustedTemplate.validate(good.replace("\"assets/logo.png\"", expression)))
          .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                TrustedTemplate.validate(
                    good.replace("kind=\"image\"", "kind=\"image\" lazy=\"true\"")))
        .isInstanceOf(IllegalArgumentException.class);
    for (byte[] bad :
        List.of(
            "<svg/>".getBytes(StandardCharsets.UTF_8),
            Arrays.copyOf(image, 20),
            png(513, 1, Color.RED),
            new byte[8193]))
      assertThatThrownBy(
              () ->
                  TemplateAssets.validate(
                      good.getBytes(StandardCharsets.UTF_8), Map.of("assets/logo.png", bad)))
          .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> TemplateAssets.decode(good, Map.of("assets/logo.png", "not-base64!")))
        .isInstanceOf(IllegalArgumentException.class);
    String encoded = Base64.getEncoder().encodeToString(image);
    assertThatThrownBy(() -> TemplateAssets.decode(good, Map.of("assets/logo.png", encoded + "\n")))
        .isInstanceOf(IllegalArgumentException.class);
    String element = "<element kind=\"image\" x=\"0\" y=\"0\" width=\"10\" height=\"10\"><expression>\"assets/logo.png\"</expression></element>";
    String repeated = good.replace("  </title>", element.repeat(8) + "  </title>");
    assertThatThrownBy(() -> TemplateAssets.validate(repeated.getBytes(StandardCharsets.UTF_8), Map.of("assets/logo.png", image)))
        .isInstanceOf(IllegalArgumentException.class);
    var noise = new BufferedImage(49, 49, BufferedImage.TYPE_INT_RGB);
    var random = new Random(123);
    for (int x = 0; x < 49; x++) for (int y = 0; y < 49; y++) noise.setRGB(x, y, random.nextInt(0x1000000));
    var noisy = new ByteArrayOutputStream();
    ImageIO.write(noise, "png", noisy);
    byte[] large = noisy.toByteArray();
    assertThat(large.length).isLessThanOrEqualTo(TemplateAssets.MAX_FILE_BYTES);
    assertThat(large.length * 3).isGreaterThan(TemplateAssets.MAX_TOTAL_BYTES);
    String three = good.replace("  </title>", element.replace("logo.png", "second.png")
        + element.replace("logo.png", "third.png") + "  </title>");
    assertThatThrownBy(() -> TemplateAssets.validate(three.getBytes(StandardCharsets.UTF_8),
        Map.of("assets/logo.png", large, "assets/second.png", large, "assets/third.png", large)))
        .isInstanceOf(IllegalArgumentException.class);
    var excess = new TreeMap<String, byte[]>();
    for (int i = 0; i < 5; i++) excess.put("assets/logo" + i + ".png", image);
    assertThatThrownBy(() -> TemplateAssets.validate(good.getBytes(StandardCharsets.UTF_8), excess))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
