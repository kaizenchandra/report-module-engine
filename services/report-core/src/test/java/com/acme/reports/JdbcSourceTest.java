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

  @Autowired JdbcSalesAdapter adapter;
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
        .contains("postgres-sales-v1", "true")
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
}
