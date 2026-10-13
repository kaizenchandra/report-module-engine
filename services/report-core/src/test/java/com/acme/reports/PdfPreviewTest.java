package com.acme.reports;

import static org.assertj.core.api.Assertions.*;

import java.nio.file.*;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PdfPreviewTest {
  @TempDir Path root;

  @Test
  void limitsPagesDimensionsAndMalformedArtifacts() throws Exception {
    var preview = new PdfPreview();
    try (var document = new PDDocument()) {
      for (int i = 0; i < 21; i++) document.addPage(new PDPage(PDRectangle.A4));
      assertThat(preview.metadata(document).pageCount()).isEqualTo(21);
      assertThat(preview.metadata(document).previewPages()).isEqualTo(20);
      assertThat(preview.page(document, 19)).isNotEmpty();
      assertThatThrownBy(() -> preview.page(document, 20)).hasMessageContaining("400");
      document.getPage(0).setMediaBox(new PDRectangle(10000, 10000));
      assertThatThrownBy(() -> preview.page(document, 0)).hasMessageContaining("422");
    }
    var file = root.resolve("corrupt.pdf");
    byte[] bytes = "not a PDF".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    Files.write(file, bytes);
    assertThatThrownBy(
            () -> preview.read(file, Renderer.sha256(bytes), bytes.length, preview::metadata))
        .hasMessageContaining("422");
    assertThatThrownBy(() -> preview.read(file, "0".repeat(64), bytes.length, preview::metadata))
        .hasMessageContaining("409");
    var link = root.resolve("link.pdf");
    Files.createSymbolicLink(link, file);
    assertThatThrownBy(
            () -> preview.read(link, Renderer.sha256(bytes), bytes.length, preview::metadata))
        .hasMessageContaining("422");
  }
}
