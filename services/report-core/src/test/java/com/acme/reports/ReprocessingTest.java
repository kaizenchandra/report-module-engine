package com.acme.reports;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.*;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

@SpringBootTest(
    properties = {
      "report.dispatch.enabled=false",
      "spring.security.oauth2.client.provider.reporting.issuer-uri=",
      "spring.security.oauth2.client.provider.reporting.authorization-uri=http://localhost/authorize",
      "spring.security.oauth2.client.provider.reporting.token-uri=http://localhost/token",
      "spring.security.oauth2.client.provider.reporting.jwk-set-uri=http://localhost/jwks",
      "spring.security.oauth2.client.registration.reporting.client-secret=synthetic-test-only"
    })
@org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
@Testcontainers
class ReprocessingTest {
  @Container static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17.6");

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry r) {
    r.add("spring.datasource.url", postgres::getJdbcUrl);
    r.add("spring.datasource.username", postgres::getUsername);
    r.add("spring.datasource.password", postgres::getPassword);
    r.add(
        "ARTIFACT_ROOT",
        () -> System.getProperty("java.io.tmpdir") + "/report-reprocessing-test-artifacts");
  }

  @Autowired org.springframework.test.web.servlet.MockMvc mvc;
  @MockitoBean JwtDecoder decoder;

  @MockitoBean
  org.springframework.security.oauth2.client.registration.ClientRegistrationRepository clients;

  @Autowired JobService jobs;
  @Autowired JdbcTemplate db;
  @Autowired ObjectMapper json;
  @Autowired JobLauncher launcher;
  @Autowired Job reportJob;
  @Autowired Renderer renderer;

  ReportRequest request() {
    return new ReportRequest(
        "1",
        "sales-summary",
        1,
        new ReportRequest.Parameters("Reprocessing integrity"),
        List.of("PDF"),
        "local-s3");
  }

  UUID parent(String owner) throws Exception {
    UUID id = (UUID) jobs.submit(owner, UUID.randomUUID().toString(), request()).get("id");
    jobs.cancel(owner, id);
    return id;
  }

  JobExecution render(UUID id) throws Exception {
    db.update("UPDATE report_job SET status='RUNNING',attempt=1,render_epoch=1 WHERE id=?", id);
    var execution =
        launcher.run(
            reportJob,
            new JobParametersBuilder()
                .addString("jobId", id.toString(), true)
                .addLong("epoch", 1L, false)
                .toJobParameters());
    assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
    return execution;
  }

  @Test
  void concurrentReprocessingRecoversOneChildAndKeysCannotCrossOperationsOrParents()
      throws Exception {
    String owner = "concurrent-owner";
    UUID parent = parent(owner);
    String key = UUID.randomUUID().toString();
    UUID child;
    try (var executor = Executors.newFixedThreadPool(2)) {
      var gate = new CountDownLatch(1);
      Callable<Map<String, Object>> call =
          () -> {
            gate.await();
            return jobs.reprocess(owner, key, parent);
          };
      var a = executor.submit(call);
      var b = executor.submit(call);
      gate.countDown();
      child = (UUID) a.get(15, TimeUnit.SECONDS).get("id");
      assertThat(b.get(15, TimeUnit.SECONDS).get("id")).isEqualTo(child);
    }
    assertThat(child).isNotEqualTo(parent);
    assertThat(jobs.get(owner, child).get("parent_job_id")).isEqualTo(parent);
    assertThat(
            db.queryForObject(
                "SELECT count(*) FROM report_job WHERE parent_job_id=?", Integer.class, parent))
        .isEqualTo(1);
    assertThat(
            db.queryForObject(
                "SELECT count(*) FROM audit_event WHERE resource_id=? AND action='JOB_REPROCESSED'",
                Integer.class,
                child.toString()))
        .isEqualTo(1);
    assertThatThrownBy(() -> jobs.submit(owner, key, request())).hasMessageContaining("409");
    UUID other = parent(owner);
    assertThatThrownBy(() -> jobs.reprocess(owner, key, other)).hasMessageContaining("409");
    String submissionKey = UUID.randomUUID().toString();
    jobs.submit(owner, submissionKey, request());
    assertThatThrownBy(() -> jobs.reprocess(owner, submissionKey, parent))
        .hasMessageContaining("409");
  }

  @Test
  void endpointsEnforceOwnershipCsrfTerminalStateAndEmptyBody() throws Exception {
    String owner = "api-owner";
    UUID parent = parent(owner);
    mvc.perform(
            post("/api/v1/jobs/" + parent + "/reprocess")
                .with(user(owner).roles("REPORT_USER"))
                .header("Idempotency-Key", UUID.randomUUID()))
        .andExpect(status().isForbidden());
    mvc.perform(
            post("/api/v1/jobs/" + parent + "/reprocess")
                .with(user("another-admin").roles("REPORT_ADMIN"))
                .with(csrf())
                .header("Idempotency-Key", UUID.randomUUID()))
        .andExpect(status().isNotFound());
    mvc.perform(
            get("/api/v1/jobs/" + parent + "/reprocessings")
                .with(user("another-admin").roles("REPORT_ADMIN")))
        .andExpect(status().isNotFound());
    mvc.perform(
            post("/api/v1/jobs/" + parent + "/reprocess")
                .with(user(owner).roles("REPORT_USER"))
                .with(csrf())
                .header("Idempotency-Key", UUID.randomUUID())
                .contentType("application/json")
                .content("{}"))
        .andExpect(status().isBadRequest());
    for (String state : List.of("QUEUED", "RUNNING", "DELIVERING")) {
      UUID active = (UUID) jobs.submit(owner, UUID.randomUUID().toString(), request()).get("id");
      db.update("UPDATE report_job SET status=? WHERE id=?", state, active);
      mvc.perform(
              post("/api/v1/jobs/" + active + "/reprocess")
                  .with(user(owner).roles("REPORT_USER"))
                  .with(csrf())
                  .header("Idempotency-Key", UUID.randomUUID()))
          .andExpect(status().isConflict());
      jobs.cancel(owner, active);
    }
    mvc.perform(
            post("/api/v1/jobs/" + parent + "/reprocess")
                .with(user(owner).roles("REPORT_USER"))
                .with(csrf())
                .header("Idempotency-Key", "bad"))
        .andExpect(status().isBadRequest());
    mvc.perform(
            post("/api/v1/jobs/" + parent + "/reprocess")
                .with(user(owner).roles("REPORT_USER"))
                .with(csrf())
                .header("Idempotency-Key", UUID.randomUUID()))
        .andExpect(status().isAccepted())
        .andExpect(jsonPath("$.parent_job_id").value(parent.toString()))
        .andExpect(header().exists("Location"));
    mvc.perform(
            get("/api/v1/jobs/" + parent + "/reprocessings?page=-1")
                .with(user(owner).roles("REPORT_USER")))
        .andExpect(status().isBadRequest());
  }

  @Test
  void newBatchInstanceDeadlineAndArtifactLeaveTheParentIntact() throws Exception {
    String owner = "render-owner";
    UUID parent = (UUID) jobs.submit(owner, UUID.randomUUID().toString(), request()).get("id");
    var originalExecution = render(parent);
    jobs.cancel(owner, parent);
    db.update("UPDATE report_job SET deadline=now()-interval '1 hour' WHERE id=?", parent);
    String originalConfig = (String) jobs.get(owner, parent).get("configuration");
    String originalKey =
        db.queryForObject(
            "SELECT storage_key FROM report_artifact WHERE job_id=?", String.class, parent);
    byte[] originalBytes = java.nio.file.Files.readAllBytes(renderer.path(originalKey));
    UUID child = (UUID) jobs.reprocess(owner, UUID.randomUUID().toString(), parent).get("id");
    assertThat(
            db.queryForObject(
                "SELECT deadline>now()+interval '14 minutes' FROM report_job WHERE id=?",
                Boolean.class,
                child))
        .isTrue();
    var newExecution = render(child);
    assertThat(newExecution.getJobInstance().getInstanceId())
        .isNotEqualTo(originalExecution.getJobInstance().getInstanceId());
    String childKey =
        db.queryForObject(
            "SELECT storage_key FROM report_artifact WHERE job_id=?", String.class, child);
    assertThat(childKey).isNotEqualTo(originalKey);
    try (var pdf =
        org.apache.pdfbox.Loader.loadPDF(
            java.nio.file.Files.readAllBytes(renderer.path(childKey)))) {
      assertThat(new org.apache.pdfbox.text.PDFTextStripper().getText(pdf))
          .contains("Reprocessing integrity", "Atlas notebook");
    }
    jobs.cancel(owner, child);
    assertThat(jobs.get(owner, parent).get("status")).isEqualTo("CANCELLED");
    assertThat(jobs.get(owner, parent).get("configuration")).isEqualTo(originalConfig);
    assertThat(java.nio.file.Files.readAllBytes(renderer.path(originalKey)))
        .isEqualTo(originalBytes);
    assertThat(
            db.queryForObject(
                "SELECT count(DISTINCT object_key) FROM delivery_outbox WHERE job_id IN (?,?)",
                Integer.class,
                parent,
                child))
        .isEqualTo(2);
  }

  @Test
  void freshAdmissionRevalidatesResourcesButAcknowledgementRecoveryDoesNot() throws Exception {
    String owner = "snapshot-owner";
    String definition = "reprocess-definition";
    String destination = "reprocess-destination";
    db.update(
        "INSERT INTO report_definition(id,version,name,description,template_resource) VALUES"
            + " (?,1,'Reprocessing','Test',?)",
        definition,
        DefinitionController.TEMPLATE);
    db.update(
        "INSERT INTO destination(id,provider,bucket,prefix,enabled) VALUES"
            + " (?,'S3','report-results','before/',true)",
        destination);
    var request =
        new ReportRequest(
            "1",
            definition,
            1,
            new ReportRequest.Parameters("Snapshots"),
            List.of("PDF"),
            destination);
    UUID parent = (UUID) jobs.submit(owner, UUID.randomUUID().toString(), request).get("id");
    jobs.cancel(owner, parent);
    db.update("UPDATE destination SET prefix='after/',version=version+1 WHERE id=?", destination);
    String key = UUID.randomUUID().toString();
    UUID child = (UUID) jobs.reprocess(owner, key, parent).get("id");
    var oldConfig = json.readTree((String) jobs.get(owner, parent).get("configuration"));
    var newConfig = json.readTree((String) jobs.get(owner, child).get("configuration"));
    assertThat(oldConfig.path("destination").path("prefix").asText()).isEqualTo("before/");
    assertThat(newConfig.path("destination").path("prefix").asText()).isEqualTo("after/");
    assertThat(oldConfig.path("request")).isEqualTo(newConfig.path("request"));
    db.update("UPDATE destination SET enabled=false,version=version+1 WHERE id=?", destination);
    assertThatThrownBy(() -> jobs.reprocess(owner, UUID.randomUUID().toString(), parent))
        .hasMessageContaining("Destination unavailable");
    db.update(
        "UPDATE report_definition SET enabled=false,version=version+1 WHERE id=?", definition);
    assertThatThrownBy(() -> jobs.reprocess(owner, UUID.randomUUID().toString(), parent))
        .hasMessageContaining("Definition version unavailable");
    assertThat(jobs.reprocess(owner, key, parent).get("id")).isEqualTo(child);
  }

  @Test
  void lineagePaginationAndQuotaRemainOwnerScoped() throws Exception {
    String owner = "history-owner";
    UUID parent = parent(owner);
    for (int i = 0; i < 21; i++) {
      UUID child = (UUID) jobs.reprocess(owner, UUID.randomUUID().toString(), parent).get("id");
      jobs.cancel(owner, child);
    }
    var first = jobs.reprocessings(owner, parent, 0);
    var second = jobs.reprocessings(owner, parent, 1);
    assertThat(first.get("total")).isEqualTo(21L);
    assertThat((List<?>) first.get("items")).hasSize(20);
    assertThat((List<?>) second.get("items")).hasSize(1);
    assertThatThrownBy(() -> jobs.reprocessings("other", parent, 0)).hasMessageContaining("404");
    String key = UUID.randomUUID().toString();
    UUID child = (UUID) jobs.reprocess(owner, key, parent).get("id");
    for (int i = 1; i < 20; i++) jobs.submit(owner, UUID.randomUUID().toString(), request());
    assertThatThrownBy(() -> jobs.reprocess(owner, UUID.randomUUID().toString(), parent))
        .hasMessageContaining("429");
    assertThat(jobs.reprocess(owner, key, parent).get("id")).isEqualTo(child);
  }
}
