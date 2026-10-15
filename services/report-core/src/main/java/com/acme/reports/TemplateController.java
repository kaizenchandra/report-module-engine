package com.acme.reports;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.Semaphore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/templates")
class TemplateController {
  static final String VERSION = "7.0.3";
  private final JdbcTemplate db;
  private final Renderer renderer;
  private final boolean uploadsEnabled;
  private final Semaphore validationSlot = new Semaphore(1);

  TemplateController(
      JdbcTemplate db,
      Renderer renderer,
      @Value("${REPORT_TRUSTED_TEMPLATE_UPLOADS_ENABLED:false}") boolean enabled) {
    this.db = db;
    this.renderer = renderer;
    this.uploadsEnabled = enabled;
  }

  record Register(
      @NotBlank @Size(max = 100) @Pattern(regexp = "[^\\p{Cntrl}]+") String name,
      @NotBlank @Size(max = 32768) String jrxml,
      @NotNull @AssertTrue Boolean trustedCodeAcknowledged,
      @Size(min = 1, max = 4)
          Map<@Pattern(regexp = TemplateAssets.NAME) String, @NotNull @Size(max = 10924) String>
              assets) {}

  @GetMapping("/policy")
  Map<String, Object> policy() {
    var policy =
        new LinkedHashMap<String, Object>(
            Map.of(
                "maxImageElements",
                TemplateAssets.MAX_IMAGE_ELEMENTS,
                "maxAssetCount",
                TemplateAssets.MAX_COUNT,
                "maxAssetBytes",
                TemplateAssets.MAX_FILE_BYTES,
                "maxTotalAssetBytes",
                TemplateAssets.MAX_TOTAL_BYTES,
                "maxAssetDimension",
                TemplateAssets.MAX_DIMENSION,
                "uploadsEnabled",
                uploadsEnabled,
                "maxSourceBytes",
                TrustedTemplate.MAX_BYTES,
                "compilerVersion",
                VERSION,
                "trustPolicy",
                "TRUSTED_ADMINISTRATORS_ONLY",
                "datasetVersion",
                "sample-v1"));
    policy.put("maxSubreportDepth", TemplateSubreports.MAX_DEPTH);
    policy.put("maxSubreportsPerReport", TemplateSubreports.MAX_ELEMENTS);
    policy.put("maxExpandedSubreports", TemplateSubreports.MAX_EXPANDED_ELEMENTS);
    policy.put("maxFontGlyphs", 256);
    return policy;
  }

  @GetMapping
  List<Map<String, Object>> list(@RequestParam(defaultValue = "0") int page) {
    if (page < 0 || page > 10000)
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid page");
    return db.queryForList(
        "SELECT t.sha256,t.name,t.compiler_version,t.created_at, (SELECT count(*) FROM"
            + " report_template_asset a WHERE a.template_sha256=t.sha256) AS asset_count FROM"
            + " report_template t ORDER BY t.created_at DESC,t.sha256 LIMIT 20 OFFSET ?",
        page * 20);
  }

  @GetMapping("/sample")
  ResponseEntity<byte[]> sample() {
    return attachment(renderer.bundledTemplate(), "sales-summary.jrxml");
  }

  @GetMapping("/{sha}/source")
  @PreAuthorize("hasRole('REPORT_ADMIN')")
  ResponseEntity<byte[]> source(@PathVariable String sha) {
    return attachment(source(db, "sha256:" + sha), sha + ".jrxml");
  }

  private ResponseEntity<byte[]> attachment(byte[] bytes, String name) {
    return ResponseEntity.ok()
        .contentType(MediaType.TEXT_PLAIN)
        .header("Content-Disposition", "attachment; filename=" + name)
        .header("Cache-Control", "no-store")
        .header("X-Content-Type-Options", "nosniff")
        .body(bytes);
  }

  @PostMapping
  @PreAuthorize("hasRole('REPORT_ADMIN')")
  @Transactional
  ResponseEntity<Map<String, Object>> register(
      @Valid @RequestBody Register request, Authentication actor) {
    if (!uploadsEnabled)
      throw new ResponseStatusException(
          HttpStatus.FORBIDDEN, "Trusted template registration is disabled by deployment policy");
    if (!validationSlot.tryAcquire())
      throw new ResponseStatusException(
          HttpStatus.TOO_MANY_REQUESTS, "Template compiler busy; retry later");
    try {
      TemplateAssets.Content content;
      try {
        content = TemplateAssets.decode(request.jrxml(), request.assets());
        renderer.compileTrusted(content.renderSource());
      } catch (IllegalArgumentException | net.sf.jasperreports.engine.JRException e) {
        throw new ResponseStatusException(
            HttpStatus.BAD_REQUEST,
            "JRXML validation or compilation failed. Use a Java template with TITLE and up to eight"
                + " typed P_ parameters, sample-v1 fields and complete named PNG/JRTX/JRXML/TTF"
                + " assets and row-scoped subreports; other dependencies and font formats are"
                + " unsupported.");
      }
      String sha = content.fingerprint();
      int created =
          db.update(
              "INSERT INTO report_template(sha256,name,jrxml,compiler_version,created_by) VALUES"
                  + " (?,?,?,?,?) ON CONFLICT(sha256) DO NOTHING",
              sha,
              request.name(),
              request.jrxml(),
              VERSION,
              actor.getName());
      if (created == 1) {
        for (var asset : content.assets().entrySet())
          db.update(
              "INSERT INTO report_template_asset(template_sha256,name,sha256,content) VALUES"
                  + " (?,?,?,?)",
              sha,
              asset.getKey(),
              Renderer.sha256(asset.getValue()),
              asset.getValue());
        db.update(
            "INSERT INTO audit_event(actor,action,resource_id) VALUES (?,'TEMPLATE_REGISTERED',?)",
            actor.getName(),
            sha);
      }
      return ResponseEntity.status(created == 1 ? HttpStatus.CREATED : HttpStatus.OK)
          .body(
              db.queryForMap(
                  "SELECT t.sha256,t.name,t.compiler_version,t.created_at, (SELECT count(*) FROM"
                      + " report_template_asset a WHERE a.template_sha256=t.sha256) AS asset_count"
                      + " FROM report_template t WHERE t.sha256=?",
                  sha));
    } finally {
      validationSlot.release();
    }
  }

  @GetMapping("/{sha}/bundle")
  @PreAuthorize("hasRole('REPORT_ADMIN')")
  ResponseEntity<byte[]> bundle(@PathVariable String sha) {
    return ResponseEntity.ok()
        .contentType(MediaType.parseMediaType("application/zip"))
        .header("Content-Disposition", "attachment; filename=" + sha + ".zip")
        .header("Cache-Control", "no-store")
        .header("X-Content-Type-Options", "nosniff")
        .body(content(db, "sha256:" + sha).archive());
  }

  static byte[] source(JdbcTemplate db, String reference) {
    return content(db, reference).source();
  }

  static TemplateAssets.Content content(JdbcTemplate db, String reference) {
    if (!reference.matches("sha256:[0-9a-f]{64}"))
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid template reference");
    var rows =
        db.queryForList("SELECT jrxml FROM report_template WHERE sha256=?", reference.substring(7));
    if (rows.isEmpty())
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Template version unavailable");
    byte[] bytes = rows.getFirst().get("jrxml").toString().getBytes(StandardCharsets.UTF_8);
    var assets = new TreeMap<String, byte[]>();
    for (var row :
        db.queryForList(
            "SELECT name,sha256,content FROM report_template_asset WHERE template_sha256=? ORDER BY"
                + " name",
            reference.substring(7))) {
      byte[] asset = (byte[]) row.get("content");
      if (!Renderer.sha256(asset).equals(row.get("sha256")))
        throw new IllegalStateException("TEMPLATE_ASSET_INTEGRITY_FAILURE");
      assets.put(row.get("name").toString(), asset);
    }
    TemplateAssets.Content content;
    try {
      content = TemplateAssets.validate(bytes, assets);
    } catch (IllegalArgumentException e) {
      throw new IllegalStateException("TEMPLATE_INTEGRITY_FAILURE");
    }
    if (!content.fingerprint().equals(reference.substring(7)))
      throw new IllegalStateException("TEMPLATE_INTEGRITY_FAILURE");
    return content;
  }

  static String resolve(JdbcTemplate db, Renderer renderer, String reference) {
    if (DefinitionController.TEMPLATE.equals(reference)) return renderer.templateHash();
    return content(db, reference).fingerprint();
  }
}
