package com.acme.reports;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
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
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

@SpringBootTest(
    properties = {
      "report.dispatch.enabled=false",
      "REPORT_TRUSTED_TEMPLATE_UPLOADS_ENABLED=true",
      "spring.security.oauth2.client.provider.reporting.issuer-uri=",
      "spring.security.oauth2.client.provider.reporting.authorization-uri=http://localhost/authorize",
      "spring.security.oauth2.client.provider.reporting.token-uri=http://localhost/token",
      "spring.security.oauth2.client.provider.reporting.jwk-set-uri=http://localhost/jwks",
      "spring.security.oauth2.client.registration.reporting.client-secret=synthetic-test-only"
    })
@org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
@Testcontainers
class JdbcSourceTest {
  @Container static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17.6");

  @Container static PostgreSQLContainer<?> input = new PostgreSQLContainer<>("postgres:17.6");

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry r) {
    r.add("REPORT_JDBC_HOST", input::getHost);
    r.add("REPORT_JDBC_PORT", input::getFirstMappedPort);
    r.add("REPORT_JDBC_DATABASE", input::getDatabaseName);
    r.add("REPORT_JDBC_USERNAME", () -> "input_reader");
    r.add("REPORT_JDBC_PASSWORD", () -> "synthetic-reader-only");
    r.add("REPORT_JDBC_SSL_MODE", () -> "disable");
    r.add("spring.datasource.url", postgres::getJdbcUrl);
    r.add("spring.datasource.username", postgres::getUsername);
    r.add("spring.datasource.password", postgres::getPassword);
    r.add(
        "ARTIFACT_ROOT",
        () -> System.getProperty("java.io.tmpdir") + "/report-source-test-artifacts");
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

  @MockitoSpyBean JdbcSalesAdapter adapter;
  @MockitoSpyBean SourceRegistry registry;
  @Autowired JdbcImportService importer;
  String product;

  java.sql.Connection inputAdmin() throws Exception {
    return java.sql.DriverManager.getConnection(
        input.getJdbcUrl(), input.getUsername(), input.getPassword());
  }

  @org.junit.jupiter.api.BeforeEach
  void fixture() throws Exception {
    product = "JDBC iris ledger " + UUID.randomUUID();
    try (var c = inputAdmin();
        var st = c.createStatement()) {
      st.execute(
          "DO $$ BEGIN IF NOT EXISTS(SELECT FROM pg_roles WHERE rolname='input_reader') THEN CREATE"
              + " ROLE input_reader LOGIN PASSWORD 'synthetic-reader-only'; END IF; END $$");
      st.execute(
          "CREATE TABLE IF NOT EXISTS reporting_sales(id bigint PRIMARY KEY,category text,product"
              + " text,quantity integer,amount numeric)");
      st.execute("GRANT SELECT ON reporting_sales TO input_reader");
      st.execute("TRUNCATE reporting_sales");
      st.execute(
          "INSERT INTO reporting_sales VALUES(1,'stationery','JDBC iris"
              + " ledger',7,84.25),(2,'equipment','Hidden lamp',1,25.00)");
      try (var update = c.prepareStatement("UPDATE reporting_sales SET product=? WHERE id=1")) {
        update.setString(1, product);
        update.executeUpdate();
      }
    }
  }

  org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder importRequest(
      String category) throws Exception {
    return post("/api/v1/jdbc-source/imports")
        .with(user("jdbc-operator").roles("REPORT_ADMIN", "REPORT_USER"))
        .with(csrf())
        .contentType("application/json")
        .content(json.writeValueAsBytes(Map.of("name", "JDBC fixture", "category", category)));
  }

  @Test
  void authorizationProfileAndLeastPrivilege() throws Exception {
    mvc.perform(get("/api/v1/jdbc-source").with(user("reader").roles("REPORT_USER")))
        .andExpect(status().isForbidden());
    mvc.perform(post("/api/v1/jdbc-source/check").with(user("admin").roles("REPORT_ADMIN")))
        .andExpect(status().isForbidden());
    mvc.perform(importRequest("stationery").with(user("reader").roles("REPORT_USER")))
        .andExpect(status().isForbidden());
    var profile =
        mvc.perform(get("/api/v1/jdbc-source").with(user("admin").roles("REPORT_ADMIN")))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(profile)
        .contains("postgres-sales-v2", "true")
        .doesNotContain(input.getHost(), "password", "username");
    mvc.perform(
            post("/api/v1/jdbc-source/check")
                .with(user("admin").roles("REPORT_ADMIN"))
                .with(csrf()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("AVAILABLE"));
    try (var c = adapter.connect();
        var st = c.createStatement()) {
      assertThat(c.isReadOnly()).isTrue();
      assertThat(c.getTransactionIsolation())
          .isEqualTo(java.sql.Connection.TRANSACTION_REPEATABLE_READ);
      assertThatThrownBy(() -> st.execute("INSERT INTO reporting_sales VALUES(9,'x','x',1,1)"))
          .isInstanceOf(java.sql.SQLException.class);
      c.rollback();
      c.setReadOnly(false);
      assertThatThrownBy(() -> st.execute("INSERT INTO reporting_sales VALUES(9,'x','x',1,1)"))
          .isInstanceOf(java.sql.SQLException.class);
      c.rollback();
    }
    var disabled =
        new JdbcSalesAdapter("", 5432, "report_input", "reader", "", "verify-full", json);
    assertThat(disabled.configured()).isFalse();
    assertThatThrownBy(() -> disabled.read("stationery", false)).hasMessageContaining("503");
  }

  @Test
  void importedSnapshotIsImmutableAndRendersThroughBatch() throws Exception {
    var response =
        mvc.perform(importRequest("stationery"))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse();
    String sha = json.readTree(response.getContentAsString()).path("sha256").asText();
    mvc.perform(importRequest("stationery"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.sha256").value(sha));
    assertThat(
            db.queryForObject(
                "SELECT count(*) FROM source_import WHERE source_sha256=?", Integer.class, sha))
        .isEqualTo(2);
    assertThatThrownBy(
            () ->
                db.update(
                    "UPDATE source_import SET category='tampered' WHERE source_sha256=?", sha))
        .hasMessageContaining("append-only");
    try (var c = inputAdmin();
        var st = c.createStatement()) {
      st.execute("UPDATE reporting_sales SET product='Changed source' WHERE id=1");
    }
    String changed =
        json.readTree(
                mvc.perform(importRequest("stationery"))
                    .andExpect(status().isCreated())
                    .andReturn()
                    .getResponse()
                    .getContentAsString())
            .path("sha256")
            .asText();
    assertThat(changed).isNotEqualTo(sha);
    assertThat(StructuredData.load(db, json, "data:" + sha).getFirst().get("product"))
        .isEqualTo(product);
    String definition = "jdbc-" + UUID.randomUUID();
    db.update(
        "INSERT INTO"
            + " report_definition(id,version,name,description,template_resource,data_source_ref)"
            + " VALUES (?,1,'JDBC','','templates/sales-summary.jrxml',?)",
        definition,
        "data:" + sha);
    var request =
        new ReportRequest(
            "1",
            definition,
            1,
            new ReportRequest.Parameters("JDBC snapshot"),
            List.of("CSV"),
            "local-s3");
    UUID id = (UUID) jobs.submit("jdbc-user", UUID.randomUUID().toString(), request).get("id");
    db.update("UPDATE report_job SET status='RUNNING',attempt=1,render_epoch=1 WHERE id=?", id);
    var execution =
        launcher.run(
            reportJob,
            new JobParametersBuilder()
                .addString("jobId", id.toString())
                .addLong("epoch", 1L, false)
                .toJobParameters());
    assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
    String key =
        db.queryForObject(
            "SELECT storage_key FROM report_artifact WHERE job_id=?", String.class, id);
    assertThat(java.nio.file.Files.readString(renderer.path(key)))
        .contains("JDBC iris ledger", "84.25")
        .doesNotContain("Hidden lamp", "Changed source");
  }

  @Test
  void invalidDataAndUnsafeInputsNeverPersistASnapshot() throws Exception {
    int count = db.queryForObject("SELECT count(*) FROM source_import", Integer.class);
    mvc.perform(importRequest("missing")).andExpect(status().isBadRequest());
    mvc.perform(importRequest("' OR true --")).andExpect(status().isBadRequest());
    mvc.perform(
            importRequest("stationery")
                .content(
                    "{\"name\":\"x\",\"category\":\"stationery\",\"sql\":\"DROP TABLE"
                        + " reporting_sales\"}"))
        .andExpect(status().isBadRequest());
    // Even when invoked internally, category is a bound value, not query syntax.
    assertThatThrownBy(() -> adapter.read("' OR true --", false)).hasMessageContaining("400");
    try (var c = inputAdmin();
        var st = c.createStatement()) {
      st.execute("UPDATE reporting_sales SET quantity=NULL WHERE id=1");
    }
    mvc.perform(importRequest("stationery")).andExpect(status().isBadRequest());
    try (var c = inputAdmin();
        var st = c.createStatement()) {
      st.execute("UPDATE reporting_sales SET quantity=1,amount=1.234 WHERE id=1");
    }
    mvc.perform(importRequest("stationery")).andExpect(status().isBadRequest());
    try (var c = inputAdmin();
        var st = c.createStatement()) {
      st.execute("TRUNCATE reporting_sales");
      st.execute(
          "INSERT INTO reporting_sales SELECT n,'stationery','Item',1,1 FROM generate_series(1,501)"
              + " n");
    }
    mvc.perform(importRequest("stationery")).andExpect(status().isBadRequest());
    assertThat(db.queryForObject("SELECT count(*) FROM source_import", Integer.class))
        .isEqualTo(count);
  }

  @Test
  void blockedQueryTimesOutAndReleasesAdapterPermit() throws Exception {
    try (var c = inputAdmin();
        var st = c.createStatement()) {
      c.setAutoCommit(false);
      st.execute("LOCK TABLE reporting_sales IN ACCESS EXCLUSIVE MODE");
      long start = System.nanoTime();
      var response =
          mvc.perform(importRequest("stationery"))
              .andExpect(status().isServiceUnavailable())
              .andReturn()
              .getResponse()
              .getContentAsString();
      assertThat(response)
          .contains("JDBC source unavailable or query deadline exceeded")
          .doesNotContain("synthetic-reader-only", input.getHost(), "reporting_sales");
      assertThat(java.time.Duration.ofNanos(System.nanoTime() - start))
          .isLessThan(java.time.Duration.ofSeconds(10));
      c.rollback();
    }
    mvc.perform(importRequest("stationery"))
        .andExpect(result -> assertThat(result.getResponse().getStatus()).isIn(200, 201));
  }

  @Test
  void keyedReplayRecoversOriginalImportAfterSourceChangesAndPermissionLoss() throws Exception {
    String key = UUID.randomUUID().toString();
    var first =
        mvc.perform(importRequest("stationery").header("Idempotency-Key", key))
            .andExpect(status().isCreated())
            .andExpect(header().string("Idempotency-Replayed", "false"))
            .andReturn()
            .getResponse();
    var accepted = json.readTree(first.getContentAsString());
    assertThat(accepted.path("importId").asText()).matches("[0-9a-f-]{36}");
    try (var c = inputAdmin();
        var st = c.createStatement()) {
      st.execute("UPDATE reporting_sales SET product='Changed after acceptance' WHERE id=1");
      st.execute("REVOKE SELECT ON reporting_sales FROM input_reader");
    }
    mvc.perform(importRequest("stationery").header("Idempotency-Key", key))
        .andExpect(status().isOk())
        .andExpect(header().string("Idempotency-Replayed", "true"))
        .andExpect(content().json(first.getContentAsString()));
    mvc.perform(importRequest("equipment").header("Idempotency-Key", key))
        .andExpect(status().isConflict());
    mvc.perform(importRequest("stationery").header("Idempotency-Key", "short"))
        .andExpect(status().isBadRequest());
    mvc.perform(importRequest("stationery").header("Idempotency-Key", UUID.randomUUID().toString()))
        .andExpect(status().isServiceUnavailable());
    assertThat(
            db.queryForObject(
                "SELECT count(*) FROM jdbc_import_request WHERE actor='jdbc-operator' AND"
                    + " idempotency_key=?",
                Integer.class,
                key))
        .isEqualTo(1);
    String sha = accepted.path("sha256").asText();
    assertThat(
            db.queryForObject(
                "SELECT count(*) FROM source_import WHERE source_sha256=?", Integer.class, sha))
        .isEqualTo(1);
    try (var c = inputAdmin();
        var st = c.createStatement()) {
      st.execute("GRANT SELECT ON reporting_sales TO input_reader");
    }
    var another =
        mvc.perform(
                importRequest("stationery")
                    .header("Idempotency-Key", key)
                    .with(user("second-operator").roles("REPORT_ADMIN")))
            .andExpect(status().isCreated())
            .andExpect(header().string("Idempotency-Replayed", "false"))
            .andReturn()
            .getResponse();
    assertThat(json.readTree(another.getContentAsString()).path("sha256").asText())
        .isNotEqualTo(sha);
    assertThat(json.readTree(another.getContentAsString()).path("importId").asText())
        .isNotEqualTo(accepted.path("importId").asText());
    assertThatThrownBy(
            () ->
                db.update(
                    "UPDATE jdbc_import_request SET request_hash=? WHERE actor='jdbc-operator' AND"
                        + " idempotency_key=?",
                    "0".repeat(64),
                    key))
        .hasMessageContaining("append-only");
    assertThatThrownBy(
            () ->
                db.update(
                    "DELETE FROM jdbc_import_request WHERE actor='jdbc-operator' AND"
                        + " idempotency_key=?",
                    key))
        .hasMessageContaining("append-only");
  }

  @Test
  void concurrentDifferentReadsPublishOneImmutableKeyedImport() throws Exception {
    String actor = "concurrent-" + UUID.randomUUID(), key = UUID.randomUUID().toString();
    var firstRead = new CountDownLatch(1);
    var bothRead = new CyclicBarrier(2);
    var count = new java.util.concurrent.atomic.AtomicInteger();
    var reads = new ConcurrentHashMap<Integer, com.fasterxml.jackson.databind.JsonNode>();
    doAnswer(
            invocation -> {
              assertThat(
                      org.springframework.transaction.support.TransactionSynchronizationManager
                          .isActualTransactionActive())
                  .isFalse();
              int ordinal = count.incrementAndGet();
              if (ordinal == 2) {
                assertThat(firstRead.await(10, TimeUnit.SECONDS)).isTrue();
                try (var c = inputAdmin();
                    var update =
                        c.prepareStatement("UPDATE reporting_sales SET product=? WHERE id=1")) {
                  update.setString(1, product + " newer");
                  update.executeUpdate();
                }
              }
              var rows = (com.fasterxml.jackson.databind.JsonNode) invocation.callRealMethod();
              reads.put(ordinal, rows);
              if (ordinal == 1) firstRead.countDown();
              bothRead.await(10, TimeUnit.SECONDS);
              return rows;
            })
        .when(adapter)
        .read("stationery", false);
    try (var pool = Executors.newFixedThreadPool(2)) {
      var a = pool.submit(() -> importer.snapshot("Concurrent", "stationery", actor, key));
      var b = pool.submit(() -> importer.snapshot("Concurrent", "stationery", actor, key));
      var first = a.get(20, TimeUnit.SECONDS);
      var second = b.get(20, TimeUnit.SECONDS);
      assertThat(first.importId()).isEqualTo(second.importId());
      assertThat(first.source().get("sha256")).isEqualTo(second.source().get("sha256"));
      assertThat(first.replayed() ^ second.replayed()).isTrue();
    }
    String aSha = Renderer.sha256(json.writeValueAsBytes(StructuredData.rows(reads.get(1))));
    String bSha = Renderer.sha256(json.writeValueAsBytes(StructuredData.rows(reads.get(2))));
    assertThat(aSha).isNotEqualTo(bSha);
    assertThat(
            db.queryForObject(
                "SELECT count(*) FROM structured_source WHERE sha256 IN (?,?)",
                Integer.class,
                aSha,
                bSha))
        .isEqualTo(1);
    assertThat(
            db.queryForObject(
                "SELECT count(*) FROM source_import WHERE created_by=?", Integer.class, actor))
        .isEqualTo(1);
    assertThat(
            db.queryForObject(
                "SELECT count(*) FROM jdbc_import_request WHERE actor=?", Integer.class, actor))
        .isEqualTo(1);
    assertThat(
            db.queryForObject(
                "SELECT count(*) FROM audit_event WHERE actor=? AND"
                    + " action='JDBC_SNAPSHOT_IMPORTED'",
                Integer.class,
                actor))
        .isEqualTo(1);
    assertThat(importer.snapshot("Concurrent", "stationery", actor, key).replayed()).isTrue();
    verify(adapter, times(2)).read("stationery", false);
  }

  @Test
  void coreFailureRollsBackSnapshotProvenanceAuditAndKeyTogether() throws Exception {
    String actor = "rollback-" + UUID.randomUUID(), key = UUID.randomUUID().toString();
    doAnswer(
            invocation -> {
              invocation.callRealMethod();
              throw new IllegalStateException("Injected core persistence interruption");
            })
        .when(registry)
        .register(eq("Rollback"), any(), eq(actor), eq("stationery"));
    try {
      assertThatThrownBy(() -> importer.snapshot("Rollback", "stationery", actor, key))
          .hasMessageContaining("Injected core persistence interruption");
      assertThat(
              db.queryForObject(
                  "SELECT count(*) FROM structured_source WHERE created_by=?",
                  Integer.class,
                  actor))
          .isZero();
      assertThat(
              db.queryForObject(
                  "SELECT count(*) FROM source_import WHERE created_by=?", Integer.class, actor))
          .isZero();
      assertThat(
              db.queryForObject(
                  "SELECT count(*) FROM jdbc_import_request WHERE actor=?", Integer.class, actor))
          .isZero();
      assertThat(
              db.queryForObject(
                  "SELECT count(*) FROM audit_event WHERE actor=?", Integer.class, actor))
          .isZero();
    } finally {
      reset(registry);
    }
    assertThat(importer.snapshot("Rollback", "stationery", actor, key).replayed()).isFalse();
    assertThat(importer.snapshot("Rollback", "stationery", actor, key).replayed()).isTrue();
  }

  String liveDefinition(String category) throws Exception {
    String id = "live-" + UUID.randomUUID();
    mvc.perform(
            post("/api/v1/definitions")
                .with(user("author").roles("REPORT_ADMIN"))
                .with(csrf())
                .contentType("application/json")
                .content(
                    json.writeValueAsBytes(
                        Map.of(
                            "id",
                            id,
                            "configuration",
                            Map.of(
                                "name",
                                "JDBC per job",
                                "description",
                                "Current at submission",
                                "enabled",
                                true,
                                "templateResource",
                                DefinitionController.TEMPLATE,
                                "dataSourceRef",
                                "jdbc:sales-jdbc:" + category)))))
        .andExpect(status().isCreated());
    return id;
  }

  ReportRequest liveRequest(String definition) {
    return new ReportRequest(
        "1",
        definition,
        1,
        new ReportRequest.Parameters("Current JDBC rows"),
        List.of("CSV"),
        "local-s3");
  }

  String renderCsv(UUID id) throws Exception {
    db.update("UPDATE report_job SET status='RUNNING',attempt=1,render_epoch=1 WHERE id=?", id);
    var execution =
        launcher.run(
            reportJob,
            new JobParametersBuilder()
                .addString("jobId", id.toString())
                .addLong("epoch", 1L, false)
                .toJobParameters());
    assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
    String key =
        db.queryForObject(
            "SELECT storage_key FROM report_artifact WHERE job_id=?", String.class, id);
    return java.nio.file.Files.readString(renderer.path(key));
  }

  @Test
  void newJobsReadCurrentRowsButReplayAndReprocessingReusePinnedOfflineSnapshot() throws Exception {
    product = "Live " + UUID.randomUUID().toString().substring(0, 8);
    try (var c = inputAdmin();
        var update = c.prepareStatement("UPDATE reporting_sales SET product=? WHERE id=1")) {
      update.setString(1, product);
      update.executeUpdate();
    }
    String definition = liveDefinition("stationery"),
        actor = "live-owner-" + UUID.randomUUID(),
        key = UUID.randomUUID().toString();
    var request = liveRequest(definition);
    doAnswer(
            invocation -> {
              assertThat(
                      org.springframework.transaction.support.TransactionSynchronizationManager
                          .isActualTransactionActive())
                  .isFalse();
              return invocation.callRealMethod();
            })
        .when(adapter)
        .read("stationery", false);
    var accepted = jobs.submit(actor, key, request);
    UUID original = (UUID) accepted.get("id");
    var pinned = json.readTree(accepted.get("configuration").toString());
    assertThat(pinned.path("definition").path("data_source_ref").asText())
        .isEqualTo("jdbc:sales-jdbc:stationery");
    assertThat(pinned.path("datasetVersion").asText()).matches("data:[0-9a-f]{64}");
    assertThat(
            db.queryForObject(
                "SELECT source_sha256 FROM source_import WHERE id=?",
                String.class,
                UUID.fromString(pinned.path("sourceImportId").asText())))
        .isEqualTo(pinned.path("datasetVersion").asText().substring(5));
    try (var c = inputAdmin();
        var st = c.createStatement()) {
      st.execute("UPDATE reporting_sales SET product='New source value' WHERE id=1");
      st.execute("REVOKE SELECT ON reporting_sales FROM input_reader");
    }
    assertThat(jobs.submit(actor, key, request).get("id")).isEqualTo(original);
    assertThatThrownBy(
            () ->
                jobs.submit(
                    actor,
                    key,
                    new ReportRequest(
                        "1",
                        definition,
                        1,
                        new ReportRequest.Parameters("Different title"),
                        List.of("CSV"),
                        "local-s3")))
        .hasMessageContaining("409");
    assertThatThrownBy(() -> jobs.submit(actor, UUID.randomUUID().toString(), request))
        .hasMessageContaining("503");
    assertThat(renderCsv(original)).contains(product).doesNotContain("New source value");
    jobs.cancel(actor, original);
    var child = jobs.reprocess(actor, UUID.randomUUID().toString(), original);
    var childConfig = json.readTree(child.get("configuration").toString());
    assertThat(childConfig.path("datasetVersion")).isEqualTo(pinned.path("datasetVersion"));
    assertThat(childConfig.path("sourceImportId")).isEqualTo(pinned.path("sourceImportId"));
    assertThat(renderCsv((UUID) child.get("id")))
        .contains(product)
        .doesNotContain("New source value");
    verify(adapter, times(2)).read("stationery", false);
    try (var c = inputAdmin();
        var st = c.createStatement()) {
      st.execute("GRANT SELECT ON reporting_sales TO input_reader");
    }
    var newer = jobs.submit(actor, UUID.randomUUID().toString(), request);
    var newerConfig = json.readTree(newer.get("configuration").toString());
    assertThat(newerConfig.path("datasetVersion")).isNotEqualTo(pinned.path("datasetVersion"));
    assertThat(newerConfig.path("sourceImportId")).isNotEqualTo(pinned.path("sourceImportId"));
    assertThat(renderCsv((UUID) newer.get("id")))
        .contains("New source value")
        .doesNotContain(product);
    assertThat(
            db.queryForObject(
                "SELECT count(*) FROM source_import WHERE created_by=?", Integer.class, actor))
        .isEqualTo(2);
  }

  @Test
  void concurrentJobReadsPublishOneJobAndOneImport() throws Exception {
    String definition = liveDefinition("stationery"),
        actor = "live-race-" + UUID.randomUUID(),
        key = UUID.randomUUID().toString();
    var request = liveRequest(definition);
    var firstRead = new CountDownLatch(1);
    var bothRead = new CyclicBarrier(2);
    var ordinal = new java.util.concurrent.atomic.AtomicInteger();
    var reads = new ConcurrentHashMap<Integer, com.fasterxml.jackson.databind.JsonNode>();
    doAnswer(
            invocation -> {
              assertThat(
                      org.springframework.transaction.support.TransactionSynchronizationManager
                          .isActualTransactionActive())
                  .isFalse();
              int number = ordinal.incrementAndGet();
              if (number == 2) {
                assertThat(firstRead.await(10, TimeUnit.SECONDS)).isTrue();
                try (var c = inputAdmin();
                    var st = c.createStatement()) {
                  st.execute(
                      "UPDATE reporting_sales SET product='Concurrent changed source' WHERE id=1");
                }
              }
              var rows = (com.fasterxml.jackson.databind.JsonNode) invocation.callRealMethod();
              reads.put(number, rows);
              if (number == 1) firstRead.countDown();
              bothRead.await(10, TimeUnit.SECONDS);
              return rows;
            })
        .when(adapter)
        .read("stationery", false);
    try (var pool = Executors.newFixedThreadPool(2)) {
      var a = pool.submit(() -> jobs.submit(actor, key, request));
      var b = pool.submit(() -> jobs.submit(actor, key, request));
      assertThat(a.get(20, TimeUnit.SECONDS).get("id"))
          .isEqualTo(b.get(20, TimeUnit.SECONDS).get("id"));
    }
    String a = Renderer.sha256(json.writeValueAsBytes(StructuredData.rows(reads.get(1))));
    String b = Renderer.sha256(json.writeValueAsBytes(StructuredData.rows(reads.get(2))));
    assertThat(a).isNotEqualTo(b);
    assertThat(
            db.queryForObject(
                "SELECT count(*) FROM structured_source WHERE sha256 IN (?,?)",
                Integer.class,
                a,
                b))
        .isEqualTo(1);
    assertThat(
            db.queryForObject(
                "SELECT count(*) FROM report_job WHERE owner_subject=?", Integer.class, actor))
        .isEqualTo(1);
    assertThat(
            db.queryForObject(
                "SELECT count(*) FROM source_import WHERE created_by=?", Integer.class, actor))
        .isEqualTo(1);
    assertThat(
            db.queryForObject(
                "SELECT count(*) FROM audit_event WHERE actor=? AND action='JOB_ACCEPTED'",
                Integer.class,
                actor))
        .isEqualTo(1);
    verify(adapter, times(2)).read("stationery", false);
  }

  @Test
  void admissionRollbackRemovesJobRowsProvenanceAndAuditTogether() throws Exception {
    String definition = liveDefinition("stationery"),
        actor = "live-rollback-" + UUID.randomUUID(),
        key = UUID.randomUUID().toString();
    var request = liveRequest(definition);
    db.execute(
        "CREATE FUNCTION fail_live_output() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF"
            + " EXISTS(SELECT FROM report_job WHERE id=NEW.job_id AND owner_subject='"
            + actor
            + "') THEN RAISE EXCEPTION 'Injected publication failure'; END IF; RETURN NEW; END $$");
    db.execute(
        "CREATE TRIGGER fail_live_output AFTER INSERT ON report_output FOR EACH ROW EXECUTE"
            + " FUNCTION fail_live_output()");
    try {
      assertThatThrownBy(() -> jobs.submit(actor, key, request))
          .hasMessageContaining("Injected publication failure");
      for (String table : List.of("structured_source", "source_import"))
        assertThat(
                db.queryForObject(
                    "SELECT count(*) FROM " + table + " WHERE created_by=?", Integer.class, actor))
            .isZero();
      assertThat(
              db.queryForObject(
                  "SELECT count(*) FROM audit_event WHERE actor=?", Integer.class, actor))
          .isZero();
      assertThat(
              db.queryForObject(
                  "SELECT count(*) FROM report_job WHERE owner_subject=?", Integer.class, actor))
          .isZero();
    } finally {
      db.execute("DROP TRIGGER fail_live_output ON report_output");
      db.execute("DROP FUNCTION fail_live_output()");
    }
    var accepted = jobs.submit(actor, key, request);
    assertThat(jobs.submit(actor, key, request).get("id")).isEqualTo(accepted.get("id"));
  }

  @Test
  void resourceValidationPrecedesReadAndIsRepeatedAfterIt() throws Exception {
    String definition = liveDefinition("stationery"),
        actor = "live-revalidate-" + UUID.randomUUID();
    var request = liveRequest(definition);
    assertThatThrownBy(() -> jobs.submit(actor, "short", request)).hasMessageContaining("400");
    var invalid =
        new ReportRequest(
            "1",
            definition,
            1,
            new ReportRequest.Parameters("Invalid destination"),
            List.of("CSV"),
            "not-available");
    assertThatThrownBy(() -> jobs.submit(actor, UUID.randomUUID().toString(), invalid))
        .hasMessageContaining("Destination unavailable");
    verify(adapter, never()).read(any(), anyBoolean());
    doAnswer(
            invocation -> {
              var rows = invocation.callRealMethod();
              // Publishing the next disabled version must not be blocked by a core lock during
              // JDBC.
              db.update(
                  "UPDATE report_definition SET enabled=false,version=version+1 WHERE id=?",
                  definition);
              return rows;
            })
        .when(adapter)
        .read("stationery", false);
    assertThatThrownBy(() -> jobs.submit(actor, UUID.randomUUID().toString(), request))
        .hasMessageContaining("Definition version unavailable");
    assertThat(
            db.queryForObject(
                "SELECT count(*) FROM source_import WHERE created_by=?", Integer.class, actor))
        .isZero();
    assertThat(
            db.queryForObject(
                "SELECT count(*) FROM report_job WHERE owner_subject=?", Integer.class, actor))
        .isZero();
  }
}
