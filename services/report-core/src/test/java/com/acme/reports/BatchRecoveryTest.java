package com.acme.reports;

import static org.assertj.core.api.Assertions.*;

import java.util.*;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.*;
import org.springframework.batch.core.explore.JobExplorer;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.batch.core.repository.JobRepository;
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
class BatchRecoveryTest {
  @Container static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17.6");

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry r) {
    r.add("spring.datasource.url", postgres::getJdbcUrl);
    r.add("spring.datasource.username", postgres::getUsername);
    r.add("spring.datasource.password", postgres::getPassword);
    r.add(
        "ARTIFACT_ROOT",
        () -> System.getProperty("java.io.tmpdir") + "/report-batch-test-artifacts");
  }

  @Autowired org.springframework.test.web.servlet.MockMvc mvc;
  @MockitoBean JwtDecoder decoder;

  @MockitoBean
  org.springframework.security.oauth2.client.registration.ClientRegistrationRepository clients;

  @Autowired JobService jobs;
  @Autowired JdbcTemplate db;
  @Autowired DataSource source;
  @Autowired JobRepository repository;
  @Autowired JobExplorer explorer;
  @Autowired JobLauncher launcher;
  @Autowired Job reportJob;

  @Test
  void interruptedExecutionRestartsSameInstanceAndKeepsDeliveredArtifact() throws Exception {
    var request =
        new ReportRequest(
            "1",
            "sales-summary",
            1,
            new ReportRequest.Parameters("Restarted sales"),
            List.of("PDF"),
            "local-s3");
    UUID id = (UUID) jobs.submit("recovery-owner", UUID.randomUUID().toString(), request).get("id");
    var parameters =
        new JobParametersBuilder()
            .addString("jobId", id.toString(), true)
            .addLong("epoch", 1L, false)
            .toJobParameters();
    var interrupted = repository.createJobExecution("report-pdf-v1", parameters);
    interrupted.setStatus(BatchStatus.STARTED);
    repository.update(interrupted);
    var interruptedStep = new StepExecution("render-and-stage", interrupted);
    repository.add(interruptedStep);
    interruptedStep.setStatus(BatchStatus.STARTED);
    repository.update(interruptedStep);
    db.update(
        "UPDATE report_job SET status='RUNNING',stage='rendering',attempt=1,render_epoch=1 WHERE"
            + " id=?",
        id);
    var dispatcher = new ReportDispatcher(source, db, launcher, reportJob, explorer, repository);
    dispatcher.dispatch();
    assertThat(jobs.get("recovery-owner", id).get("status")).isEqualTo("DELIVERING");
    assertThat(jobs.get("recovery-owner", id).get("attempt")).isEqualTo(2);
    assertThat(explorer.getJobExecution(interrupted.getId()).getStatus())
        .isEqualTo(BatchStatus.FAILED);
    var executions = explorer.getJobExecutions(interrupted.getJobInstance());
    assertThat(executions).hasSize(2);
    assertThat(executions).anyMatch(e -> e.getStatus() == BatchStatus.COMPLETED);
    String storageKey =
        db.queryForObject("SELECT storage_key FROM report_artifact WHERE id=?", String.class, id);
    assertThat(storageKey).isEqualTo(id + ".3.pdf");
    var service =
        org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors
            .user("delivery")
            .roles("DELIVERY_SERVICE");
    var csrf =
        org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors
            .csrf();
    String started =
        "{\"attempts\":[{\"number\":1,\"phase\":\"UPLOAD\",\"status\":\"STARTED\",\"code\":\"NONE\",\"startedAt\":\"2026-10-01T00:00:00Z\",\"endedAt\":null}]}";
    String completed =
        started.replace("STARTED", "VERIFIED").replace("null", "\"2026-10-01T00:00:01Z\"");
    for (String event : List.of(started, completed, started)) {
      mvc.perform(
              org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(
                      "/api/v1/internal/deliveries/" + id + "/attempts")
                  .with(service)
                  .with(csrf)
                  .contentType("application/json")
                  .content(event))
          .andExpect(
              org.springframework.test.web.servlet.result.MockMvcResultMatchers.status()
                  .isNoContent());
    }
    assertThat(
            db.queryForObject(
                "SELECT status FROM delivery_attempt WHERE delivery_id=? AND number=1",
                String.class,
                id))
        .isEqualTo("VERIFIED");

    dispatcher.dispatch(); // Waiting on delivery must never re-render a complete retained artifact.
    assertThat(jobs.get("recovery-owner", id).get("attempt")).isEqualTo(2);
    assertThat(
            db.queryForObject(
                "SELECT storage_key FROM report_artifact WHERE id=?", String.class, id))
        .isEqualTo(storageKey);
    String hash =
        db.queryForObject("SELECT sha256 FROM report_artifact WHERE id=?", String.class, id);
    String receipt =
        "{\"status\":\"VERIFIED\",\"sha256\":\""
            + hash
            + "\",\"version\":\"generation-1\",\"code\":\"NONE\",\"attempts\":1}";
    for (int i = 0; i < 3; i++) {
      mvc.perform(
              org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(
                      "/api/v1/internal/deliveries/" + id + "/receipt")
                  .with(service)
                  .with(csrf)
                  .contentType("application/json")
                  .content(i < 2 ? receipt : receipt.replace("generation-1", "generation-2")))
          .andExpect(
              org.springframework.test.web.servlet.result.MockMvcResultMatchers.status()
                  .is(i < 2 ? 204 : 409));
    }
    assertThat(jobs.get("recovery-owner", id).get("status")).isEqualTo("SUCCEEDED");
    var owner =
        org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors
            .user("recovery-owner")
            .roles("REPORT_USER");
    var outsider =
        org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors
            .user("other-owner")
            .roles("REPORT_USER");
    mvc.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(
                    "/api/v1/jobs/" + id + "/download-links")
                .with(outsider)
                .with(csrf))
        .andExpect(
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.status()
                .isNotFound());
    var linkResponse =
        mvc.perform(
                org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(
                        "/api/v1/jobs/" + id + "/download-links")
                    .with(owner)
                    .with(csrf))
            .andExpect(
                org.springframework.test.web.servlet.result.MockMvcResultMatchers.status()
                    .isAccepted())
            .andReturn();
    var linkJson =
        new com.fasterxml.jackson.databind.ObjectMapper()
            .readTree(linkResponse.getResponse().getContentAsString());
    String operationId = linkJson.path("id").asText();
    assertThat(java.time.Instant.parse(linkJson.path("expires_at").asText()))
        .isBeforeOrEqualTo(java.time.Instant.now().plusSeconds(300));
    mvc.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                    "/api/v1/cloud-operations/" + operationId)
                .with(outsider))
        .andExpect(
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.status()
                .isNotFound());
    mvc.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(
                    "/api/v1/internal/deliveries/controls/" + operationId + "/result")
                .with(service)
                .with(csrf)
                .contentType("application/json")
                .content(
                    "{\"status\":\"SUCCEEDED\",\"code\":\"SIGN_OK\",\"url\":\"javascript:alert(1)\"}"))
        .andExpect(
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.status()
                .isBadRequest());
  }

  @Test
  void destinationUpdatesRequireAdminAndCsrf() throws Exception {
    var request =
        org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch(
                "/api/v1/destinations/local-gcs")
            .contentType("application/json")
            .content("{\"enabled\":false}");
    mvc.perform(
            request
                .with(
                    org.springframework.security.test.web.servlet.request
                        .SecurityMockMvcRequestPostProcessors.user("user")
                        .roles("REPORT_USER"))
                .with(
                    org.springframework.security.test.web.servlet.request
                        .SecurityMockMvcRequestPostProcessors.csrf()))
        .andExpect(
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.status()
                .isForbidden());
    mvc.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch(
                    "/api/v1/destinations/local-gcs")
                .contentType("application/json")
                .content("{\"enabled\":false}")
                .with(
                    org.springframework.security.test.web.servlet.request
                        .SecurityMockMvcRequestPostProcessors.user("admin")
                        .roles("REPORT_ADMIN"))
                .with(
                    org.springframework.security.test.web.servlet.request
                        .SecurityMockMvcRequestPostProcessors.csrf()))
        .andExpect(
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());
    assertThat(
            db.queryForObject(
                "SELECT enabled FROM destination WHERE id='local-gcs'", Boolean.class))
        .isFalse();
    db.update("UPDATE destination SET enabled=true,version=version+1 WHERE id='local-gcs'");
  }

  @Test
  void destinationVersionsAreValidatedAndAcceptedJobsKeepSnapshots() throws Exception {
    var admin =
        org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors
            .user("admin")
            .roles("REPORT_ADMIN");
    var csrf =
        org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors
            .csrf();
    var id = "finance-" + UUID.randomUUID();
    String configuration =
        "{\"provider\":\"S3\",\"bucket\":\"report-results\",\"prefix\":\"finance/2026/\",\"enabled\":true}";
    mvc.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(
                    "/api/v1/destinations")
                .with(admin)
                .with(csrf)
                .contentType("application/json")
                .content("{\"id\":\"" + id + "\",\"configuration\":" + configuration + "}"))
        .andExpect(
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isCreated());
    var job =
        jobs.submit(
            "snapshot-owner",
            UUID.randomUUID().toString(),
            new ReportRequest(
                "1",
                "sales-summary",
                1,
                new ReportRequest.Parameters("Snapshot"),
                List.of("PDF"),
                id));
    String replacement =
        "{\"expectedVersion\":1,\"configuration\":"
            + configuration.replace("finance/2026/", "finance/2027/")
            + "}";
    for (int expected : new int[] {200, 409}) {
      mvc.perform(
              org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(
                      "/api/v1/destinations/" + id)
                  .with(admin)
                  .with(csrf)
                  .contentType("application/json")
                  .content(replacement))
          .andExpect(
              org.springframework.test.web.servlet.result.MockMvcResultMatchers.status()
                  .is(expected));
    }
    assertThat(
            db.queryForObject(
                "SELECT resolved_config->'destination'->>'prefix' FROM report_job WHERE id=?",
                String.class,
                job.get("id")))
        .isEqualTo("finance/2026/");
    assertThat(
            db.queryForObject(
                "SELECT count(*) FROM destination_version WHERE destination_id=?",
                Integer.class,
                id))
        .isEqualTo(2);
    assertThatThrownBy(
            () -> db.update("DELETE FROM destination_version WHERE destination_id=?", id))
        .hasMessageContaining("append-only");
    for (String invalid :
        List.of(
            configuration.replace("finance/2026/", "../private/"),
            configuration.replace("report-results", "unapproved-bucket"))) {
      mvc.perform(
              org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(
                      "/api/v1/destinations/" + id)
                  .with(admin)
                  .with(csrf)
                  .contentType("application/json")
                  .content("{\"expectedVersion\":2,\"configuration\":" + invalid + "}"))
          .andExpect(
              org.springframework.test.web.servlet.result.MockMvcResultMatchers.status()
                  .isBadRequest());
    }
    mvc.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(
                    "/api/v1/destinations/" + id)
                .with(admin)
                .contentType("application/json")
                .content(replacement))
        .andExpect(
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.status()
                .isForbidden());
    jobs.cancel("snapshot-owner", (UUID) job.get("id"));
  }

  @Test
  void batchPersistsEachEnabledFormatWithMatchingStorageKeyAndMime() throws Exception {
    for (var format :
        List.of(ReportFormat.CSV, ReportFormat.XLSX, ReportFormat.DOCX, ReportFormat.HTML)) {
      var request =
          new ReportRequest(
              "1",
              "sales-summary",
              1,
              new ReportRequest.Parameters("Batch formats"),
              List.of(format.name()),
              "local-s3");
      UUID id = (UUID) jobs.submit("format-owner", UUID.randomUUID().toString(), request).get("id");
      new ReportDispatcher(source, db, launcher, reportJob, explorer, repository).dispatch();
      assertThat(jobs.get("format-owner", id).get("status")).isEqualTo("DELIVERING");
      var artifact =
          db.queryForMap(
              "SELECT format,storage_key,media_type FROM report_artifact WHERE id=?", id);
      assertThat(artifact.get("format")).isEqualTo(format.name());
      assertThat(artifact.get("storage_key")).isEqualTo(id + ".1." + format.extension());
      assertThat(artifact.get("media_type")).isEqualTo(format.mediaType);
      assertThat(
              db.queryForObject(
                  "SELECT object_key FROM delivery_outbox WHERE id=?", String.class, id))
          .endsWith("." + format.extension());
      assertThatThrownBy(
              () ->
                  db.update(
                      "UPDATE report_artifact SET storage_key=? WHERE id=?", id + ".1.pdf", id))
          .hasMessageContaining("safe_storage_key");
      jobs.cancel("format-owner", id);
    }
  }

  @Test
  void cloudChecksAreDurableScopedAndExpireWithoutLeakingLinks() throws Exception {
    var admin =
        org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors
            .user("check-admin")
            .roles("REPORT_ADMIN");
    var user =
        org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors
            .user("report-user")
            .roles("REPORT_USER");
    var service =
        org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors
            .user("delivery")
            .roles("DELIVERY_SERVICE");
    var csrf =
        org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors
            .csrf();
    var json = new com.fasterxml.jackson.databind.ObjectMapper();
    var request =
        org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(
                "/api/v1/destinations/local-s3/checks")
            .with(admin)
            .with(csrf);
    var response =
        mvc.perform(request)
            .andExpect(
                org.springframework.test.web.servlet.result.MockMvcResultMatchers.status()
                    .isAccepted())
            .andReturn();
    String id = json.readTree(response.getResponse().getContentAsString()).path("id").asText();
    var again =
        mvc.perform(
                org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(
                        "/api/v1/destinations/local-s3/checks")
                    .with(admin)
                    .with(csrf))
            .andReturn();
    assertThat(json.readTree(again.getResponse().getContentAsString()).path("id").asText())
        .isEqualTo(id);
    mvc.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                    "/api/v1/cloud-operations/" + id)
                .with(user))
        .andExpect(
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.status()
                .isNotFound());
    mvc.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(
                    "/api/v1/destinations/local-s3/checks")
                .with(user)
                .with(csrf))
        .andExpect(
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.status()
                .isForbidden());
    for (String result :
        List.of(
            "{\"status\":\"SUCCEEDED\",\"code\":\"CHECK_OK\",\"url\":\"\"}",
            "{\"status\":\"SUCCEEDED\",\"code\":\"CHECK_OK\",\"url\":\"\"}")) {
      mvc.perform(
              org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(
                      "/api/v1/internal/deliveries/controls/" + id + "/result")
                  .with(service)
                  .with(csrf)
                  .contentType("application/json")
                  .content(result))
          .andExpect(
              org.springframework.test.web.servlet.result.MockMvcResultMatchers.status()
                  .isNoContent());
    }
    mvc.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(
                    "/api/v1/internal/deliveries/controls/" + id + "/result")
                .with(service)
                .with(csrf)
                .contentType("application/json")
                .content("{\"status\":\"FAILED\",\"code\":\"PROVIDER_UNAVAILABLE\",\"url\":\"\"}"))
        .andExpect(
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.status()
                .isConflict());
    db.update(
        "UPDATE cloud_operation SET expires_at=now()-interval '1 second' WHERE id=?",
        UUID.fromString(id));
    mvc.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                    "/api/v1/cloud-operations/" + id)
                .with(admin))
        .andExpect(
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.status")
                .value("EXPIRED"))
        .andExpect(
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.url")
                .value(""));
  }

  @Test
  void oversizedJsonRejectedBeforeParsing() throws Exception {
    mvc.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/jobs")
                .contentType("application/json")
                .content("x".repeat(65537)))
        .andExpect(
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.status()
                .isPayloadTooLarge());
  }
}
