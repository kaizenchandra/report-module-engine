package com.acme.reports;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.*;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/jdbc-source")
@PreAuthorize("hasRole('REPORT_ADMIN')")
class JdbcSourceController {
  private final JdbcSalesAdapter adapter;
  private final JdbcImportService importer;
  private final JdbcTemplate db;

  JdbcSourceController(JdbcSalesAdapter adapter, JdbcImportService importer, JdbcTemplate db) {
    this.adapter = adapter;
    this.importer = importer;
    this.db = db;
  }

  record ImportRequest(
      @NotBlank @Size(max = 100) @Pattern(regexp = "[^\\p{Cntrl}]+") String name,
      @NotBlank @Size(max = 40) @Pattern(regexp = "[a-zA-Z0-9][a-zA-Z0-9 _-]*") String category) {}

  @GetMapping
  Map<String, Object> profile() {
    return Map.of(
        "id",
        "sales-jdbc",
        "adapter",
        JdbcSalesAdapter.VERSION,
        "configured",
        adapter.configured(),
        "mappingSha256",
        adapter.mapping().sha256());
  }

  @PostMapping("/check")
  Map<String, Object> check(Authentication actor, @RequestBody(required = false) byte[] body) {
    if (body != null && body.length != 0)
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "Connection check does not accept a body");
    adapter.read(null, true);
    db.update(
        "INSERT INTO audit_event(actor,action,resource_id) VALUES"
            + " (?,'JDBC_CONNECTION_CHECKED','sales-jdbc')",
        actor.getName());
    return Map.of("status", "AVAILABLE", "checkedAt", java.time.Instant.now().toString());
  }

  @PostMapping(value = "/imports", consumes = "application/json")
  ResponseEntity<Map<String, Object>> snapshot(
      @Valid @RequestBody ImportRequest request,
      Authentication actor,
      @RequestHeader(value = "Idempotency-Key", required = false) String key)
      throws Exception {
    var result = importer.snapshot(request.name(), request.category(), actor.getName(), key);
    var body = new LinkedHashMap<>(result.source());
    body.put("importId", result.importId());
    return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK)
        .header("Idempotency-Replayed", Boolean.toString(result.replayed()))
        .body(body);
  }

  @GetMapping("/imports")
  List<Map<String, Object>> imports(@RequestParam(defaultValue = "0") int page) {
    if (page < 0 || page > 10000)
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid page");
    return db.queryForList(
        "SELECT id,source_sha256,profile_ref,adapter_version,category,created_at,mapping_sha256"
            + " FROM source_import ORDER BY created_at DESC,id DESC LIMIT 20 OFFSET ?",
        page * 20);
  }
}
