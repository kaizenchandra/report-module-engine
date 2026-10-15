package com.acme.reports;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.*;
import net.sf.jasperreports.engine.*;
import net.sf.jasperreports.engine.export.*;
import net.sf.jasperreports.export.*;

/** A downloadable static package. Never serve its HTML on the application's origin. */
final class HtmlPackage {
  static final int MAX_BYTES = 10 * 1024 * 1024;
  static final int MAX_RESOURCES = 128;

  static byte[] export(JasperPrint print) throws JRException {
    return export(print, DefaultJasperReportsContext.getInstance());
  }

  static byte[] export(JasperPrint print, JasperReportsContext context) throws JRException {
    var resources = new Resources();
    var html = new ByteArrayOutputStream();
    var fontResources = new FontResources(resources);
    var exporter =
        new HtmlExporter(context) {
          @Override
          public void addFontFamily(HtmlFontFamily family) {
            if (fontsToProcess.putIfAbsent(family.getId(), family) == null)
              HtmlFontUtil.getInstance(context)
                  .handleHtmlFont(fontResources, fontResources, fontResources, family, true, true);
          }
        };
    var settings = new SimpleHtmlReportConfiguration();
    settings.setOverrideHints(true);
    settings.setIgnoreHyperlink(true);
    settings.setEmbedImage(false);
    settings.setConvertSvgToImage(true);
    var wrapper = new SimpleHtmlExporterConfiguration();
    wrapper.setOverrideHints(true);
    wrapper.setHtmlHeader(
        "<!doctype html><html lang=\"en\"><head><meta charset=\"UTF-8\"><meta"
            + " http-equiv=\"Content-Security-Policy\" content=\"default-src 'none'; img-src 'self'"
            + " data:; style-src 'self' 'unsafe-inline'; font-src 'self'; base-uri 'none';"
            + " form-action 'none'\"><meta name=\"viewport\" content=\"width=device-width,"
            + " initial-scale=1\"><title>Report</title></head><body>");
    wrapper.setHtmlFooter("</body></html>");
    var output =
        new SimpleHtmlExporterOutput(
            new OutputStream() {
              @Override
              public void write(int value) throws IOException {
                if (html.size() >= MAX_BYTES) throw new IOException("HTML size limit exceeded");
                html.write(value);
              }

              @Override
              public void write(byte[] bytes, int offset, int length) throws IOException {
                if (length > MAX_BYTES - html.size())
                  throw new IOException("HTML size limit exceeded");
                html.write(bytes, offset, length);
              }
            },
            "UTF-8");
    output.setImageHandler(resources);
    output.setFontHandler(fontResources);
    output.setResourceHandler(resources);
    exporter.setExporterInput(new SimpleExporterInput(print));
    exporter.setExporterOutput(output);
    exporter.setConfiguration(settings);
    exporter.setConfiguration(wrapper);
    try {
      exporter.exportReport();
      String document =
          html.toString(StandardCharsets.UTF_8)
              .replaceAll("(?s)<!--\\[if IE]>\\s*<script>.*?</script>\\s*<!\\[endif]-->", "");
      if (document.toLowerCase(Locale.ROOT).contains("<script"))
        throw new IOException("Executable HTML output rejected");
      return resources.pack(document.getBytes(StandardCharsets.UTF_8));
    } catch (IOException | IllegalArgumentException e) {
      throw new JRException("HTML_PACKAGE_FAILED", e);
    }
  }

  private record FontResources(Resources resources) implements HtmlResourceHandler {
    private String safe(String id) {
      if (id == null || (!id.endsWith(".css") && !id.endsWith(".ttf")))
        throw new IllegalArgumentException("Unsupported generated font resource");
      return "font-"
          + Renderer.sha256(id.getBytes(StandardCharsets.UTF_8))
          + (id.endsWith(".css") ? ".css" : ".ttf");
    }

    @Override
    public String getResourcePath(String id) {
      String path = resources.getResourcePath(safe(id));
      // Font URLs are inside a stylesheet in assets/, whereas its link is in index.html.
      return id.endsWith(".css") ? path : path.substring("assets/".length());
    }

    @Override
    public void handleResource(String id, byte[] bytes) {
      resources.handleResource(safe(id), bytes);
    }
  }

  static final class Resources implements HtmlResourceHandler {
    private final Map<String, byte[]> files = new TreeMap<>();
    private final Set<String> requested = new HashSet<>();
    private int size;

    private String path(String id) {
      if (id == null || !id.matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,127}") || id.contains(".."))
        throw new IllegalArgumentException("Unsafe generated resource name");
      return "assets/" + id;
    }

    @Override
    public String getResourcePath(String id) {
      String path = path(id);
      if (requested.size() >= MAX_RESOURCES && !requested.contains(path))
        throw new IllegalArgumentException("Too many HTML resources");
      requested.add(path);
      return path;
    }

    @Override
    public void handleResource(String id, byte[] bytes) {
      String path = path(id);
      if (bytes == null) throw new IllegalArgumentException("Missing resource bytes");
      if (files.containsKey(path)) {
        if (!Arrays.equals(files.get(path), bytes))
          throw new IllegalArgumentException("Conflicting resource content");
        return;
      }
      if (files.size() >= MAX_RESOURCES || bytes.length > MAX_BYTES - size)
        throw new IllegalArgumentException("HTML resource budget exceeded");
      files.put(path, bytes.clone());
      size += bytes.length;
    }

    byte[] pack(byte[] html) throws IOException {
      if (html.length > MAX_BYTES - size || !files.keySet().containsAll(requested))
        throw new IOException("HTML package incomplete or oversized");
      var bytes = new ByteArrayOutputStream();
      try (var zip = new ZipOutputStream(bytes, StandardCharsets.UTF_8)) {
        put(zip, "index.html", html);
        for (var file : files.entrySet()) put(zip, file.getKey(), file.getValue());
      }
      if (bytes.size() > MAX_BYTES) throw new IOException("HTML package size limit exceeded");
      return bytes.toByteArray();
    }

    private void put(ZipOutputStream zip, String name, byte[] bytes) throws IOException {
      var entry = new ZipEntry(name);
      entry.setTime(0); // Stable metadata; no local paths, permissions or host timestamps.
      zip.putNextEntry(entry);
      zip.write(bytes);
      zip.closeEntry();
    }
  }
}
