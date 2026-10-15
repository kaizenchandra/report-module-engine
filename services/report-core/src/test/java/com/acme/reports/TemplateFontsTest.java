package com.acme.reports;

import static org.assertj.core.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TemplateFontsTest {
  @TempDir Path root;

  static String source() throws Exception {
    return Files.readString(Path.of("../../examples/font-assets.jrxml"));
  }

  static byte[] font(boolean wide) throws Exception {
    return Files.readAllBytes(
        Path.of("../../examples/" + (wide ? "digits-wide.ttf" : "digits.ttf")));
  }

  static float embeddedWidth(byte[] bytes) throws Exception {
    try (var pdf = Loader.loadPDF(bytes)) {
      for (var page : pdf.getPages())
        for (var key : page.getResources().getFontNames()) {
          var font = page.getResources().getFont(key);
          if (font.getName().contains("ReportDigits")) {
            assertThat(font.isEmbedded()).isTrue();
            assertThat(font.getFontDescriptor().getFontFile2()).isNotNull();
            assertThat(new PDFTextStripper().getText(pdf)).contains("240.00", "90.00", "160.00");
            return font.getStringWidth("240.00");
          }
        }
    }
    throw new AssertionError("No embedded ReportDigits font");
  }

  @Test
  void fontsEmbedInPdfRemainIsolatedAndPackageExactHtmlResources() throws Exception {
    var a =
        TemplateAssets.validate(
            source().getBytes(StandardCharsets.UTF_8), Map.of("assets/digits.ttf", font(false)));
    var b =
        TemplateAssets.validate(
            source().getBytes(StandardCharsets.UTF_8), Map.of("assets/digits.ttf", font(true)));
    var renderer = new Renderer(root.toString());
    assertThat(
            embeddedWidth(
                renderer.renderRegistered(a.renderSource(), "Font example", ReportFormat.PDF)))
        .isEqualTo(3600);
    assertThat(
            embeddedWidth(
                renderer.renderRegistered(b.renderSource(), "Wide font", ReportFormat.PDF)))
        .isEqualTo(4500);
    assertThat(
            embeddedWidth(
                renderer.renderRegistered(a.renderSource(), "Original again", ReportFormat.PDF)))
        .isEqualTo(3600);
    String mixedSource =
        TemplateAssetsTest.source()
            .replace(
                "width=\"130\" height=\"22\" pattern=",
                "width=\"130\" height=\"22\" fontName=\"assets/digits.ttf\" pattern=");
    var mixed =
        TemplateAssets.validate(
            mixedSource.getBytes(StandardCharsets.UTF_8),
            Map.of(
                "assets/digits.ttf",
                font(false),
                "assets/logo.png",
                TemplateAssetsTest.png(16, 16, java.awt.Color.BLUE)));
    byte[] mixedPdf =
        renderer.renderRegistered(mixed.renderSource(), "Fonts and PNG", ReportFormat.PDF);
    assertThat(embeddedWidth(mixedPdf)).isEqualTo(3600);
    try (var pdf = Loader.loadPDF(mixedPdf)) {
      boolean found = false;
      for (var page : pdf.getPages())
        for (var key : page.getResources().getXObjectNames()) {
          var object = page.getResources().getXObject(key);
          if (object instanceof org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject image) {
            found = true;
            assertThat(image.getImage().getRGB(0, 0) & 0xffffff).isEqualTo(0x0000ff);
          }
        }
      assertThat(found).isTrue();
    }
    var files = new TreeMap<String, byte[]>();
    try (var zip =
        new java.util.zip.ZipInputStream(
            new java.io.ByteArrayInputStream(
                renderer.renderRegistered(a.renderSource(), "HTML fonts", ReportFormat.HTML)))) {
      for (var e = zip.getNextEntry(); e != null; e = zip.getNextEntry())
        files.put(e.getName(), zip.readAllBytes());
    }
    assertThat(new String(files.get("index.html"), StandardCharsets.UTF_8).toLowerCase(Locale.ROOT))
        .doesNotContain("<script");
    assertThat(files.keySet().stream().filter(n -> n.endsWith(".ttf")).toList()).hasSize(1);
    assertThat(
            files.entrySet().stream()
                .filter(e -> e.getKey().endsWith(".ttf"))
                .findFirst()
                .orElseThrow()
                .getValue())
        .isEqualTo(font(false));
    for (var format : List.of(ReportFormat.CSV, ReportFormat.XLSX, ReportFormat.DOCX)) {
      byte[] bytes = renderer.renderRegistered(a.renderSource(), "Font outputs", format);
      assertThat(bytes).isNotEmpty();
      if (format != ReportFormat.CSV) {
        var xml = new StringBuilder();
        try (var zip = new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(bytes))) {
          for (var e = zip.getNextEntry(); e != null; e = zip.getNextEntry())
            if (e.getName().endsWith(".xml"))
              xml.append(new String(zip.readAllBytes(), StandardCharsets.UTF_8));
        }
        assertThat(xml.toString()).contains("ReportDigits").doesNotContain("BundleFont_");
      }
    }
    for (var entry : files.entrySet())
      if (entry.getKey().endsWith(".css")) {
        String css = new String(entry.getValue(), StandardCharsets.UTF_8);
        var matcher = java.util.regex.Pattern.compile("url\\('([^']+)'\\)").matcher(css);
        int references = 0;
        while (matcher.find()) {
          references++;
          String target =
              Path.of(entry.getKey()).getParent().resolve(matcher.group(1)).normalize().toString();
          assertThat(files).containsKey(target);
        }
        assertThat(references).isPositive();
      }
    assertThat(a.fingerprint()).isNotEqualTo(b.fingerprint());
  }

  @Test
  void fontReferencesInGrandchildStylesUseTheSameIsolatedFillContext() throws Exception {
    String leaf =
        new String(TemplateSubreportsTest.child(), StandardCharsets.UTF_8)
            .replace("  <parameter", "<template>\"assets/digits.jrtx\"</template>\n  <parameter")
            .replace("width=\"130\" height=\"22\"", "width=\"130\" height=\"22\" style=\"Digits\"");
    var content =
        TemplateAssets.validate(
            TemplateNestedSubreportsTest.parent().getBytes(StandardCharsets.UTF_8),
            Map.of(
                "assets/nested-section.jrxml", TemplateNestedSubreportsTest.section(),
                "assets/subreport-row.jrxml", leaf.getBytes(StandardCharsets.UTF_8),
                "assets/digits.jrtx",
                    "<jasperTemplate><style name=\"Digits\" fontName=\"assets/digits.ttf\"/></jasperTemplate>"
                        .getBytes(StandardCharsets.UTF_8),
                "assets/digits.ttf", font(false)));
    assertThat(
            embeddedWidth(
                new Renderer(root.toString())
                    .renderRegistered(
                        content.renderSource(), "Nested styled font", ReportFormat.PDF)))
        .isEqualTo(3600);
  }

  @Test
  void invalidMissingUnusedAndUnsupportedFontResourcesFailClosed() throws Exception {
    assertThatThrownBy(() -> TemplateAssets.decode(source(), null))
        .isInstanceOf(IllegalArgumentException.class);
    for (byte[] bad : List.of(new byte[128], Arrays.copyOf(font(false), 140), new byte[8193]))
      assertThatThrownBy(
              () ->
                  TemplateAssets.validate(
                      source().getBytes(StandardCharsets.UTF_8), Map.of("assets/digits.ttf", bad)))
          .isInstanceOf(IllegalArgumentException.class);
    byte[] restricted = font(false).clone();
    var buffer = java.nio.ByteBuffer.wrap(restricted);
    for (int at = 12; at < 12 + Short.toUnsignedInt(buffer.getShort(4)) * 16; at += 16)
      if (buffer.getInt(at) == 0x4f532f32) buffer.putShort(buffer.getInt(at + 8) + 8, (short) 2);
    assertThat(
            java.awt.Font.createFont(
                java.awt.Font.TRUETYPE_FONT, new java.io.ByteArrayInputStream(restricted)))
        .isNotNull();
    assertThatThrownBy(() -> TemplateFonts.validate(restricted))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                TemplateAssets.validate(
                    TemplateSubreportsTest.parent()
                        .replace("assets/subreport-row.jrxml", "assets/digits.ttf")
                        .getBytes(StandardCharsets.UTF_8),
                    Map.of("assets/digits.ttf", font(false))))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                TemplateAssets.validate(
                    source()
                        .replace("fontName=\"assets/digits.ttf\"", "")
                        .getBytes(StandardCharsets.UTF_8),
                    Map.of("assets/digits.ttf", font(false))))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                TemplateAssets.decode(
                    source().replace("assets/digits.ttf", "https://example.invalid/font.ttf"),
                    null))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void missingGlyphsAreRejectedBeforeAnArtifactCanBeExported() throws Exception {
    String source =
        source()
            .replace("$F{amount}", "\"Unsupported letters\"")
            .replace("pattern=\"#,##0.00\"", "");
    var content =
        TemplateAssets.validate(
            source.getBytes(StandardCharsets.UTF_8), Map.of("assets/digits.ttf", font(false)));
    assertThatThrownBy(
            () ->
                new Renderer(root.toString())
                    .renderRegistered(content.renderSource(), "Missing glyph", ReportFormat.PDF))
        .hasMessageContaining("TEMPLATE_FONT_MISSING_GLYPH");
  }
}
