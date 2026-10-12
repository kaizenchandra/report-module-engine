package com.acme.reports;

import static org.assertj.core.api.Assertions.*;

import java.nio.file.Path;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RendererTest {
  @TempDir Path temporary;

  @Test
  void generatesRealPdfWithExpectedContent() throws Exception {
    byte[] bytes = new Renderer(temporary.toString()).render("Quarterly review");
    assertThat(new String(bytes, 0, 5, java.nio.charset.StandardCharsets.US_ASCII))
        .isEqualTo("%PDF-");
    try (var pdf = Loader.loadPDF(bytes)) {
      assertThat(pdf.getNumberOfPages()).isEqualTo(1);
      assertThat(new PDFTextStripper().getText(pdf))
          .contains(
              "Quarterly review",
              "Atlas notebook",
              "Cedar pen",
              "Harbor planner",
              "240.00",
              "90.00",
              "160.00");
    }
  }

  @Test
  void csvContainsRowsAndNeutralizesConsumerFormulaText() throws Exception {
    var renderer = new Renderer(temporary.toString());
    for (String title : java.util.List.of("=1+1", "+1+1", "@SUM(1,2)", "  =1+1")) {
      String csv =
          new String(
              renderer.render(title, ReportFormat.CSV), java.nio.charset.StandardCharsets.UTF_8);
      assertThat(csv)
          .contains(
              "'" + title,
              "Atlas notebook",
              "Cedar pen",
              "Harbor planner",
              "240.00",
              "90.00",
              "160.00");
      assertThat(csv).contains("\r\n");
    }
  }

  @Test
  void xlsxIsARealWorkbookWithLiteralTitleAndNumericCells() throws Exception {
    byte[] bytes = new Renderer(temporary.toString()).render("=1+1", ReportFormat.XLSX);
    var entries = new java.util.HashMap<String, byte[]>();
    try (var zip = new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(bytes))) {
      for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry())
        entries.put(entry.getName(), zip.readAllBytes());
    }
    assertThat(entries)
        .containsKeys(
            "[Content_Types].xml",
            "xl/workbook.xml",
            "xl/sharedStrings.xml",
            "xl/worksheets/sheet1.xml");
    var factory = javax.xml.parsers.DocumentBuilderFactory.newInstance();
    factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
    factory.setAttribute(javax.xml.XMLConstants.ACCESS_EXTERNAL_DTD, "");
    factory.setAttribute(javax.xml.XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
    var parser = factory.newDocumentBuilder();
    var strings =
        parser.parse(new java.io.ByteArrayInputStream(entries.get("xl/sharedStrings.xml")));
    assertThat(strings.getDocumentElement().getTextContent())
        .contains("=1+1", "Atlas notebook", "Cedar pen", "Harbor planner");
    var sheet =
        parser.parse(new java.io.ByteArrayInputStream(entries.get("xl/worksheets/sheet1.xml")));
    assertThat(sheet.getElementsByTagName("f").getLength()).isZero();
    assertThat(
            new String(
                entries.get("xl/worksheets/sheet1.xml"), java.nio.charset.StandardCharsets.UTF_8))
        .contains("<v>240", "<v>30");
    assertThat(entries.keySet())
        .noneMatch(k -> k.contains("externalLink") || k.endsWith("vbaProject.bin"));
  }
}
