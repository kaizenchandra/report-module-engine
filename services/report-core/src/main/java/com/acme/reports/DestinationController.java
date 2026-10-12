package com.acme.reports;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/destinations")
class DestinationController {
  private final JdbcTemplate db;

  private final Map<String, String> buckets;

  DestinationController(
      JdbcTemplate db,
      @Value("${S3_BUCKET:report-results}") String s3Bucket,
      @Value("${GCS_BUCKET:report-results}") String gcsBucket) {
    this.db = db;
    this.buckets = Map.of("S3", s3Bucket, "GCS", gcsBucket);
  }

  record Configuration(
      @NotNull @Pattern(regexp = "S3|GCS") String provider,
      @NotNull @Pattern(regexp = "[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]") String bucket,
      @NotNull @Size(max = 200) @Pattern(regexp = "([A-Za-z0-9_-]+/)*") String prefix,
      @NotNull Boolean enabled) {}

  record Create(
      @NotNull @Pattern(regexp = "[a-z][a-z0-9-]{2,63}") String id,
      @NotNull @Valid Configuration configuration) {}

  record Replace(@Min(1) int expectedVersion, @NotNull @Valid Configuration configuration) {}

  void allowed(Configuration c) {
    if (!Objects.equals(buckets.get(c.provider()), c.bucket()))
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "Bucket is not in the deployment allowlist");
  }

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  @PreAuthorize("hasRole('REPORT_ADMIN')")
  @Transactional
  Map<String, Object> create(@Valid @RequestBody Create request, Authentication actor) {
    var c = request.configuration();
    allowed(c);
    if (db.update(
            "INSERT INTO destination(id,provider,bucket,prefix,enabled) VALUES (?,?,?,?,?) ON"
                + " CONFLICT(id) DO NOTHING",
            request.id(),
            c.provider(),
            c.bucket(),
            c.prefix(),
            c.enabled())
        == 0) throw new ResponseStatusException(HttpStatus.CONFLICT, "Destination already exists");
    audit(actor, request.id());
    return db.queryForMap("SELECT * FROM destination WHERE id=?", request.id());
  }

  @PutMapping("/{id}")
  @PreAuthorize("hasRole('REPORT_ADMIN')")
  @Transactional
  Map<String, Object> replace(
      @PathVariable String id, @Valid @RequestBody Replace request, Authentication actor) {
    var c = request.configuration();
    allowed(c);
    if (db.update(
            "UPDATE destination SET"
                + " provider=?,bucket=?,prefix=?,enabled=?,version=version+1,updated_at=now() WHERE"
                + " id=? AND version=?",
            c.provider(),
            c.bucket(),
            c.prefix(),
            c.enabled(),
            id,
            request.expectedVersion())
        == 0)
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "Destination changed or no longer exists; reload before saving");
    audit(actor, id);
    return db.queryForMap("SELECT * FROM destination WHERE id=?", id);
  }

  @GetMapping("/{id}/versions")
  List<Map<String, Object>> versions(@PathVariable String id) {
    return db.queryForList(
        "SELECT * FROM destination_version WHERE destination_id=? ORDER BY version DESC LIMIT 100",
        id);
  }

  private void audit(Authentication actor, String id) {
    db.update(
        "INSERT INTO audit_event(actor,action,resource_id) VALUES (?,'DESTINATION_UPDATED',?)",
        actor.getName(),
        id);
  }

  @GetMapping
  List<Map<String, Object>> list() {
    return db.queryForList(
        "SELECT d.*, (SELECT max(o.updated_at) FROM delivery_outbox o WHERE o.destination_ref=d.id"
            + " AND o.status='VERIFIED') AS last_verified_delivery FROM destination d ORDER BY id");
  }

  record Update(@NotNull Boolean enabled) {}

  @PatchMapping("/{id}")
  @PreAuthorize("hasRole('REPORT_ADMIN')")
  @Transactional
  Map<String, Object> update(
      @PathVariable String id, @Valid @RequestBody Update request, Authentication actor) {
    if (db.update(
            "UPDATE destination SET enabled=?,version=version+1,updated_at=now() WHERE id=?",
            request.enabled(),
            id)
        == 0) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Destination not found");
    db.update(
        "INSERT INTO audit_event(actor,action,resource_id) VALUES (?,'DESTINATION_UPDATED',?)",
        actor.getName(),
        id);
    return db.queryForMap("SELECT * FROM destination WHERE id=?", id);
  }
}
