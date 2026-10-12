package com.acme.reports;

import java.util.*;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/jobs")
class JobController {
  private final JobService jobs;
  private final Renderer renderer;
  private final JdbcTemplate db;
  private final ConfigurationCodec codec;

  JobController(JobService jobs, Renderer renderer, JdbcTemplate db, ConfigurationCodec codec) {
    this.jobs = jobs;
    this.renderer = renderer;
    this.db = db;
    this.codec = codec;
  }

  @PostMapping(
      consumes = {"application/json", "application/yaml"},
      produces = "application/json")
  ResponseEntity<Map<String, Object>> submit(
      Authentication user,
      @RequestHeader("Idempotency-Key") String key,
      @RequestHeader("Content-Type") String contentType,
      @RequestBody byte[] bytes)
      throws Exception {
    var request =
        codec.read(
            bytes,
            MediaType.parseMediaType(contentType)
                .isCompatibleWith(MediaType.valueOf("application/yaml")));
    var result = jobs.submit(user.getName(), key, request);
    return ResponseEntity.accepted()
        .header("Location", "/backend/api/v1/jobs/" + result.get("id"))
        .body(result);
  }

  @GetMapping
  Map<String, Object> list(
      Authentication user,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(required = false) String status) {
    return jobs.list(user.getName(), page, status);
  }

  @GetMapping("/{id}")
  Map<String, Object> get(Authentication user, @PathVariable UUID id) {
    return jobs.get(user.getName(), id);
  }

  @PostMapping("/{id}/cancel")
  ResponseEntity<Void> cancel(Authentication user, @PathVariable UUID id) {
    jobs.cancel(user.getName(), id);
    return ResponseEntity.noContent().build();
  }

  @PostMapping("/{id}/reprocess")
  ResponseEntity<Map<String, Object>> reprocess(
      Authentication user,
      @PathVariable UUID id,
      @RequestHeader("Idempotency-Key") String key,
      @RequestBody(required = false) byte[] body)
      throws Exception {
    if (body != null && body.length != 0)
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "Reprocessing does not accept a body");
    var result = jobs.reprocess(user.getName(), key, id);
    return ResponseEntity.accepted()
        .header("Location", "/backend/api/v1/jobs/" + result.get("id"))
        .body(result);
  }

  @GetMapping("/{id}/reprocessings")
  Map<String, Object> reprocessings(
      Authentication user, @PathVariable UUID id, @RequestParam(defaultValue = "0") int page) {
    return jobs.reprocessings(user.getName(), id, page);
  }

  @GetMapping("/{id}/output")
  ResponseEntity<FileSystemResource> output(Authentication user, @PathVariable UUID id) {
    jobs.get(user.getName(), id);
    var artifacts = db.queryForList("SELECT id FROM report_artifact WHERE job_id=?", id);
    if (artifacts.size() != 1)
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "Select an available output by artifact ID");
    return output(user, id, (UUID) artifacts.getFirst().get("id"));
  }

  @GetMapping("/{id}/outputs/{artifactId}")
  ResponseEntity<FileSystemResource> output(
      Authentication user, @PathVariable UUID id, @PathVariable UUID artifactId) {
    jobs.get(user.getName(), id);
    var artifacts =
        db.queryForList(
            "SELECT sha256,size_bytes,storage_key,format,media_type FROM report_artifact WHERE"
                + " job_id=? AND id=?",
            id,
            artifactId);
    if (artifacts.isEmpty())
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Artifact not found");
    var file =
        new FileSystemResource(renderer.path(artifacts.getFirst().get("storage_key").toString()));
    if (!file.exists())
      throw new ResponseStatusException(HttpStatus.GONE, "Artifact no longer retained");
    return ResponseEntity.ok()
        .contentType(MediaType.parseMediaType(artifacts.getFirst().get("media_type").toString()))
        .header(
            "Content-Disposition",
            "attachment; filename=report-"
                + id
                + "."
                + ReportFormat.valueOf(artifacts.getFirst().get("format").toString()).extension())
        .header("Cache-Control", "no-store")
        .header("X-Content-Type-Options", "nosniff")
        .header("X-Artifact-SHA256", artifacts.getFirst().get("sha256").toString())
        .body(file);
  }
}
