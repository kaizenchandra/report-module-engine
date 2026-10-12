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
      @NotNull @AssertTrue Boolean trustedCodeAcknowledged) {}

  @GetMapping("/policy")
  Map<String, Object> policy() {
    return Map.of(
        "uploadsEnabled",
        uploadsEnabled,
        "maxSourceBytes",
        TrustedTemplate.MAX_BYTES,
        "compilerVersion",
        VERSION,
        "trustPolicy",
        "TRUSTED_ADMINISTRATORS_ONLY",
        "datasetVersion",
        "sample-v1");
  }

  @GetMapping
  List<Map<String, Object>> list(@RequestParam(defaultValue = "0") int page) {
    if (page < 0 || page > 10000)
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid page");
    return db.queryForList(
        "SELECT sha256,name,compiler_version,created_at FROM report_template ORDER BY created_at"
            + " DESC,sha256 LIMIT 20 OFFSET ?",
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
      byte[] bytes;
      try {
        bytes = TrustedTemplate.validate(request.jrxml());
        renderer.compileTrusted(bytes);
      } catch (IllegalArgumentException | net.sf.jasperreports.engine.JRException e) {
        throw new ResponseStatusException(
            HttpStatus.BAD_REQUEST,
            "JRXML validation or compilation failed. Use a standalone Java template with TITLE and"
                + " sample-v1 fields; external dependencies and custom fonts are unsupported.");
      }
      String sha = Renderer.sha256(bytes);
      int created =
          db.update(
              "INSERT INTO report_template(sha256,name,jrxml,compiler_version,created_by) VALUES"
                  + " (?,?,?,?,?) ON CONFLICT(sha256) DO NOTHING",
              sha,
              request.name(),
              request.jrxml(),
              VERSION,
              actor.getName());
      if (created == 1)
        db.update(
            "INSERT INTO audit_event(actor,action,resource_id) VALUES (?,'TEMPLATE_REGISTERED',?)",
            actor.getName(),
            sha);
      return ResponseEntity.status(created == 1 ? HttpStatus.CREATED : HttpStatus.OK)
          .body(
              db.queryForMap(
                  "SELECT sha256,name,compiler_version,created_at FROM report_template WHERE"
                      + " sha256=?",
                  sha));
    } finally {
      validationSlot.release();
    }
  }

  static byte[] source(JdbcTemplate db, String reference) {
    if (!reference.matches("sha256:[0-9a-f]{64}"))
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid template reference");
    var rows =
        db.queryForList("SELECT jrxml FROM report_template WHERE sha256=?", reference.substring(7));
    if (rows.isEmpty())
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Template version unavailable");
    byte[] bytes = rows.getFirst().get("jrxml").toString().getBytes(StandardCharsets.UTF_8);
    if (!Renderer.sha256(bytes).equals(reference.substring(7)))
      throw new IllegalStateException("TEMPLATE_INTEGRITY_FAILURE");
    return bytes;
  }

  static String resolve(JdbcTemplate db, Renderer renderer, String reference) {
    if (DefinitionController.TEMPLATE.equals(reference)) return renderer.templateHash();
    return Renderer.sha256(source(db, reference));
  }
}
