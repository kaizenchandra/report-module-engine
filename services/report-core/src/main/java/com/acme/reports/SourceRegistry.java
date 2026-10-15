package com.acme.reports;

import com.fasterxml.jackson.databind.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
class SourceRegistry {
  private final JdbcTemplate db;
  private final ObjectMapper json;
  private final JdbcSalesAdapter jdbc;

  SourceRegistry(JdbcTemplate db, ObjectMapper json, JdbcSalesAdapter jdbc) {
    this.db = db;
    this.json = json;
    this.jdbc = jdbc;
  }

  record Registration(Map<String, Object> source, boolean created, UUID importId) {}

  @Transactional
  public Registration register(String name, JsonNode rows, String actor, String category)
      throws Exception {
    if (name == null
        || name.isBlank()
        || name.length() > 100
        || name.chars().anyMatch(Character::isISOControl))
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST,
          "Source name must contain 1–100 characters without control characters");
    String canonical = json.writeValueAsString(StructuredData.rows(rows));
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
            rows.size(),
            actor);
    if (created == 1)
      db.update(
          "INSERT INTO audit_event(actor,action,resource_id) VALUES (?,'DATA_SOURCE_REGISTERED',?)",
          actor,
          sha);
    UUID id = category == null ? null : UUID.randomUUID();
    if (category != null) {
      db.update(
          "INSERT INTO"
              + " source_import(id,source_sha256,profile_ref,adapter_version,category,created_by,mapping_json,mapping_sha256)"
              + " VALUES (?,?,'sales-jdbc',?,?,?,?,?)",
          id,
          sha,
          JdbcSalesAdapter.VERSION,
          category,
          actor,
          jdbc.mapping().canonical(),
          jdbc.mapping().sha256());
      db.update(
          "INSERT INTO audit_event(actor,action,resource_id) VALUES (?,'JDBC_SNAPSHOT_IMPORTED',?)",
          actor,
          id.toString());
    }
    return new Registration(
        db.queryForMap(
            "SELECT sha256,name,row_count,created_at FROM structured_source WHERE sha256=?", sha),
        created == 1,
        id);
  }
}
