package com.acme.reports;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.*;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/internal/deliveries")
class DeliveryController {
  private final JdbcTemplate db;
  private final Renderer renderer;
  private final ObjectMapper json;
  private final JobOutcomes outcomes;

  DeliveryController(JdbcTemplate db, Renderer renderer, ObjectMapper json, JobOutcomes outcomes) {
    this.db = db;
    this.renderer = renderer;
    this.json = json;
    this.outcomes = outcomes;
  }

  @GetMapping
  List<Map<String, Object>> pending() {
    return db.queryForList(
        "SELECT"
            + " d.id,d.artifact_id,d.destination_ref,d.object_key,d.provider,d.bucket,a.sha256,a.size_bytes"
            + " FROM delivery_outbox d JOIN report_artifact a ON a.id=d.artifact_id WHERE"
            + " d.status='PENDING' ORDER BY d.created_at LIMIT 20");
  }

  @GetMapping("/{id}/artifact")
  ResponseEntity<FileSystemResource> artifact(@PathVariable UUID id) {
    if (db.queryForObject(
            "SELECT count(*) FROM delivery_outbox WHERE id=? AND status='PENDING'",
            Integer.class,
            id)
        == 0) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Task not available");
    String key =
        db.queryForObject(
            "SELECT a.storage_key FROM report_artifact a JOIN delivery_outbox d ON"
                + " d.artifact_id=a.id WHERE d.id=?",
            String.class,
            id);
    var file = new FileSystemResource(renderer.path(key));
    if (!file.exists())
      throw new ResponseStatusException(HttpStatus.GONE, "Retained artifact missing");
    return ResponseEntity.ok()
        .contentType(
            MediaType.parseMediaType(
                db.queryForObject(
                    "SELECT a.media_type FROM report_artifact a JOIN delivery_outbox d ON"
                        + " d.artifact_id=a.id WHERE d.id=?",
                    String.class,
                    id)))
        .header("Cache-Control", "no-store")
        .body(file);
  }

  record Attempt(
      @Min(1) @Max(6) int number,
      @NotNull @Pattern(regexp = "UPLOAD|RECONCILE") String phase,
      @NotNull @Pattern(regexp = "STARTED|RETRYING|VERIFIED|FAILED|INTERRUPTED") String status,
      @NotNull
          @Pattern(
              regexp =
                  "NONE|UPLOAD_RETRY|UPLOAD_UNVERIFIED|INTEGRITY_MISMATCH|CONFLICT|WORKER_INTERRUPTED")
          String code,
      @NotNull java.time.Instant startedAt,
      java.time.Instant endedAt) {}

  record Attempts(@NotNull @Size(max = 6) List<@NotNull @Valid Attempt> attempts) {}

  @PostMapping("/{id}/attempts")
  @Transactional
  ResponseEntity<Void> attempts(@PathVariable UUID id, @Valid @RequestBody Attempts request) {
    if (db.queryForList("SELECT id FROM delivery_outbox WHERE id=? FOR UPDATE", id).isEmpty())
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Delivery not found");
    Set<Integer> seen = new HashSet<>();
    for (var a : request.attempts()) {
      if (!seen.add(a.number())
          || (a.number() == 6) != a.phase().equals("RECONCILE")
          || a.status().equals("STARTED") != (a.endedAt() == null)
          || (a.endedAt() != null && a.endedAt().isBefore(a.startedAt())))
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid attempt timeline");
      db.update(
          "INSERT INTO delivery_attempt(delivery_id,number,phase,status,code,started_at,ended_at)"
              + " VALUES (?,?,?,?,?,?,?) ON CONFLICT(delivery_id,number) DO UPDATE SET"
              + " status=excluded.status,code=excluded.code,ended_at=excluded.ended_at WHERE"
              + " delivery_attempt.status='STARTED' AND"
              + " delivery_attempt.started_at=excluded.started_at",
          id,
          a.number(),
          a.phase(),
          a.status(),
          a.code(),
          java.sql.Timestamp.from(a.startedAt()),
          a.endedAt() == null ? null : java.sql.Timestamp.from(a.endedAt()));
    }
    return ResponseEntity.noContent().build();
  }

  record Receipt(
      @NotNull @Pattern(regexp = "VERIFIED|FAILED") String status,
      @NotNull @Pattern(regexp = "[a-f0-9]{64}") String sha256,
      @NotNull @Size(max = 1024) String version,
      @NotNull
          @Pattern(
              regexp =
                  "NONE|UPLOAD_FAILED|UPLOAD_UNVERIFIED|INTEGRITY_MISMATCH|CONFLICT|ARTIFACT_UNAVAILABLE")
          String code,
      @Min(1) @Max(5) int attempts) {}

  @PostMapping("/{id}/receipt")
  @Transactional
  ResponseEntity<Void> receipt(@PathVariable UUID id, @Valid @RequestBody Receipt receipt)
      throws Exception {
    if (receipt.status().equals("VERIFIED") != receipt.code().equals("NONE"))
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "Receipt status and diagnostic disagree");
    var parent = db.queryForList("SELECT job_id FROM delivery_outbox WHERE id=?", id);
    if (parent.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Task not found");
    UUID jobId = (UUID) parent.getFirst().get("job_id");
    db.queryForList("SELECT id FROM report_job WHERE id=? FOR UPDATE", jobId);
    var tasks =
        db.queryForList(
            "SELECT d.status,d.receipt::text,a.sha256 FROM delivery_outbox d JOIN report_artifact a"
                + " ON a.id=d.artifact_id WHERE d.id=? FOR UPDATE OF d",
            id);
    if (tasks.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Task not found");
    var task = tasks.getFirst();
    if (!receipt.sha256().equals(task.get("sha256")))
      throw new ResponseStatusException(HttpStatus.CONFLICT, "Artifact checksum mismatch");
    if (task.get("status").equals("CANCELLED")) return ResponseEntity.noContent().build();
    if (!task.get("status").equals("PENDING")) {
      if (!json.readTree((String) task.get("receipt")).equals(json.valueToTree(receipt)))
        throw new ResponseStatusException(HttpStatus.CONFLICT, "Conflicting terminal receipt");
      return ResponseEntity.noContent().build();
    }
    db.update(
        "UPDATE delivery_outbox SET status=?,receipt=?::jsonb,updated_at=now() WHERE id=?",
        receipt.status(),
        json.writeValueAsString(receipt),
        id);
    outcomes.settle(jobId);
    db.update(
        "INSERT INTO audit_event(actor,action,resource_id) VALUES ('delivery-service',?,?)",
        "DELIVERY_" + receipt.status(),
        id.toString());
    return ResponseEntity.noContent().build();
  }
}
