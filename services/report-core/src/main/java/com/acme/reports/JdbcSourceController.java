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
  private final SourceRegistry registry;
  private final JdbcTemplate db;

  JdbcSourceController(JdbcSalesAdapter adapter, SourceRegistry registry, JdbcTemplate db) {
    this.adapter = adapter;
    this.registry = registry;
    this.db = db;
  }

  record ImportRequest(
      @NotBlank @Size(max = 100) @Pattern(regexp = "[^\\p{Cntrl}]+") String name,
      @NotBlank @Size(max = 40) @Pattern(regexp = "[a-zA-Z0-9][a-zA-Z0-9 _-]*") String category) {}

  @GetMapping
  Map<String, Object> profile() {
    return Map.of(
        "id", "sales-jdbc", "adapter", "postgres-sales-v1", "configured", adapter.configured());
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
      @Valid @RequestBody ImportRequest request, Authentication actor) throws Exception {
    // Close the external read transaction before opening the core persistence transaction.
    var rows = adapter.read(request.category(), false);
    var result = registry.register(request.name(), rows, actor.getName(), request.category());
    return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK)
        .body(result.source());
  }

  @GetMapping("/imports")
  List<Map<String, Object>> imports(@RequestParam(defaultValue = "0") int page) {
    if (page < 0 || page > 10000)
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid page");
    return db.queryForList(
        "SELECT id,source_sha256,profile_ref,adapter_version,category,created_at FROM source_import"
            + " ORDER BY created_at DESC,id DESC LIMIT 20 OFFSET ?",
        page * 20);
  }
}
