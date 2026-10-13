package com.acme.reports;

import java.io.*;
import java.nio.file.*;
import java.util.concurrent.Semaphore;
import javax.imageio.ImageIO;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
class PdfPreview {
  static final int MAX_BYTES = 10 * 1024 * 1024;
  static final int MAX_PAGES = 20;
  private final Semaphore permits = new Semaphore(2);

  record Metadata(int pageCount, int previewPages, int dpi) {}

  interface Operation<T> {
    T apply(PDDocument document) throws IOException;
  }

  <T> T read(Path path, String sha, long size, Operation<T> operation) {
    if (!permits.tryAcquire())
      throw new ResponseStatusException(
          HttpStatus.TOO_MANY_REQUESTS, "Preview capacity is busy; retry later");
    try {
      byte[] bytes;
      try (var input = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
        bytes = input.readNBytes(MAX_BYTES + 1);
      }
      if (bytes.length > MAX_BYTES || bytes.length != size || !Renderer.sha256(bytes).equals(sha))
        throw new ResponseStatusException(
            HttpStatus.CONFLICT, "Retained artifact integrity check failed");
      try (var document = Loader.loadPDF(bytes)) {
        if (document.isEncrypted() || document.getNumberOfPages() == 0)
          throw new ResponseStatusException(
              HttpStatus.UNPROCESSABLE_ENTITY, "PDF cannot be previewed");
        return operation.apply(document);
      }
    } catch (NoSuchFileException e) {
      throw new ResponseStatusException(HttpStatus.GONE, "Artifact no longer retained");
    } catch (IOException e) {
      throw new ResponseStatusException(
          HttpStatus.UNPROCESSABLE_ENTITY, "PDF preview unavailable; try downloading the original");
    } finally {
      permits.release();
    }
  }

  Metadata metadata(PDDocument document) {
    return new Metadata(
        document.getNumberOfPages(), Math.min(MAX_PAGES, document.getNumberOfPages()), 96);
  }

  byte[] page(PDDocument document, int index) throws IOException {
    if (index < 0 || index >= Math.min(MAX_PAGES, document.getNumberOfPages()))
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "Page is outside the available preview range");
    var box = document.getPage(index).getCropBox();
    double width = Math.ceil(box.getWidth() * 96.0 / 72),
        height = Math.ceil(box.getHeight() * 96.0 / 72);
    if (!Double.isFinite(width)
        || !Double.isFinite(height)
        || width < 1
        || height < 1
        || width > 2000
        || height > 2000
        || width * height > 2000000)
      throw new ResponseStatusException(
          HttpStatus.UNPROCESSABLE_ENTITY,
          "Page dimensions exceed preview limits; download the original");
    var renderer = new PDFRenderer(document);
    renderer.setSubsamplingAllowed(true);
    var image = renderer.renderImageWithDPI(index, 96);
    try (var output = new ByteArrayOutputStream()) {
      if (!ImageIO.write(image, "png", output) || output.size() > 4 * 1024 * 1024)
        throw new ResponseStatusException(
            HttpStatus.UNPROCESSABLE_ENTITY, "Preview image exceeds size limit");
      return output.toByteArray();
    } finally {
      image.flush();
    }
  }
}
