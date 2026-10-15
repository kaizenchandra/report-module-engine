package com.acme.reports;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

/** Reads JDBC outside the core transaction, then atomically publishes one keyed import result. */
@Service
class JdbcImportService {
  private final JdbcSalesAdapter adapter;
  private final SourceRegistry registry;
  private final JdbcTemplate db;
  private final ObjectMapper json;
  private final TransactionTemplate transaction;

  JdbcImportService(
      JdbcSalesAdapter adapter,
      SourceRegistry registry,
      JdbcTemplate db,
      ObjectMapper json,
      PlatformTransactionManager manager) {
    this.adapter = adapter;
    this.registry = registry;
    this.db = db;
    this.json = json;
    this.transaction = new TransactionTemplate(manager);
  }

  record Selector(String name, String category, String profile, String adapter) {}

  record Result(Map<String, Object> source, boolean created, boolean replayed, UUID importId) {}

  Result snapshot(String name, String category, String actor, String key) throws Exception {
    if (key != null && !key.matches("[a-zA-Z0-9_-]{8,128}"))
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid idempotency key");
    // This is the frozen request identity, not the current deployment adapter/mapping.
    // An unchanged key must replay its original result across deployment changes, offline.
    String fingerprint =
        Renderer.sha256(
            json.writeValueAsBytes(
                new Selector(name, category, "sales-jdbc", "postgres-sales-v1")));
    if (key != null) {
      var saved = saved(actor, key, fingerprint);
      if (saved != null) return saved;
    }
    // The source connection and read-only transaction close before core writes/locks begin.
    var rows = adapter.read(category, false);
    if (key == null) {
      var result = registry.register(name, rows, actor, category);
      return new Result(result.source(), result.created(), false, result.importId());
    }
    return transaction.execute(
        status -> {
          db.queryForList(
              "SELECT pg_advisory_xact_lock(hashtextextended(?,0))",
              "jdbc-import:" + actor + ":" + key);
          var saved = saved(actor, key, fingerprint);
          if (saved != null) return saved;
          try {
            var result = registry.register(name, rows, actor, category);
            db.update(
                "INSERT INTO jdbc_import_request(actor,idempotency_key,request_hash,import_id)"
                    + " VALUES (?,?,?,?)",
                actor,
                key,
                fingerprint,
                result.importId());
            return new Result(result.source(), result.created(), false, result.importId());
          } catch (RuntimeException e) {
            throw e;
          } catch (Exception e) {
            throw new IllegalStateException("JDBC snapshot persistence failed");
          }
        });
  }

  private Result saved(String actor, String key, String fingerprint) {
    var results =
        db.queryForList(
            "SELECT r.request_hash,r.import_id,s.sha256,s.name,s.row_count,s.created_at FROM"
                + " jdbc_import_request r JOIN source_import i ON i.id=r.import_id JOIN"
                + " structured_source s ON s.sha256=i.source_sha256 WHERE r.actor=? AND"
                + " r.idempotency_key=?",
            actor,
            key);
    if (results.isEmpty()) return null;
    var row = results.getFirst();
    if (!fingerprint.equals(row.get("request_hash")))
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "Idempotency key has a different JDBC import request");
    var source = new LinkedHashMap<String, Object>();
    for (String field : List.of("sha256", "name", "row_count", "created_at"))
      source.put(field, row.get(field));
    return new Result(source, false, true, (UUID) row.get("import_id"));
  }
}
