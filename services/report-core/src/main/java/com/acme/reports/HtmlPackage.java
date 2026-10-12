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
    var resources = new Resources();
    var html = new ByteArrayOutputStream();
    var exporter = new HtmlExporter();
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
            + " data:; style-src 'unsafe-inline'; font-src 'self'; base-uri 'none'; form-action"
            + " 'none'\"><meta name=\"viewport\" content=\"width=device-width,"
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
    output.setFontHandler(resources);
    output.setResourceHandler(resources);
    exporter.setExporterInput(new SimpleExporterInput(print));
    exporter.setExporterOutput(output);
    exporter.setConfiguration(settings);
    exporter.setConfiguration(wrapper);
    try {
      exporter.exportReport();
      return resources.pack(html.toByteArray());
    } catch (IOException | IllegalArgumentException e) {
      throw new JRException("HTML_PACKAGE_FAILED", e);
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
