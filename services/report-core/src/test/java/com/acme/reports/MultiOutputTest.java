package com.acme.reports;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.*;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.*;
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
class MultiOutputTest {
  @Container static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17.6");

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry r) {
    r.add("spring.datasource.url", postgres::getJdbcUrl);
    r.add("spring.datasource.username", postgres::getUsername);
    r.add("spring.datasource.password", postgres::getPassword);
    r.add(
        "ARTIFACT_ROOT",
        () -> System.getProperty("java.io.tmpdir") + "/report-multi-test-artifacts");
  }

  @Autowired org.springframework.test.web.servlet.MockMvc mvc;
  @MockitoBean JwtDecoder decoder;

  @MockitoBean
  org.springframework.security.oauth2.client.registration.ClientRegistrationRepository clients;

  @MockitoSpyBean Renderer renderer;
  @Autowired JobService jobs;
  @Autowired JdbcTemplate db;
  @Autowired JobLauncher launcher;
  @Autowired Job reportJob;
  @Autowired ObjectMapper json;

  ReportRequest request(boolean optionalCsv) {
    return new ReportRequest(
        "2",
        "sales-summary",
        1,
        new ReportRequest.Parameters("Multi-format integrity"),
        List.of("PDF", "CSV"),
        null,
        optionalCsv ? List.of("CSV") : List.of(),
        List.of(
            new ReportRequest.Delivery("local-s3", List.of("PDF"), true),
            new ReportRequest.Delivery("local-gcs", List.of("PDF", "CSV"), false),
            // A required CSV mapping when the output itself is required.
            new ReportRequest.Delivery("local-s3", List.of("CSV"), !optionalCsv)));
  }

  UUID stage(ReportRequest request) throws Exception {
    UUID id = (UUID) jobs.submit("multi-owner", UUID.randomUUID().toString(), request).get("id");
    db.update("UPDATE report_job SET status='RUNNING',attempt=1,render_epoch=1 WHERE id=?", id);
    var result =
        launcher.run(
            reportJob,
            new JobParametersBuilder()
                .addString("jobId", id.toString(), true)
                .addLong("epoch", 1L, false)
                .toJobParameters());
    assertThat(result.getStatus()).isEqualTo(BatchStatus.COMPLETED);
    return id;
  }

  void receipt(Map<String, Object> task, boolean success) throws Exception {
    var body =
        Map.of(
            "status",
            success ? "VERIFIED" : "FAILED",
            "sha256",
            task.get("sha256"),
            "version",
            success ? "test-generation-1" : "",
            "code",
            success ? "NONE" : "UPLOAD_FAILED",
            "attempts",
            1);
    mvc.perform(
            post("/api/v1/internal/deliveries/" + task.get("id") + "/receipt")
                .with(user("delivery").roles("DELIVERY_SERVICE"))
                .with(csrf())
                .contentType("application/json")
                .content(json.writeValueAsBytes(body)))
        .andExpect(status().isNoContent());
  }

  List<Map<String, Object>> tasks(UUID id) {
    return db.queryForList(
        "SELECT d.*,a.sha256 FROM delivery_outbox d JOIN report_artifact a ON a.id=d.artifact_id"
            + " WHERE d.job_id=? ORDER BY d.required DESC,d.id",
        id);
  }

  @Test
  void mixedDeliveriesWaitForAllOutcomesAndRetainOwnerScopedArtifacts() throws Exception {
    UUID id = stage(request(false));
    var tasks = tasks(id);
    assertThat(tasks).hasSize(4);
    assertThat(
            db.queryForObject(
                "SELECT count(*) FROM report_artifact WHERE job_id=?", Integer.class, id))
        .isEqualTo(2);
    assertThat(tasks)
        .allMatch(t -> !t.get("id").equals(t.get("artifact_id")) && !t.get("id").equals(id));
    for (int i = 0; i < tasks.size(); i++) {
      receipt(tasks.get(i), i != tasks.size() - 1);
      assertThat(jobs.get("multi-owner", id).get("status"))
          .isEqualTo(i == tasks.size() - 1 ? "PARTIAL_SUCCESS" : "DELIVERING");
    }
    receipt(
        tasks.getLast(), false); // exact replay cannot add aggregation events or change the outcome
    assertThat(
            db.queryForObject(
                "SELECT count(*) FROM audit_event WHERE resource_id=? AND"
                    + " action='JOB_PARTIAL_SUCCESS'",
                Integer.class,
                id.toString()))
        .isEqualTo(1);
    var artifact = tasks.getFirst().get("artifact_id");
    mvc.perform(
            get("/api/v1/jobs/" + id + "/outputs/" + artifact)
                .with(user("multi-owner").roles("REPORT_USER")))
        .andExpect(status().isOk())
        .andExpect(header().string("X-Artifact-SHA256", tasks.getFirst().get("sha256").toString()));
    mvc.perform(
            get("/api/v1/jobs/" + id + "/outputs/" + artifact)
                .with(user("stranger").roles("REPORT_ADMIN")))
        .andExpect(status().isNotFound());
    mvc.perform(
            get("/api/v1/jobs/" + id + "/output").with(user("multi-owner").roles("REPORT_USER")))
        .andExpect(status().isConflict());
    mvc.perform(
            post("/api/v1/jobs/"
                    + id
                    + "/deliveries/"
                    + tasks.getFirst().get("id")
                    + "/download-links")
                .with(user("multi-owner").roles("REPORT_USER"))
                .with(csrf()))
        .andExpect(status().isAccepted());
  }

  @Test
  void optionalExportFailureKeepsRealPdfAndRequiredExportFailureFails() throws Exception {
    doThrow(new net.sf.jasperreports.engine.JRException("synthetic export failure"))
        .when(renderer)
        .render(anyString(), eq(ReportFormat.CSV));
    for (boolean optional : List.of(true, false)) {
      UUID id = stage(request(optional));
      assertThat(
              db.queryForObject(
                  "SELECT status FROM report_output WHERE job_id=? AND format='CSV'",
                  String.class,
                  id))
          .isEqualTo("FAILED");
      assertThat(tasks(id)).hasSize(2); // No upload task may refer to a failed export.
      for (var t : tasks(id)) receipt(t, true);
      assertThat(jobs.get("multi-owner", id).get("status"))
          .isEqualTo(optional ? "PARTIAL_SUCCESS" : "FAILED");
      assertThat(
              db.queryForObject(
                  "SELECT count(*) FROM report_artifact WHERE job_id=? AND format='PDF'",
                  Integer.class,
                  id))
          .isEqualTo(1);
    }
  }

  @Test
  void concurrentReceiptsCannotLoseOutcomeAndCancellationFencesEveryDelivery() throws Exception {
    UUID id = stage(request(false));
    var tasks = tasks(id);
    try (var pool = java.util.concurrent.Executors.newFixedThreadPool(4)) {
      var futures = new ArrayList<java.util.concurrent.Future<?>>();
      for (var t : tasks)
        futures.add(
            pool.submit(
                () -> {
                  receipt(t, true);
                  return null;
                }));
      for (var f : futures) f.get(20, java.util.concurrent.TimeUnit.SECONDS);
    }
    assertThat(jobs.get("multi-owner", id).get("status")).isEqualTo("SUCCEEDED");
    UUID cancelled = stage(request(false));
    var cancelledTasks = tasks(cancelled);
    jobs.cancel("multi-owner", cancelled);
    for (var t : cancelledTasks) receipt(t, true);
    assertThat(jobs.get("multi-owner", cancelled).get("status")).isEqualTo("CANCELLED");
    assertThat(tasks(cancelled)).allMatch(t -> t.get("status").equals("CANCELLED"));
  }

  @Test
  void legacyFingerprintAndInvalidMappingsAreStable() throws Exception {
    var old =
        new ReportRequest(
            "1",
            "sales-summary",
            1,
            new ReportRequest.Parameters("Title"),
            List.of("PDF"),
            "local-s3");
    assertThat(json.writeValueAsString(old))
        .isEqualTo(
            "{\"schemaVersion\":\"1\",\"definitionId\":\"sales-summary\",\"definitionVersion\":1,\"parameters\":{\"title\":\"Title\"},\"outputs\":[\"PDF\"],\"destinationRef\":\"local-s3\"}");
    var key = UUID.randomUUID().toString();
    var accepted = jobs.submit("multi-owner", key, request(false));
    assertThat(jobs.submit("multi-owner", key, request(false)).get("id"))
        .isEqualTo(accepted.get("id"));
    assertThatThrownBy(() -> jobs.submit("multi-owner", key, request(true)))
        .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
    var invalid = json.valueToTree(request(false));
    ((com.fasterxml.jackson.databind.node.ArrayNode) invalid.path("outputs")).add("PDF");
    mvc.perform(
            post("/api/v1/jobs")
                .with(user("multi-owner").roles("REPORT_USER"))
                .with(csrf())
                .header("Idempotency-Key", UUID.randomUUID())
                .contentType("application/json")
                .content(json.writeValueAsBytes(invalid)))
        .andExpect(status().isBadRequest());
    db.update(
        "INSERT INTO destination(id,provider,bucket,prefix,enabled,version) VALUES"
            + " ('same-target','S3','report-results','reports/',true,1)");
    var alias =
        new ReportRequest(
            "2",
            "sales-summary",
            1,
            old.parameters(),
            List.of("PDF"),
            null,
            List.of(),
            List.of(
                new ReportRequest.Delivery("local-s3", List.of("PDF"), true),
                new ReportRequest.Delivery("same-target", List.of("PDF"), false)));
    assertThatThrownBy(() -> jobs.submit("multi-owner", UUID.randomUUID().toString(), alias))
        .hasMessageContaining("Duplicate physical");
  }

  @Test
  void requiredDeliveryFailureRemainsFailedAfterOtherDeliveriesSucceed() throws Exception {
    UUID id = stage(request(false));
    var tasks = tasks(id);
    assertThat(tasks.getFirst().get("required")).isEqualTo(true);
    receipt(tasks.getFirst(), false);
    assertThat(jobs.get("multi-owner", id).get("status")).isEqualTo("DELIVERING");
    for (var t : tasks.subList(1, tasks.size())) receipt(t, true);
    assertThat(jobs.get("multi-owner", id).get("status")).isEqualTo("FAILED");
    assertThat(jobs.get("multi-owner", id).get("failure_code")).isEqualTo("REQUIRED_WORK_FAILED");
    assertThat(
            db.queryForObject(
                "SELECT count(*) FROM report_artifact WHERE job_id=?", Integer.class, id))
        .isEqualTo(2);
  }

  @Test
  void rejectsUnmappedOptionalDependenciesUnknownAndIgnoredProperties() throws Exception {
    var base = json.valueToTree(request(false));
    var invalid = new ArrayList<com.fasterxml.jackson.databind.JsonNode>();
    var ignored = base.deepCopy();
    ((com.fasterxml.jackson.databind.node.ObjectNode) ignored).put("mappingValid", true);
    invalid.add(ignored);
    var unknown = base.deepCopy();
    ((com.fasterxml.jackson.databind.node.ObjectNode) unknown).put("unsafePolicyOverride", true);
    invalid.add(unknown);
    var optional = base.deepCopy();
    ((com.fasterxml.jackson.databind.node.ObjectNode) optional)
        .putArray("optionalOutputs")
        .add("CSV");
    invalid.add(optional);
    var unmapped = base.deepCopy();
    ((com.fasterxml.jackson.databind.node.ArrayNode) unmapped.path("outputs")).add("XLSX");
    invalid.add(unmapped);
    var duplicate = base.deepCopy();
    ((com.fasterxml.jackson.databind.node.ArrayNode) duplicate.path("deliveries"))
        .add(duplicate.path("deliveries").get(0).deepCopy());
    invalid.add(duplicate);
    var empty = base.deepCopy();
    ((com.fasterxml.jackson.databind.node.ObjectNode) empty).putArray("outputs");
    invalid.add(empty);
    for (var body : invalid)
      mvc.perform(
              post("/api/v1/jobs")
                  .with(user("multi-validation").roles("REPORT_USER"))
                  .with(csrf())
                  .header("Idempotency-Key", UUID.randomUUID())
                  .contentType("application/json")
                  .content(json.writeValueAsBytes(body)))
          .andExpect(status().isBadRequest());
    assertThat(
            db.queryForObject(
                "SELECT count(*) FROM report_job WHERE owner_subject='multi-validation'",
                Integer.class))
        .isZero();
  }

  @Test
  void yamlAcceptanceSharesIdempotencyWithJsonAndConversionHasNoDurableSideEffects()
      throws Exception {
    var actor = user("yaml-owner").roles("REPORT_USER");
    String yaml = ConfigurationCodecTest.YAML;
    String key = UUID.randomUUID().toString();
    mvc.perform(
            post("/api/v1/configurations/convert?target=json")
                .contentType("application/yaml")
                .content(yaml)
                .with(actor))
        .andExpect(status().isForbidden());
    var converted =
        mvc.perform(
                post("/api/v1/configurations/convert?target=json")
                    .contentType("application/yaml")
                    .content(yaml)
                    .with(actor)
                    .with(csrf()))
            .andExpect(status().isOk())
            .andExpect(header().string("Cache-Control", "no-store"))
            .andReturn();
    assertThat(
            db.queryForObject(
                "SELECT count(*) FROM report_job WHERE owner_subject='yaml-owner'", Integer.class))
        .isZero();
    String equivalent =
        json.readTree(converted.getResponse().getContentAsString()).path("configuration").asText();
    var first =
        mvc.perform(
                post("/api/v1/jobs")
                    .contentType("application/yaml")
                    .content(yaml)
                    .header("Idempotency-Key", key)
                    .with(actor)
                    .with(csrf()))
            .andExpect(status().isAccepted())
            .andReturn();
    var second =
        mvc.perform(
                post("/api/v1/jobs")
                    .contentType("application/json")
                    .content(equivalent)
                    .header("Idempotency-Key", key)
                    .with(actor)
                    .with(csrf()))
            .andExpect(status().isAccepted())
            .andReturn();
    assertThat(json.readTree(first.getResponse().getContentAsString()).path("id"))
        .isEqualTo(json.readTree(second.getResponse().getContentAsString()).path("id"));
    assertThat(
            db.queryForObject(
                "SELECT count(*) FROM report_job WHERE owner_subject='yaml-owner'", Integer.class))
        .isEqualTo(1);
    mvc.perform(
            post("/api/v1/jobs")
                .contentType("application/yaml")
                .content(yaml.replace("title: yes", "title: changed"))
                .header("Idempotency-Key", key)
                .with(actor)
                .with(csrf()))
        .andExpect(status().isConflict());
    mvc.perform(
            post("/api/v1/configurations/convert?target=json")
                .contentType("application/yaml")
                .content(yaml.replace("title: yes", "title: ''"))
                .with(actor)
                .with(csrf()))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errors[0].field").value("parameters.title"));
    mvc.perform(
            post("/api/v1/jobs")
                .contentType("text/plain")
                .content(yaml)
                .header("Idempotency-Key", UUID.randomUUID())
                .with(actor)
                .with(csrf()))
        .andExpect(status().isUnsupportedMediaType());
    mvc.perform(
            post("/api/v1/configurations/convert?target=json")
                .contentType("application/yaml")
                .content("#".repeat(65537))
                .with(actor)
                .with(csrf()))
        .andExpect(status().isPayloadTooLarge());
  }

  @Test
  void definitionHistoryRetirementAndPinnedAcceptance() throws Exception {
    String definitionId = "versioned-" + UUID.randomUUID();
    var configuration =
        Map.of(
            "name",
            "Original name",
            "description",
            "Version one",
            "templateResource",
            DefinitionController.TEMPLATE,
            "enabled",
            true);
    var admin = user("definition-admin").roles("REPORT_USER", "REPORT_ADMIN");
    byte[] create =
        json.writeValueAsBytes(Map.of("id", definitionId, "configuration", configuration));
    mvc.perform(
            post("/api/v1/definitions")
                .with(admin)
                .with(csrf())
                .contentType("application/json")
                .content(create))
        .andExpect(status().isCreated());
    mvc.perform(
            post("/api/v1/definitions")
                .with(admin)
                .with(csrf())
                .contentType("application/json")
                .content(create))
        .andExpect(status().isConflict());
    var request =
        new ReportRequest(
            "1",
            definitionId,
            1,
            new ReportRequest.Parameters("Pinned definition"),
            List.of("PDF"),
            "local-s3");
    String key = UUID.randomUUID().toString();
    var accepted = jobs.submit("definition-owner", key, request);
    UUID id = (UUID) accepted.get("id");
    var replacement =
        Map.of(
            "expectedVersion",
            1,
            "configuration",
            Map.of(
                "name",
                "Retired name",
                "description",
                "Version two",
                "templateResource",
                DefinitionController.TEMPLATE,
                "enabled",
                false));
    byte[] update = json.writeValueAsBytes(replacement);
    mvc.perform(
            put("/api/v1/definitions/" + definitionId)
                .with(admin)
                .with(csrf())
                .contentType("application/json")
                .content(update))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.version").value(2));
    mvc.perform(
            put("/api/v1/definitions/" + definitionId)
                .with(admin)
                .with(csrf())
                .contentType("application/json")
                .content(update))
        .andExpect(status().isConflict());
    mvc.perform(
            get("/api/v1/definitions/" + definitionId + "/versions/1")
                .with(user("reader").roles("REPORT_USER")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.name").value("Original name"));
    assertThat(jobs.submit("definition-owner", key, request).get("id")).isEqualTo(id);
    assertThatThrownBy(() -> jobs.submit("definition-owner", UUID.randomUUID().toString(), request))
        .hasMessageContaining("Definition version unavailable");
    var snapshot = json.readTree((String) jobs.get("definition-owner", id).get("configuration"));
    assertThat(snapshot.path("definition").path("name").asText()).isEqualTo("Original name");
    assertThat(snapshot.path("definition").path("version").asInt()).isEqualTo(1);
    assertThatThrownBy(
            () ->
                db.update(
                    "UPDATE report_definition_version SET name='Tampered' WHERE definition_id=?",
                    definitionId))
        .hasMessageContaining("append-only");
    db.update("UPDATE report_job SET status='RUNNING',attempt=1,render_epoch=1 WHERE id=?", id);
    var execution =
        launcher.run(
            reportJob,
            new JobParametersBuilder()
                .addString("jobId", id.toString(), true)
                .addLong("epoch", 1L, false)
                .toJobParameters());
    assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
    String storage =
        db.queryForObject(
            "SELECT storage_key FROM report_artifact WHERE job_id=?", String.class, id);
    try (var pdf =
        org.apache.pdfbox.Loader.loadPDF(
            java.nio.file.Files.readAllBytes(renderer.path(storage)))) {
      assertThat(new org.apache.pdfbox.text.PDFTextStripper().getText(pdf))
          .contains("Pinned definition", "Atlas notebook");
    }
    assertThat(
            db.queryForObject(
                "SELECT count(*) FROM audit_event WHERE resource_id=? AND"
                    + " action='DEFINITION_UPDATED'",
                Integer.class,
                definitionId))
        .isEqualTo(2);
  }

  @Test
  void definitionAuthorizationUnknownReferencesAndUnsafeTemplates() throws Exception {
    var configuration =
        Map.of(
            "name",
            "Test",
            "description",
            "Test",
            "templateResource",
            DefinitionController.TEMPLATE,
            "enabled",
            true);
    byte[] create =
        json.writeValueAsBytes(
            Map.of("id", "restricted-definition", "configuration", configuration));
    mvc.perform(
            post("/api/v1/definitions")
                .with(user("reader").roles("REPORT_USER"))
                .with(csrf())
                .contentType("application/json")
                .content(create))
        .andExpect(status().isForbidden());
    mvc.perform(
            post("/api/v1/definitions")
                .with(user("admin").roles("REPORT_ADMIN"))
                .contentType("application/json")
                .content(create))
        .andExpect(status().isForbidden());
    var unsafe = new HashMap<String, Object>(configuration);
    unsafe.put("templateResource", "https://example.invalid/template.jrxml");
    mvc.perform(
            post("/api/v1/definitions")
                .with(user("admin").roles("REPORT_ADMIN"))
                .with(csrf())
                .contentType("application/json")
                .content(
                    json.writeValueAsBytes(
                        Map.of("id", "restricted-definition", "configuration", unsafe))))
        .andExpect(status().isBadRequest());
    for (var request :
        List.of(
            new ReportRequest(
                "1",
                "missing-definition",
                1,
                new ReportRequest.Parameters("Test"),
                List.of("PDF"),
                "local-s3"),
            new ReportRequest(
                "1",
                "sales-summary",
                999,
                new ReportRequest.Parameters("Test"),
                List.of("PDF"),
                "local-s3"))) {
      mvc.perform(
              post("/api/v1/jobs")
                  .with(user("reader").roles("REPORT_USER"))
                  .with(csrf())
                  .header("Idempotency-Key", UUID.randomUUID())
                  .contentType("application/json")
                  .content(json.writeValueAsBytes(request)))
          .andExpect(status().isBadRequest());
    }
    assertThat(
            db.queryForObject(
                "SELECT count(*) FROM report_definition WHERE id='restricted-definition'",
                Integer.class))
        .isZero();
  }

  @Test
  void trustedTemplateRegistrationDefaultsToDisabled() throws Exception {
    mvc.perform(
            post("/api/v1/templates")
                .with(user("admin").roles("REPORT_ADMIN"))
                .with(csrf())
                .contentType("application/json")
                .content(
                    json.writeValueAsBytes(
                        Map.of(
                            "name",
                            "Disabled",
                            "jrxml",
                            new String(
                                renderer.bundledTemplate(),
                                java.nio.charset.StandardCharsets.UTF_8),
                            "trustedCodeAcknowledged",
                            true))))
        .andExpect(status().isForbidden());
  }

  @Test
  void catalogSearchIsLiteralPaginatedAndUsesCurrentVersions() throws Exception {
    String prefix = "catalog-" + UUID.randomUUID();
    for (int n = 0; n < 23; n++) {
      db.update(
          "INSERT INTO"
              + " report_definition(id,version,name,description,template_resource,enabled,updated_at)"
              + " VALUES (?,1,'Tied name',?,'templates/sales-summary.jrxml',?,?::timestamptz)",
          prefix + "-" + String.format("%02d", n),
          prefix + " literal%_marker",
          n < 21,
          n == 22 ? "2025-01-02T00:00Z" : "2025-01-01T00:00Z");
    }
    var first =
        json.readTree(
            mvc.perform(
                    get("/api/v1/definition-catalog")
                        .with(user("reader").roles("REPORT_USER"))
                        .param("q", "  " + prefix.toUpperCase() + "  ")
                        .param("status", "enabled"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(21))
                .andReturn()
                .getResponse()
                .getContentAsString());
    assertThat(first.path("items").size()).isEqualTo(20);
    assertThat(first.path("items").get(0).path("id").asText()).isEqualTo(prefix + "-00");
    var second =
        json.readTree(
            mvc.perform(
                    get("/api/v1/definition-catalog")
                        .with(user("reader").roles("REPORT_USER"))
                        .param("q", prefix)
                        .param("status", "enabled")
                        .param("page", "1"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());
    assertThat(second.path("items").size()).isEqualTo(1);
    assertThat(second.path("items").get(0).path("id").asText()).isEqualTo(prefix + "-20");
    mvc.perform(
            get("/api/v1/definition-catalog")
                .with(user("reader").roles("REPORT_USER"))
                .param("q", prefix)
                .param("status", "disabled")
                .param("sort", "updated"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total").value(2))
        .andExpect(jsonPath("$.items[0].id").value(prefix + "-22"));
    mvc.perform(
            get("/api/v1/definition-catalog")
                .with(user("reader").roles("REPORT_USER"))
                .param("q", prefix + " literal%_"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total").value(23));
    for (String query : List.of(prefix + " literal%missing", "' OR true --"))
      mvc.perform(
              get("/api/v1/definition-catalog")
                  .with(user("reader").roles("REPORT_USER"))
                  .param("q", query))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.total").value(0));
    db.update(
        "UPDATE report_definition SET version=2,name=?,updated_at='2025-01-03T00:00Z' WHERE id=?",
        prefix + " revised",
        prefix + "-00");
    mvc.perform(
            get("/api/v1/definition-catalog")
                .with(user("reader").roles("REPORT_USER"))
                .param("q", prefix + " revised"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total").value(1))
        .andExpect(jsonPath("$.items[0].version").value(2));
    mvc.perform(get("/api/v1/definitions").with(user("reader").roles("REPORT_USER")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$").isArray());
  }

  @Test
  void catalogRejectsUnauthorizedAndInvalidFilters() throws Exception {
    mvc.perform(get("/api/v1/definition-catalog")).andExpect(status().isUnauthorized());
    mvc.perform(get("/api/v1/definition-catalog").with(user("outsider").roles("OTHER")))
        .andExpect(status().isForbidden());
    for (var entry :
        List.of(
            Map.entry("q", "x".repeat(101)),
            Map.entry("q", "bad\ninput"),
            Map.entry("page", "-1"),
            Map.entry("page", "10001"),
            Map.entry("status", "unknown"),
            Map.entry("sort", "name;DROP TABLE report_definition")))
      mvc.perform(
              get("/api/v1/definition-catalog")
                  .with(user("reader").roles("REPORT_USER"))
                  .param(entry.getKey(), entry.getValue()))
          .andExpect(status().isBadRequest());
  }

  @Test
  void previewUsesOwnedRetainedPdfWithoutAnotherExecution() throws Exception {
    UUID id =
        stage(
            new ReportRequest(
                "1",
                "sales-summary",
                1,
                new ReportRequest.Parameters("Preview content"),
                List.of("PDF"),
                "local-s3"));
    var artifact = db.queryForMap("SELECT id,storage_key FROM report_artifact WHERE job_id=?", id);
    String base = "/api/v1/jobs/" + id + "/outputs/" + artifact.get("id") + "/preview";
    mvc.perform(get(base)).andExpect(status().isUnauthorized());
    for (String path : List.of(base, base + "/pages/0")) {
      mvc.perform(get(path).with(user("other").roles("REPORT_USER")))
          .andExpect(status().isNotFound());
      mvc.perform(get(path).with(user("admin-not-owner").roles("REPORT_ADMIN", "REPORT_USER")))
          .andExpect(status().isNotFound());
    }
    mvc.perform(get(base).with(user("multi-owner").roles("REPORT_USER")))
        .andExpect(status().isOk())
        .andExpect(header().string("Cache-Control", "no-store"))
        .andExpect(jsonPath("$.pageCount").value(1))
        .andExpect(jsonPath("$.previewPages").value(1));
    byte[] png =
        mvc.perform(get(base + "/pages/0").with(user("multi-owner").roles("REPORT_USER")))
            .andExpect(status().isOk())
            .andExpect(content().contentType("image/png"))
            .andExpect(header().string("X-Content-Type-Options", "nosniff"))
            .andExpect(header().string("Cache-Control", "no-store"))
            .andReturn()
            .getResponse()
            .getContentAsByteArray();
    var image = javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(png));
    assertThat(image.getWidth()).isBetween(700, 900);
    assertThat(image.getHeight()).isBetween(1000, 1200);
    long ink = 0;
    for (int y = 0; y < image.getHeight(); y++)
      for (int x = 0; x < image.getWidth(); x++)
        if ((image.getRGB(x, y) & 0xffffff) != 0xffffff) ink++;
    assertThat(ink).isGreaterThan(1000);
    image.flush();
    for (int page : List.of(-1, 1, 20))
      mvc.perform(get(base + "/pages/" + page).with(user("multi-owner").roles("REPORT_USER")))
          .andExpect(status().isBadRequest());
    assertThat(db.queryForObject("SELECT attempt FROM report_job WHERE id=?", Integer.class, id))
        .isEqualTo(1);
    assertThat(
            db.queryForObject(
                "SELECT count(*) FROM delivery_outbox WHERE job_id=?", Integer.class, id))
        .isEqualTo(1);
    var file = renderer.path(artifact.get("storage_key").toString());
    java.nio.file.Files.writeString(file, "corrupted");
    mvc.perform(get(base).with(user("multi-owner").roles("REPORT_USER")))
        .andExpect(status().isConflict());
    java.nio.file.Files.delete(file);
    mvc.perform(get(base + "/pages/0").with(user("multi-owner").roles("REPORT_USER")))
        .andExpect(status().isGone());
    UUID csv =
        stage(
            new ReportRequest(
                "1",
                "sales-summary",
                1,
                new ReportRequest.Parameters("No CSV preview"),
                List.of("CSV"),
                "local-s3"));
    UUID csvArtifact =
        db.queryForObject("SELECT id FROM report_artifact WHERE job_id=?", UUID.class, csv);
    mvc.perform(
            get("/api/v1/jobs/" + csv + "/outputs/" + csvArtifact + "/preview")
                .with(user("multi-owner").roles("REPORT_USER")))
        .andExpect(status().isUnsupportedMediaType());
    mvc.perform(
            get("/api/v1/jobs/" + csv + "/outputs/" + artifact.get("id") + "/preview")
                .with(user("multi-owner").roles("REPORT_USER")))
        .andExpect(status().isNotFound());
  }
}
