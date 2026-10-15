package com.acme.reports;

import java.io.*;
import java.math.BigDecimal;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import net.sf.jasperreports.engine.*;
import net.sf.jasperreports.engine.data.JRMapCollectionDataSource;
import net.sf.jasperreports.engine.export.JRCsvExporter;
import net.sf.jasperreports.engine.export.ooxml.JRDocxExporter;
import net.sf.jasperreports.engine.export.ooxml.JRXlsxExporter;
import net.sf.jasperreports.export.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class Renderer {
  private final Path root;
  private final byte[] template;
  private JasperReport compiled;
  private final LinkedHashMap<String, JasperReport> registered =
      new LinkedHashMap<>(16, 0.75f, true);

  public Renderer(@Value("${ARTIFACT_ROOT:.artifacts}") String root) throws IOException {
    this.root = Path.of(root).toAbsolutePath().normalize();
    Files.createDirectories(this.root);
    Files.setPosixFilePermissions(
        this.root, java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));
    try (var stream = getClass().getResourceAsStream("/templates/sales-summary.jrxml")) {
      if (stream == null) throw new IOException("Missing trusted template");
      template = stream.readAllBytes();
    }
  }

  static String sha256(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  byte[] bundledTemplate() {
    return template.clone();
  }

  String templateHash() {
    return sha256(template);
  }

  Path root() {
    return root;
  }

  Path path(String key) {
    if (!key.matches("[0-9a-f-]{36}(\\.[0-9]+)?\\.(pdf|xlsx|csv|docx|html\\.zip)"))
      throw new IllegalArgumentException("Invalid artifact key");
    return root.resolve(key);
  }

  public byte[] render(String title) throws JRException {
    return render(title, ReportFormat.PDF);
  }

  public synchronized byte[] render(String title, ReportFormat format) throws JRException {
    if (compiled == null)
      compiled = JasperCompileManager.compileReport(new ByteArrayInputStream(template));
    return export(compiled, title, format);
  }

  synchronized JasperReport compileTrusted(byte[] source) throws JRException {
    String hash = sha256(source);
    var value = registered.get(hash);
    if (value == null) {
      value = JasperCompileManager.compileReport(new ByteArrayInputStream(source));
      registered.put(hash, value);
      if (registered.size() > 16) registered.remove(registered.keySet().iterator().next());
    }
    return value;
  }

  public synchronized byte[] renderRegistered(byte[] source, String title, ReportFormat format)
      throws JRException {
    return export(compileTrusted(source), title, format);
  }

  private byte[] export(JasperReport report, String title, ReportFormat format) throws JRException {
    return export(report, title, format, sampleRows());
  }

  private Collection<Map<String, ?>> sampleRows() {
    return List.of(
        Map.of("product", "Atlas notebook", "quantity", 12, "amount", new BigDecimal("240.00")),
        Map.of("product", "Cedar pen", "quantity", 30, "amount", new BigDecimal("90.00")),
        Map.of("product", "Harbor planner", "quantity", 8, "amount", new BigDecimal("160.00")));
  }

  public synchronized byte[] renderConfigured(
      byte[] source,
      String title,
      ReportFormat format,
      Collection<Map<String, ?>> rows,
      String locale,
      String zone)
      throws JRException {
    return renderConfigured(source, title, format, rows, locale, zone, null);
  }

  public synchronized byte[] renderConfigured(
      byte[] source,
      String title,
      ReportFormat format,
      Collection<Map<String, ?>> rows,
      String locale,
      String zone,
      ReportRequest.OutputOptions options)
      throws JRException {
    if (source == null && compiled == null)
      compiled = JasperCompileManager.compileReport(new ByteArrayInputStream(template));
    return export(
        source == null ? compiled : compileTrusted(source),
        title,
        format,
        rows == null ? sampleRows() : rows,
        Locale.forLanguageTag(locale),
        TimeZone.getTimeZone(java.time.ZoneId.of(zone)),
        options);
  }

  public synchronized byte[] renderRequest(
      byte[] source,
      ReportRequest.Parameters parameters,
      ReportFormat format,
      Collection<Map<String, ?>> rows,
      ReportRequest.OutputOptions options)
      throws JRException {
    if (source == null && compiled == null)
      compiled = JasperCompileManager.compileReport(new ByteArrayInputStream(template));
    return export(
        source == null ? compiled : compileTrusted(source),
        parameters.title(),
        format,
        rows == null ? sampleRows() : rows,
        Locale.forLanguageTag(parameters.resolvedLocale()),
        TimeZone.getTimeZone(java.time.ZoneId.of(parameters.resolvedTimeZone())),
        options,
        parameters.values());
  }

  public synchronized byte[] renderData(
      byte[] source, String title, ReportFormat format, Collection<Map<String, ?>> rows)
      throws JRException {
    if (source == null && compiled == null)
      compiled = JasperCompileManager.compileReport(new ByteArrayInputStream(template));
    return export(source == null ? compiled : compileTrusted(source), title, format, rows);
  }

  private byte[] export(
      JasperReport report, String title, ReportFormat format, Collection<Map<String, ?>> rows)
      throws JRException {
    return export(report, title, format, rows, Locale.US, TimeZone.getTimeZone("UTC"));
  }

  private byte[] export(
      JasperReport report,
      String title,
      ReportFormat format,
      Collection<Map<String, ?>> rows,
      Locale locale,
      TimeZone zone)
      throws JRException {
    return export(report, title, format, rows, locale, zone, null);
  }

  private byte[] export(
      JasperReport report,
      String title,
      ReportFormat format,
      Collection<Map<String, ?>> rows,
      Locale locale,
      TimeZone zone,
      ReportRequest.OutputOptions options)
      throws JRException {
    return export(report, title, format, rows, locale, zone, options, null);
  }

  private byte[] export(
      JasperReport report,
      String title,
      ReportFormat format,
      Collection<Map<String, ?>> rows,
      Locale locale,
      TimeZone zone,
      ReportRequest.OutputOptions options,
      Map<String, ReportRequest.ParameterValue> values)
      throws JRException {
    if (format == ReportFormat.CSV) {
      var safe = new ArrayList<Map<String, ?>>();
      for (var row : rows) {
        var copy = new HashMap<String, Object>(row);
        copy.put("product", "'" + row.get("product"));
        safe.add(copy);
      }
      rows = safe;
    }
    var parameters = new HashMap<String, Object>();
    // Title is the only consumer-controlled text in this trusted fixture. Force CSV literal
    // text even when spreadsheet software ignores leading whitespace before a formula marker.
    parameters.put("TITLE", format == ReportFormat.CSV ? "'" + title : title);
    if (values != null)
      values.forEach(
          (name, value) ->
              parameters.put(
                  name, value.jasperValue(zone.toZoneId().getId(), format == ReportFormat.CSV)));
    parameters.put("R_REPORT_ROWS", rows);
    parameters.put(JRParameter.REPORT_LOCALE, locale);
    parameters.put(JRParameter.REPORT_TIME_ZONE, zone);
    var scope = TemplateFonts.scope(report);
    var print =
        JasperFillManager.getInstance(scope.context())
            .fill(report, parameters, new JRMapCollectionDataSource(rows));
    scope.check(print);
    if (format == ReportFormat.HTML) return HtmlPackage.export(print, scope.context());
    var bytes = new ByteArrayOutputStream();
    if (format == ReportFormat.PDF) {
      var exporter = new net.sf.jasperreports.pdf.JRPdfExporter(scope.context());
      exporter.setExporterInput(new SimpleExporterInput(print));
      exporter.setExporterOutput(new SimpleOutputStreamExporterOutput(bytes));
      exporter.exportReport();
    } else if (format == ReportFormat.CSV) {
      var exporter = new JRCsvExporter(scope.context());
      var settings = new SimpleCsvExporterConfiguration();
      settings.setEscapeFormula(true);
      settings.setOverrideHints(true);
      settings.setRecordDelimiter("\r\n");
      if (options != null && options.csvIncludeBom() != null)
        settings.setWriteBOM(options.csvIncludeBom());
      if (options != null && options.csvDelimiter() != null)
        settings.setFieldDelimiter(
            switch (options.csvDelimiter()) {
              case "SEMICOLON" -> ";";
              case "TAB" -> "\t";
              default -> ",";
            });
      exporter.setConfiguration(settings);
      exporter.setExporterInput(new SimpleExporterInput(print));
      exporter.setExporterOutput(new SimpleWriterExporterOutput(bytes, "UTF-8"));
      exporter.exportReport();
    } else if (format == ReportFormat.DOCX) {
      var exporter = new JRDocxExporter(scope.context());
      var settings = new SimpleDocxReportConfiguration();
      settings.setOverrideHints(true);
      settings.setIgnoreHyperlink(true);
      exporter.setConfiguration(settings);
      exporter.setExporterInput(new SimpleExporterInput(print));
      exporter.setExporterOutput(new SimpleOutputStreamExporterOutput(bytes));
      exporter.exportReport();
    } else if (format == ReportFormat.XLSX) {
      var exporter = new JRXlsxExporter(scope.context());
      var settings = new SimpleXlsxReportConfiguration();
      settings.setDetectCellType(true);
      if (options != null && options.xlsxOnePagePerSheet() != null)
        settings.setOnePagePerSheet(options.xlsxOnePagePerSheet());
      if (options != null && options.xlsxSheetName() != null) {
        String name = options.xlsxSheetName();
        String[] names = new String[Math.max(1, print.getPages().size())];
        for (int i = 0; i < names.length; i++) {
          String suffix = i == 0 ? "" : " (" + (i + 1) + ")";
          names[i] = name.substring(0, Math.min(name.length(), 31 - suffix.length())) + suffix;
        }
        settings.setSheetNames(names);
      }
      settings.setOverrideHints(true);
      exporter.setConfiguration(settings);
      exporter.setExporterInput(new SimpleExporterInput(print));
      exporter.setExporterOutput(new SimpleOutputStreamExporterOutput(bytes));
      exporter.exportReport();
    }
    if (bytes.size() == 0) throw new JRException("EMPTY_EXPORT");
    return bytes.toByteArray();
  }

  void persist(UUID id, long epoch, byte[] bytes) throws IOException {
    persist(id, epoch, bytes, ReportFormat.PDF);
  }

  void persist(UUID id, long epoch, byte[] bytes, ReportFormat format) throws IOException {
    if (bytes.length > 10 * 1024 * 1024) throw new IOException("Artifact size limit exceeded");
    Path temporary = Files.createTempFile(root, id.toString(), ".tmp");
    try {
      try (var channel = java.nio.channels.FileChannel.open(temporary, StandardOpenOption.WRITE)) {
        var buffer = java.nio.ByteBuffer.wrap(bytes);
        while (buffer.hasRemaining()) channel.write(buffer);
        channel.force(true);
      }
      Files.move(
          temporary,
          path(id + "." + epoch + "." + format.extension()),
          StandardCopyOption.ATOMIC_MOVE,
          StandardCopyOption.REPLACE_EXISTING);
    } finally {
      Files.deleteIfExists(temporary);
    }
  }
}
