package com.acme.reports;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.*;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

@JdbcTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({JobService.class, Renderer.class, JobPersistenceTest.JsonConfig.class})
@Testcontainers
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class JobPersistenceTest {
  @Container static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17.6");

  @DynamicPropertySource
  static void database(DynamicPropertyRegistry r) {
    r.add("spring.datasource.url", postgres::getJdbcUrl);
    r.add("spring.datasource.username", postgres::getUsername);
    r.add("spring.datasource.password", postgres::getPassword);
    r.add("ARTIFACT_ROOT", () -> System.getProperty("java.io.tmpdir") + "/report-test-artifacts");
  }

  @TestConfiguration
  static class JsonConfig {
    @Bean
    ObjectMapper mapper() {
      return new ObjectMapper();
    }
  }

  @Autowired JobService jobs;
  @Autowired org.springframework.jdbc.core.JdbcTemplate db;

  ReportRequest request(String title) {
    return new ReportRequest(
        "1", "sales-summary", 1, new ReportRequest.Parameters(title), List.of("PDF"), "local-s3");
  }

  @Test
  void durableIdempotencyAndObjectIsolation() throws Exception {
    assertThatThrownBy(
            () ->
                jobs.submit(
                    "unknown-destination-owner",
                    UUID.randomUUID().toString(),
                    new ReportRequest(
                        "1",
                        "sales-summary",
                        1,
                        new ReportRequest.Parameters("Test"),
                        List.of("PDF"),
                        "unregistered")))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("400");
    String owner = UUID.randomUUID().toString();
    String key = UUID.randomUUID().toString();
    var first = jobs.submit(owner, key, request("Test sales"));
    assertThat(jobs.submit(owner, key, request("Test sales")).get("id")).isEqualTo(first.get("id"));
    assertThatThrownBy(() -> jobs.submit(owner, key, request("Changed")))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("409");
    assertThatThrownBy(() -> jobs.get("different-owner", (UUID) first.get("id")))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("404");
    assertThat(
            db.queryForObject(
                "SELECT count(*) FROM audit_event WHERE resource_id=?",
                Integer.class,
                first.get("id").toString()))
        .isEqualTo(1);
    jobs.cancel(owner, (UUID) first.get("id"));
    assertThat(jobs.get(owner, (UUID) first.get("id")).get("status")).isEqualTo("CANCELLED");
    assertThatThrownBy(() -> jobs.cancel(owner, (UUID) first.get("id")))
        .hasMessageContaining("409");
  }

  @Test
  void cleanupPreservesReferencedActiveRecentAndUnrelatedFiles(
      @org.junit.jupiter.api.io.TempDir java.nio.file.Path root) throws Exception {
    var renderer = new Renderer(root.toString());
    UUID id =
        (UUID)
            jobs.submit("cleanup-owner", UUID.randomUUID().toString(), request("Cleanup"))
                .get("id");
    var old = java.nio.file.attribute.FileTime.from(java.time.Instant.now().minusSeconds(90000));
    var retained = root.resolve(id + ".1.pdf");
    var orphan = root.resolve(UUID.randomUUID() + ".2.pdf");
    var active = root.resolve(id + ".2.pdf");
    var recent = root.resolve(UUID.randomUUID() + ".3.pdf");
    var unrelated = root.resolve("operator-notes.txt");
    for (var file : List.of(retained, orphan, active, recent, unrelated)) {
      java.nio.file.Files.writeString(file, "synthetic");
      if (!file.equals(recent)) java.nio.file.Files.setLastModifiedTime(file, old);
    }
    var link = root.resolve(UUID.randomUUID() + ".4.pdf");
    java.nio.file.Files.createSymbolicLink(link, unrelated);
    db.update(
        "INSERT INTO report_artifact(id,job_id,sha256,size_bytes,storage_key) VALUES (?,?,?,?,?)",
        id,
        id,
        "a".repeat(64),
        9,
        retained.getFileName().toString());
    var cleanup = new ArtifactCleanup(db.getDataSource(), db, renderer);
    assertThat(cleanup.sweep(java.time.Instant.now())).isEqualTo(1);
    assertThat(java.nio.file.Files.exists(orphan)).isFalse();
    for (var file : List.of(retained, active, recent, unrelated, link))
      assertThat(java.nio.file.Files.exists(file, java.nio.file.LinkOption.NOFOLLOW_LINKS))
          .isTrue();
    jobs.cancel("cleanup-owner", id);
    assertThat(cleanup.sweep(java.time.Instant.now())).isEqualTo(1);
    assertThat(java.nio.file.Files.exists(retained)).isTrue();
  }

  @Test
  @SuppressWarnings("unchecked")
  void historyFiltersAreOwnerScopedStableAndBounded() throws Exception {
    String owner = UUID.randomUUID().toString();
    var ids = new ArrayList<UUID>();
    for (int n = 0; n < 23; n++) {
      UUID id =
          (UUID) jobs.submit(owner, UUID.randomUUID().toString(), request("History")).get("id");
      jobs.cancel(owner, id);
      db.update(
          "UPDATE report_job SET created_at=?::timestamptz WHERE id=?",
          n == 22 ? "2025-02-02T00:00:00Z" : "2025-02-01T00:00:00Z",
          id);
      if (n < 22) ids.add(id);
    }
    jobs.submit("other-" + owner, UUID.randomUUID().toString(), request("Hidden"));
    ids.sort(Comparator.comparing(UUID::toString));
    var first =
        jobs.list(owner, 0, "CANCELLED", "sales-summary", "2025-02-01", "2025-02-02", "oldest");
    assertThat(first.get("total")).isEqualTo(22L);
    var rows = (List<Map<String, Object>>) first.get("items");
    assertThat(rows.stream().map(r -> r.get("id")).toList())
        .containsExactlyElementsOf(ids.subList(0, 20));
    var second =
        jobs.list(owner, 1, "CANCELLED", "sales-summary", "2025-02-01", "2025-02-02", "oldest");
    assertThat(
            ((List<Map<String, Object>>) second.get("items"))
                .stream().map(r -> r.get("id")).toList())
        .containsExactlyElementsOf(ids.subList(20, 22));
    var newest = jobs.list(owner, 0, null, null, "2025-02-01", "2025-02-02", "newest");
    assertThat(((List<Map<String, Object>>) newest.get("items")).getFirst().get("id"))
        .isEqualTo(ids.getLast());
    assertThat(jobs.list(owner, 0, null, "missing", null, null, "newest").get("total"))
        .isEqualTo(0L);
    assertThat(jobs.list(owner, 0, "QUEUED", null, null, null, "newest").get("total"))
        .isEqualTo(0L);
    assertThat(jobs.list(owner, 0, null, null, null, null, "newest").get("total")).isEqualTo(23L);
    for (String invalid : List.of("2025-02-29", "2025-1-01", "0000-01-01", "2025-01-01T00:00Z"))
      assertThatThrownBy(() -> jobs.list(owner, 0, null, null, invalid, null, "newest"))
          .hasMessageContaining("400");
    assertThatThrownBy(() -> jobs.list(owner, 0, null, null, "2025-02-02", "2025-02-01", "newest"))
        .hasMessageContaining("400");
    assertThatThrownBy(
            () -> jobs.list(owner, 0, null, null, null, null, "id; DROP TABLE report_job"))
        .hasMessageContaining("400");
    assertThatThrownBy(() -> jobs.list(owner, 0, null, "' OR true --", null, null, "newest"))
        .hasMessageContaining("400");
    assertThatThrownBy(() -> jobs.list(owner, 10001, null, null, null, null, "newest"))
        .hasMessageContaining("400");
  }

  @Test
  void auditCannotBeUpdated() {
    db.update(
        "INSERT INTO audit_event(actor,action,resource_id) VALUES ('test','TEST','audit-guard')");
    assertThatThrownBy(
            () ->
                db.update(
                    "UPDATE audit_event SET action='TAMPERED' WHERE resource_id='audit-guard'"))
        .hasMessageContaining("append-only");
  }
}
