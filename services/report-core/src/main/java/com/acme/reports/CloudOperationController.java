package com.acme.reports;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.net.URI;
import java.time.Instant;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1")
class CloudOperationController {
  private final JdbcTemplate db;
  private final JobService jobs;
  private final ObjectMapper json;
  private final boolean emulatorLinks;

  CloudOperationController(
      JdbcTemplate db,
      JobService jobs,
      ObjectMapper json,
      @Value("${REPORT_ALLOW_EMULATOR_LINKS:false}") boolean emulatorLinks) {
    this.db = db;
    this.jobs = jobs;
    this.json = json;
    this.emulatorLinks = emulatorLinks;
  }

  @PostMapping("/destinations/{id}/checks")
  @PreAuthorize("hasRole('REPORT_ADMIN')")
  @Transactional
  ResponseEntity<Map<String, Object>> check(Authentication actor, @PathVariable String id)
      throws Exception {
    var rows = db.queryForList("SELECT * FROM destination WHERE id=? FOR SHARE", id);
    if (rows.isEmpty())
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Destination not found");
    var d = rows.getFirst();
    return accept(
        actor.getName(),
        "CHECK",
        id,
        (Integer) d.get("version"),
        new LinkedHashMap<>(
            Map.of(
                "provider",
                d.get("provider"),
                "bucket",
                d.get("bucket"),
                "objectKey",
                "",
                "version",
                "",
                "sha256",
                "",
                "sizeBytes",
                0,
                "artifactId",
                "")));
  }

  @PostMapping("/jobs/{id}/download-links")
  @Transactional
  ResponseEntity<Map<String, Object>> sign(Authentication actor, @PathVariable UUID id)
      throws Exception {
    jobs.get(actor.getName(), id);
    var deliveries = db.queryForList("SELECT id FROM delivery_outbox WHERE job_id=?", id);
    if (deliveries.size() != 1)
      throw new ResponseStatusException(HttpStatus.CONFLICT, "Select a delivery by ID");
    return sign(actor, id, (UUID) deliveries.getFirst().get("id"));
  }

  @PostMapping("/jobs/{id}/deliveries/{deliveryId}/download-links")
  @Transactional
  ResponseEntity<Map<String, Object>> sign(
      Authentication actor, @PathVariable UUID id, @PathVariable UUID deliveryId) throws Exception {
    jobs.get(actor.getName(), id);
    var rows =
        db.queryForList(
            "SELECT d.provider,d.bucket,d.object_key,d.artifact_id,d.receipt->>'version' AS"
                + " version,a.sha256,a.size_bytes FROM delivery_outbox d JOIN report_artifact a ON"
                + " a.id=d.artifact_id WHERE d.job_id=? AND d.id=? AND d.status='VERIFIED'",
            id,
            deliveryId);
    if (rows.isEmpty())
      throw new ResponseStatusException(HttpStatus.CONFLICT, "A verified delivery is required");
    var d = rows.getFirst();
    return accept(
        actor.getName(),
        "SIGN",
        deliveryId.toString(),
        null,
        new LinkedHashMap<>(
            Map.of(
                "provider",
                d.get("provider"),
                "bucket",
                d.get("bucket"),
                "objectKey",
                d.get("object_key"),
                "version",
                d.get("version"),
                "sha256",
                d.get("sha256"),
                "sizeBytes",
                d.get("size_bytes"),
                "artifactId",
                d.get("artifact_id").toString())));
  }

  private ResponseEntity<Map<String, Object>> accept(
      String owner, String kind, String resource, Integer version, Map<String, Object> payload)
      throws Exception {
    db.queryForObject("SELECT pg_advisory_xact_lock(hashtextextended(?,19))", Object.class, owner);
    // Repeated clicks and lost acknowledgements recover an unexpired operation without signing
    // again.
    var existing =
        db.queryForList(
            "SELECT id FROM cloud_operation WHERE owner_subject=? AND kind=? AND resource_id=? AND"
                + " resource_version IS NOT DISTINCT FROM ? AND status IN ('PENDING','SUCCEEDED')"
                + " AND expires_at>now()+interval '15 seconds' ORDER BY created_at DESC LIMIT 1",
            owner,
            kind,
            resource,
            version);
    if (!existing.isEmpty()) return accepted((UUID) existing.getFirst().get("id"), owner);
    if (db.queryForObject(
            "SELECT count(*) FROM cloud_operation WHERE owner_subject=? AND"
                + " created_at>now()-interval '1 minute'",
            Integer.class,
            owner)
        >= 10)
      throw new ResponseStatusException(
          HttpStatus.TOO_MANY_REQUESTS, "Cloud operation rate limit reached");
    UUID id = UUID.randomUUID();
    Instant expires =
        Instant.now().plusSeconds(300).truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
    payload.put("id", id);
    payload.put("kind", kind);
    payload.put("expiresAt", expires.toString());
    db.update(
        "INSERT INTO"
            + " cloud_operation(id,owner_subject,kind,resource_id,resource_version,payload,expires_at)"
            + " VALUES (?,?,?,?,?,?::jsonb,?)",
        id,
        owner,
        kind,
        resource,
        version,
        json.writeValueAsString(payload),
        java.sql.Timestamp.from(expires));
    db.update(
        "INSERT INTO audit_event(actor,action,resource_id) VALUES (?,?,?)",
        owner,
        "CLOUD_" + kind + "_REQUESTED",
        resource);
    return accepted(id, owner);
  }

  private ResponseEntity<Map<String, Object>> accepted(UUID id, String owner) throws Exception {
    return ResponseEntity.accepted()
        .header("Cache-Control", "no-store")
        .header("Location", "/backend/api/v1/cloud-operations/" + id)
        .body(view(id, owner));
  }

  private Map<String, Object> view(UUID id, String owner) throws Exception {
    var rows =
        db.queryForList(
            "SELECT id,kind,resource_id,resource_version,status,result::text,expires_at,created_at"
                + " FROM cloud_operation WHERE id=? AND owner_subject=?",
            id,
            owner);
    if (rows.isEmpty())
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Operation not found");
    var row = rows.getFirst();
    var expires = ((java.sql.Timestamp) row.get("expires_at")).toInstant();
    row.put("emulated", emulatorLinks);
    row.put("url", "");
    row.put("code", "");
    if (!expires.isAfter(Instant.now())) {
      row.put("status", "EXPIRED");
      row.put("code", "EXPIRED");
    } else if (row.get("result") != null) {
      var result = json.readTree((String) row.get("result"));
      row.put("code", result.path("code").asText());
      row.put("url", result.path("url").asText());
    }
    row.remove("result");
    return row;
  }

  @GetMapping("/cloud-operations/{id}")
  ResponseEntity<Map<String, Object>> get(Authentication actor, @PathVariable UUID id)
      throws Exception {
    return ResponseEntity.ok().header("Cache-Control", "no-store").body(view(id, actor.getName()));
  }

  @GetMapping("/internal/deliveries/controls")
  @Transactional
  List<Object> pending() throws Exception {
    db.update(
        "UPDATE cloud_operation SET status='EXPIRED',result=NULL WHERE expires_at<=now() AND"
            + " status<>'EXPIRED'");
    var result = new ArrayList<Object>();
    for (var row :
        db.queryForList(
            "SELECT payload::text FROM cloud_operation WHERE status='PENDING' ORDER BY created_at"
                + " LIMIT 20")) result.add(json.readTree((String) row.get("payload")));
    return result;
  }

  record Result(
      @NotNull @Pattern(regexp = "SUCCEEDED|FAILED") String status,
      @NotNull
          @Pattern(
              regexp =
                  "CHECK_OK|SIGN_OK|PROVIDER_UNAVAILABLE|SIGNING_UNAVAILABLE|EXPIRED|DESTINATION_NOT_ALLOWED|OBJECT_CHANGED")
          String code,
      @NotNull @Size(max = 8192) String url) {}

  @PostMapping("/internal/deliveries/controls/{id}/result")
  @Transactional
  ResponseEntity<Void> result(@PathVariable UUID id, @Valid @RequestBody Result result)
      throws Exception {
    var rows =
        db.queryForList(
            "SELECT kind,status,result::text,expires_at FROM cloud_operation WHERE id=? FOR UPDATE",
            id);
    if (rows.isEmpty())
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Operation not found");
    var row = rows.getFirst();
    if (!((java.sql.Timestamp) row.get("expires_at")).toInstant().isAfter(Instant.now()))
      return ResponseEntity.noContent().build();
    boolean sign = row.get("kind").equals("SIGN"), success = result.status().equals("SUCCEEDED");
    if (success
        ? !result.code().equals(sign ? "SIGN_OK" : "CHECK_OK")
        : Set.of("SIGN_OK", "CHECK_OK").contains(result.code()))
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "Operation outcome is inconsistent");
    if (success && sign) {
      URI uri;
      try {
        uri = URI.create(result.url());
      } catch (IllegalArgumentException e) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid download URL");
      }
      boolean local =
          emulatorLinks
              && "http".equals(uri.getScheme())
              && Set.of("localhost", "127.0.0.1").contains(Objects.toString(uri.getHost(), ""));
      if ((!"https".equals(uri.getScheme()) && !local)
          || uri.getHost() == null
          || uri.getUserInfo() != null
          || uri.getFragment() != null)
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unsafe download URL");
    } else if (!result.url().isEmpty())
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unexpected download URL");
    if (!row.get("status").equals("PENDING")) {
      if (!json.readTree((String) row.get("result")).equals(json.valueToTree(result)))
        throw new ResponseStatusException(HttpStatus.CONFLICT, "Conflicting operation result");
      return ResponseEntity.noContent().build();
    }
    db.update(
        "UPDATE cloud_operation SET status=?,result=?::jsonb WHERE id=?",
        result.status(),
        json.writeValueAsString(result),
        id);
    db.update(
        "INSERT INTO audit_event(actor,action,resource_id) VALUES ('delivery-service',?,?)",
        "CLOUD_" + row.get("kind") + "_" + result.status(),
        id.toString());
    return ResponseEntity.noContent().build();
  }
}
