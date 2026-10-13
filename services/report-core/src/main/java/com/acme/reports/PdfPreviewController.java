package com.acme.reports;

import java.util.*;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/jobs/{id}/outputs/{artifactId}/preview")
class PdfPreviewController {
  private final JobService jobs;
  private final JdbcTemplate db;
  private final Renderer renderer;
  private final PdfPreview previews;

  PdfPreviewController(JobService jobs, JdbcTemplate db, Renderer renderer, PdfPreview previews) {
    this.jobs = jobs;
    this.db = db;
    this.renderer = renderer;
    this.previews = previews;
  }

  private <T> T read(
      Authentication user, UUID id, UUID artifactId, PdfPreview.Operation<T> action) {
    jobs.get(user.getName(), id);
    var rows =
        db.queryForList(
            "SELECT format,storage_key,sha256,size_bytes FROM report_artifact WHERE job_id=? AND"
                + " id=?",
            id,
            artifactId);
    if (rows.isEmpty())
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Artifact not found");
    var artifact = rows.getFirst();
    if (!"PDF".equals(artifact.get("format")))
      throw new ResponseStatusException(
          HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Only PDF artifacts support preview");
    return previews.read(
        renderer.path(artifact.get("storage_key").toString()),
        artifact.get("sha256").toString(),
        ((Number) artifact.get("size_bytes")).longValue(),
        action);
  }

  @GetMapping
  ResponseEntity<PdfPreview.Metadata> metadata(
      Authentication user, @PathVariable UUID id, @PathVariable UUID artifactId) {
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(read(user, id, artifactId, previews::metadata));
  }

  @GetMapping("/pages/{page}")
  ResponseEntity<byte[]> page(
      Authentication user,
      @PathVariable UUID id,
      @PathVariable UUID artifactId,
      @PathVariable int page) {
    byte[] bytes = read(user, id, artifactId, document -> previews.page(document, page));
    return ResponseEntity.ok()
        .contentType(MediaType.IMAGE_PNG)
        .cacheControl(CacheControl.noStore())
        .header("X-Content-Type-Options", "nosniff")
        .header("Content-Disposition", "inline; filename=preview.png")
        .body(bytes);
  }
}
