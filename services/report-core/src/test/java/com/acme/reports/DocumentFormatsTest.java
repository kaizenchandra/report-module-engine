package com.acme.reports;

import static org.assertj.core.api.Assertions.*;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.zip.ZipInputStream;
import net.sf.jasperreports.engine.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DocumentFormatsTest {
  @TempDir Path temporary;

  static Map<String, byte[]> entries(byte[] bytes) throws Exception {
    var result = new HashMap<String, byte[]>();
    try (var zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
      for (var e = zip.getNextEntry(); e != null; e = zip.getNextEntry()) {
        assertThat(e.getName()).doesNotStartWith("/").doesNotContain("..", "\\");
        assertThat(result.put(e.getName(), zip.readAllBytes())).isNull();
      }
    }
    return result;
  }

  @Test
  void docxContainsEscapedTextAndNoExternalRelationshipsOrMacros() throws Exception {
    var renderer = new Renderer(temporary.toString());
    var files =
        entries(renderer.render("<script>alert('text')</script> & review", ReportFormat.DOCX));
    assertThat(files).containsKeys("[Content_Types].xml", "_rels/.rels", "word/document.xml");
    var factory = javax.xml.parsers.DocumentBuilderFactory.newInstance();
    factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
    factory.setAttribute(javax.xml.XMLConstants.ACCESS_EXTERNAL_DTD, "");
    factory.setAttribute(javax.xml.XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
    var xml =
        factory
            .newDocumentBuilder()
            .parse(new ByteArrayInputStream(files.get("word/document.xml")));
    assertThat(xml.getDocumentElement().getTextContent())
        .contains(
            "<script>alert('text')</script> & review",
            "Atlas notebook",
            "Cedar pen",
            "Harbor planner",
            "240.00");
    assertThat(files.keySet())
        .noneMatch(k -> k.endsWith("vbaProject.bin") || k.startsWith("word/embeddings/"));
    for (var file : files.entrySet())
      if (file.getKey().endsWith(".rels"))
        assertThat(new String(file.getValue(), StandardCharsets.UTF_8))
            .doesNotContain("TargetMode=\"External\"");
  }

  @Test
  void htmlPackageEscapesConsumerMarkupAndUsesStaticRestrictiveDocument() throws Exception {
    var renderer = new Renderer(temporary.toString());
    var files = entries(renderer.render("<script>alert(1)</script> & report", ReportFormat.HTML));
    String html = new String(files.get("index.html"), StandardCharsets.UTF_8);
    assertThat(html)
        .contains(
            "Atlas notebook",
            "Cedar pen",
            "240.00",
            "&lt;script&gt;",
            "&amp;",
            "default-src 'none'",
            "charset=\"UTF-8\"");
    assertThat(html).doesNotContain("<script", "<iframe", "http://", "https://", "javascript:");
    assertThat(ReportFormat.HTML.extension()).isEqualTo("html.zip");
    assertThat(ReportFormat.HTML.mediaType).isEqualTo("application/zip");
  }

  @Test
  void htmlPackagesGeneratedImageAndEveryReferencedAsset() throws Exception {
    String jrxml =
        """
        <jasperReport name="ImageFixture" language="java" pageWidth="100" pageHeight="100" columnWidth="80" leftMargin="10" rightMargin="10" topMargin="10" bottomMargin="10">
          <parameter name="IMAGE" class="java.awt.Image"/>
          <detail><band height="30"><element kind="image" x="0" y="0" width="20" height="20"><expression><![CDATA[$P{IMAGE}]]></expression></element></band></detail>
        </jasperReport>
        """;
    var image = new java.awt.image.BufferedImage(2, 2, java.awt.image.BufferedImage.TYPE_INT_RGB);
    image.setRGB(0, 0, 0xff0000);
    var report =
        JasperCompileManager.compileReport(
            new ByteArrayInputStream(jrxml.getBytes(StandardCharsets.UTF_8)));
    var print =
        JasperFillManager.fillReport(
            report, new HashMap<>(Map.of("IMAGE", image)), new JREmptyDataSource(1));
    var files = entries(HtmlPackage.export(print));
    String html = new String(files.get("index.html"), StandardCharsets.UTF_8);
    var refs = java.util.regex.Pattern.compile("src=\"(assets/[^\"]+)\"").matcher(html);
    int count = 0;
    while (refs.find()) {
      count++;
      assertThat(files).containsKey(refs.group(1));
      assertThat(javax.imageio.ImageIO.read(new ByteArrayInputStream(files.get(refs.group(1)))))
          .isNotNull();
    }
    assertThat(count).isGreaterThan(0);
  }

  @Test
  void resourceHandlerRejectsTraversalMissingAssetsConflictsAndBudgetOverflow() throws Exception {
    var resources = new HtmlPackage.Resources();
    for (String name :
        List.of("../x", "/x", "a/b", "a\\b", "a%2fb", "x\" onerror=\"alert(1)", "..", ""))
      assertThatThrownBy(() -> resources.handleResource(name, new byte[] {1}))
          .isInstanceOf(IllegalArgumentException.class);
    resources.getResourcePath("missing.png");
    assertThatThrownBy(() -> resources.pack(new byte[0])).isInstanceOf(IOException.class);
    resources.handleResource("missing.png", new byte[] {1});
    assertThatThrownBy(() -> resources.handleResource("missing.png", new byte[] {2}))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> resources.handleResource("large.png", new byte[HtmlPackage.MAX_BYTES]))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> resources.pack(new byte[HtmlPackage.MAX_BYTES]))
        .isInstanceOf(IOException.class);
    var many = new HtmlPackage.Resources();
    for (int i = 0; i < HtmlPackage.MAX_RESOURCES; i++) many.getResourcePath("image" + i);
    assertThatThrownBy(() -> many.getResourcePath("overflow"))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
