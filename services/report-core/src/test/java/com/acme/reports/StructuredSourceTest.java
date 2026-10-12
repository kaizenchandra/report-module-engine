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
class StructuredSourceTest {
  @Container static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17.6");

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry r) {
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

  String register(String rows) throws Exception {
    var result =
        mvc.perform(
                post("/api/v1/data-sources")
                    .with(user("source-admin").roles("REPORT_ADMIN"))
                    .with(csrf())
                    .contentType("application/json")
                    .content("{\"name\":\"Structured test\",\"rows\":" + rows + "}"))
            .andExpect(status().isCreated())
            .andReturn();
    return json.readTree(result.getResponse().getContentAsString()).path("sha256").asText();
  }

  String row(String product) throws Exception {
    return json.writeValueAsString(
        List.of(
            Map.of(
                "product", product, "quantity", 7, "amount", new java.math.BigDecimal("123.45"))));
  }

  UUID accept(String definition, int version) throws Exception {
    return (UUID)
        jobs.submit(
                "data-owner",
                UUID.randomUUID().toString(),
                new ReportRequest(
                    "1",
                    definition,
                    version,
                    new ReportRequest.Parameters("Structured report"),
                    List.of("PDF"),
                    "local-s3"))
            .get("id");
  }

  String render(UUID id) throws Exception {
    db.update("UPDATE report_job SET status='RUNNING',attempt=1,render_epoch=1 WHERE id=?", id);
    var result =
        launcher.run(
            reportJob,
            new JobParametersBuilder()
                .addString("jobId", id.toString(), true)
                .addLong("epoch", 1L, false)
                .toJobParameters());
    assertThat(result.getStatus()).isEqualTo(BatchStatus.COMPLETED);
    String key =
        db.queryForObject(
            "SELECT storage_key FROM report_artifact WHERE job_id=?", String.class, id);
    try (var pdf =
        org.apache.pdfbox.Loader.loadPDF(java.nio.file.Files.readAllBytes(renderer.path(key)))) {
      return new org.apache.pdfbox.text.PDFTextStripper().getText(pdf);
    }
  }

  @Test
  void strictValidationRolesCsrfEmptyAndMalformedRows() throws Exception {
    String good = "{\"name\":\"Test\",\"rows\":" + row("Validation row") + "}";
    mvc.perform(
            post("/api/v1/data-sources")
                .with(user("reader").roles("REPORT_USER"))
                .with(csrf())
                .contentType("application/json")
                .content(good))
        .andExpect(status().isForbidden());
    mvc.perform(
            post("/api/v1/data-sources")
                .with(user("admin").roles("REPORT_ADMIN"))
                .contentType("application/json")
                .content(good))
        .andExpect(status().isForbidden());
    for (String rows :
        List.of(
            "[]",
            "[null]",
            "[{\"product\":\"x\",\"quantity\":\"7\",\"amount\":1}]",
            "[{\"product\":\"x\",\"quantity\":1.0,\"amount\":1}]",
            "[{\"product\":\"x\",\"quantity\":1,\"amount\":1.001}]",
            "[{\"product\":\"x\",\"quantity\":1,\"amount\":null}]",
            "[{\"product\":\"x\",\"quantity\":1,\"amount\":1,\"extra\":true}]",
            "[{\"product\":\"x\",\"quantity\":1,\"amount\":1,\"amount\":2}]")) {
      mvc.perform(
              post("/api/v1/data-sources")
                  .with(user("admin").roles("REPORT_ADMIN"))
                  .with(csrf())
                  .contentType("application/json")
                  .content("{\"name\":\"Invalid\",\"rows\":" + rows + "}"))
          .andExpect(status().isBadRequest());
    }
    var oversized =
        Collections.nCopies(300, Map.of("product", "p".repeat(100), "quantity", 1, "amount", 1));
    mvc.perform(
            post("/api/v1/data-sources")
                .with(user("admin").roles("REPORT_ADMIN"))
                .with(csrf())
                .contentType("application/json")
                .content(json.writeValueAsBytes(Map.of("name", "Oversized", "rows", oversized))))
        .andExpect(status().isBadRequest());
    mvc.perform(
            post("/api/v1/data-sources")
                .with(user("admin").roles("REPORT_ADMIN"))
                .with(csrf())
                .contentType("application/json")
                .content(good + " {}"))
        .andExpect(status().isBadRequest());
    assertThat(
            db.queryForObject(
                "SELECT count(*) FROM structured_source WHERE name IN"
                    + " ('Test','Invalid','Oversized')",
                Integer.class))
        .isZero();
  }

  @Test
  void equivalentTypedRowsDeduplicateAndCannotBeMutated() throws Exception {
    String sha = register("[{\"product\":\"Canonical\",\"quantity\":2,\"amount\":1.0}]");
    mvc.perform(
            post("/api/v1/data-sources")
                .with(user("admin").roles("REPORT_ADMIN"))
                .with(csrf())
                .contentType("application/json")
                .content(
                    "{\"name\":\"New"
                        + " label\",\"rows\":[{\"amount\":1,\"quantity\":2,\"product\":\"Canonical\"}]}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.sha256").value(sha))
        .andExpect(jsonPath("$.name").value("Structured test"));
    assertThatThrownBy(
            () -> db.update("UPDATE structured_source SET name='changed' WHERE sha256=?", sha))
        .hasMessageContaining("immutable");
    assertThatThrownBy(() -> db.update("DELETE FROM structured_source WHERE sha256=?", sha))
        .hasMessageContaining("immutable");
    assertThat(
            db.queryForObject(
                "SELECT count(*) FROM audit_event WHERE action='DATA_SOURCE_REGISTERED' AND"
                    + " resource_id=?",
                Integer.class,
                sha))
        .isEqualTo(1);
    var loaded = StructuredData.load(db, json, "data:" + sha);
    assertThat(loaded.getFirst().get("amount")).isEqualTo(new java.math.BigDecimal("1.00"));
    assertThat(loaded.getFirst().get("quantity")).isInstanceOf(Integer.class);
    assertThatThrownBy(() -> StructuredData.load(db, json, "data:" + "0".repeat(64)))
        .hasMessageContaining("Data source unavailable");
  }

  @Test
  void acceptedRowsRemainPinnedAfterDefinitionRevisionAndProtectSpreadsheets() throws Exception {
    String old = register(row("Registered original"));
    String revised = register(row("Registered revised"));
    var config =
        new HashMap<String, Object>(
            Map.of(
                "name",
                "Data binding",
                "description",
                "Test",
                "templateResource",
                DefinitionController.TEMPLATE,
                "dataSourceRef",
                "data:" + old,
                "enabled",
                true));
    var admin = user("admin").roles("REPORT_ADMIN");
    mvc.perform(
            post("/api/v1/definitions")
                .with(admin)
                .with(csrf())
                .contentType("application/json")
                .content(
                    json.writeValueAsBytes(Map.of("id", "data-binding", "configuration", config))))
        .andExpect(status().isCreated());
    UUID first = accept("data-binding", 1);
    config.put("dataSourceRef", "data:" + revised);
    mvc.perform(
            put("/api/v1/definitions/data-binding")
                .with(admin)
                .with(csrf())
                .contentType("application/json")
                .content(
                    json.writeValueAsBytes(Map.of("expectedVersion", 1, "configuration", config))))
        .andExpect(status().isOk());
    UUID second = accept("data-binding", 2);
    assertThat(render(first))
        .contains("Registered original", "123.45")
        .doesNotContain("Registered revised", "Atlas notebook");
    assertThat(render(second)).contains("Registered revised").doesNotContain("Registered original");
    assertThat(
            json.readTree((String) jobs.get("data-owner", first).get("configuration"))
                .path("datasetVersion")
                .asText())
        .isEqualTo("data:" + old);
    var formula = StructuredData.rows(StructuredData.parser(json).readTree(row("  =1+1")));
    String csv =
        new String(
            renderer.renderData(null, "Formula", ReportFormat.CSV, formula),
            java.nio.charset.StandardCharsets.UTF_8);
    assertThat(csv).contains("'  =1+1", "123.45");
    byte[] xlsx = renderer.renderData(null, "Formula", ReportFormat.XLSX, formula);
    try (var zip = new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(xlsx))) {
      boolean sheet = false;
      for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
        String content = new String(zip.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        if (entry.getName().equals("xl/worksheets/sheet1.xml")) {
          sheet = true;
          assertThat(content).doesNotContain("<f>", "<f ").contains("<v>123.45");
        }
      }
      assertThat(sheet).isTrue();
    }
    config.put("dataSourceRef", "data:" + "0".repeat(64));
    mvc.perform(
            post("/api/v1/definitions")
                .with(admin)
                .with(csrf())
                .contentType("application/json")
                .content(
                    json.writeValueAsBytes(
                        Map.of("id", "unknown-source", "configuration", config))))
        .andExpect(status().isBadRequest());
  }
}
