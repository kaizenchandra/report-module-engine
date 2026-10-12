package com.acme.reports;

import com.fasterxml.jackson.databind.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/data-sources")
class DataSourceController {
  private final JdbcTemplate db;
  private final ObjectMapper json;

  DataSourceController(JdbcTemplate db, ObjectMapper json) {
    this.db = db;
    this.json = StructuredData.parser(json);
  }

  @GetMapping
  List<Map<String, Object>> list(@RequestParam(defaultValue = "0") int page) {
    if (page < 0 || page > 10000)
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid page");
    return db.queryForList(
        "SELECT sha256,name,row_count,created_at FROM structured_source ORDER BY created_at"
            + " DESC,sha256 LIMIT 20 OFFSET ?",
        page * 20);
  }

  @PostMapping(consumes = "application/json")
  @PreAuthorize("hasRole('REPORT_ADMIN')")
  @Transactional
  ResponseEntity<Map<String, Object>> register(@RequestBody byte[] bytes, Authentication actor)
      throws Exception {
    JsonNode root;
    try {
      root =
          json.readTree(
              StandardCharsets.UTF_8
                  .newDecoder()
                  .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                  .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                  .decode(java.nio.ByteBuffer.wrap(bytes))
                  .toString());
    } catch (java.io.IOException e) {
      throw StructuredData.invalid();
    }
    if (root == null || !root.isObject() || root.size() != 2 || !root.path("name").isTextual())
      throw StructuredData.invalid();
    String name = root.path("name").asText();
    if (name.isBlank() || name.length() > 100 || name.chars().anyMatch(Character::isISOControl))
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST,
          "Source name must contain 1–100 characters without control characters");
    String canonical = json.writeValueAsString(StructuredData.rows(root.path("rows")));
    byte[] encoded = canonical.getBytes(StandardCharsets.UTF_8);
    if (encoded.length > 32768)
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "Structured source exceeds 32768 canonical UTF-8 bytes");
    String sha = Renderer.sha256(encoded);
    int created =
        db.update(
            "INSERT INTO structured_source(sha256,name,rows_json,row_count,created_by) VALUES"
                + " (?,?,?,?,?) ON CONFLICT(sha256) DO NOTHING",
            sha,
            name,
            canonical,
            root.path("rows").size(),
            actor.getName());
    if (created == 1)
      db.update(
          "INSERT INTO audit_event(actor,action,resource_id) VALUES (?,'DATA_SOURCE_REGISTERED',?)",
          actor.getName(),
          sha);
    return ResponseEntity.status(created == 1 ? HttpStatus.CREATED : HttpStatus.OK)
        .body(
            db.queryForMap(
                "SELECT sha256,name,row_count,created_at FROM structured_source WHERE sha256=?",
                sha));
  }
}
