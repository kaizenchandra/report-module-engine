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
class TemplateRegistrationTest {
  @Container static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17.6");

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry r) {
    r.add("spring.datasource.url", postgres::getJdbcUrl);
    r.add("spring.datasource.username", postgres::getUsername);
    r.add("spring.datasource.password", postgres::getPassword);
    r.add(
        "ARTIFACT_ROOT",
        () -> System.getProperty("java.io.tmpdir") + "/report-template-test-artifacts");
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

  String source(String marker) {
    return new String(renderer.bundledTemplate(), java.nio.charset.StandardCharsets.UTF_8)
        .replace("Sales summary | Synthetic demonstration data | USD | UTC", marker);
  }

  String register(String source) throws Exception {
    var result =
        mvc.perform(
                post("/api/v1/templates")
                    .with(user("trusted-author").roles("REPORT_ADMIN"))
                    .with(csrf())
                    .contentType("application/json")
                    .content(
                        json.writeValueAsBytes(
                            Map.of(
                                "name",
                                "Registered layout",
                                "jrxml",
                                source,
                                "trustedCodeAcknowledged",
                                true))))
            .andExpect(status().isCreated())
            .andReturn();
    return json.readTree(result.getResponse().getContentAsString()).path("sha256").asText();
  }

  UUID accept(String owner, String definition, int version) throws Exception {
    return (UUID)
        jobs.submit(
                owner,
                UUID.randomUUID().toString(),
                new ReportRequest(
                    "1",
                    definition,
                    version,
                    new ReportRequest.Parameters("Registered report"),
                    List.of("PDF"),
                    "local-s3"))
            .get("id");
  }

  String render(UUID id) throws Exception {
    db.update("UPDATE report_job SET status='RUNNING',attempt=1,render_epoch=1 WHERE id=?", id);
    var execution =
        launcher.run(
            reportJob,
            new JobParametersBuilder()
                .addString("jobId", id.toString(), true)
                .addLong("epoch", 1L, false)
                .toJobParameters());
    assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
    String key =
        db.queryForObject(
            "SELECT storage_key FROM report_artifact WHERE job_id=?", String.class, id);
    try (var pdf =
        org.apache.pdfbox.Loader.loadPDF(java.nio.file.Files.readAllBytes(renderer.path(key)))) {
      return new org.apache.pdfbox.text.PDFTextStripper().getText(pdf);
    }
  }

  @Test
  void registrationIsContentAddressedImmutableAndSourceAccessIsRestricted() throws Exception {
    String source = source("Immutable source test");
    String sha = register(source);
    assertThat(sha)
        .isEqualTo(Renderer.sha256(source.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    mvc.perform(
            post("/api/v1/templates")
                .with(user("trusted-author").roles("REPORT_ADMIN"))
                .with(csrf())
                .contentType("application/json")
                .content(
                    json.writeValueAsBytes(
                        Map.of(
                            "name",
                            "Another label",
                            "jrxml",
                            source,
                            "trustedCodeAcknowledged",
                            true))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.name").value("Registered layout"))
        .andExpect(jsonPath("$.sha256").value(sha));
    assertThat(
            db.queryForObject(
                "SELECT count(*) FROM audit_event WHERE action='TEMPLATE_REGISTERED' AND"
                    + " resource_id=?",
                Integer.class,
                sha))
        .isEqualTo(1);
    assertThatThrownBy(
            () -> db.update("UPDATE report_template SET jrxml='changed' WHERE sha256=?", sha))
        .hasMessageContaining("immutable");
    assertThatThrownBy(() -> db.update("DELETE FROM report_template WHERE sha256=?", sha))
        .hasMessageContaining("immutable");
    mvc.perform(
            get("/api/v1/templates/" + sha + "/source").with(user("reader").roles("REPORT_USER")))
        .andExpect(status().isForbidden());
    mvc.perform(
            get("/api/v1/templates/" + sha + "/source").with(user("author").roles("REPORT_ADMIN")))
        .andExpect(status().isOk())
        .andExpect(header().string("X-Content-Type-Options", "nosniff"))
        .andExpect(header().string("Content-Disposition", "attachment; filename=" + sha + ".jrxml"))
        .andExpect(content().string(source));
    mvc.perform(get("/api/v1/templates").with(user("reader").roles("REPORT_USER")))
        .andExpect(status().isOk());
    mvc.perform(get("/api/v1/templates?page=-1").with(user("reader").roles("REPORT_USER")))
        .andExpect(status().isBadRequest());
    mvc.perform(get("/api/v1/templates/policy").with(user("reader").roles("REPORT_USER")))
        .andExpect(jsonPath("$.uploadsEnabled").value(true));
  }

  @Test
  void authorRoleCsrfAcknowledgementAndCompilerValidationAreRequired() throws Exception {
    byte[] body =
        json.writeValueAsBytes(
            Map.of(
                "name",
                "Unaccepted",
                "jrxml",
                source("Forbidden fixture"),
                "trustedCodeAcknowledged",
                true));
    mvc.perform(
            post("/api/v1/templates")
                .with(user("reader").roles("REPORT_USER"))
                .with(csrf())
                .contentType("application/json")
                .content(body))
        .andExpect(status().isForbidden());
    mvc.perform(
            post("/api/v1/templates")
                .with(user("author").roles("REPORT_ADMIN"))
                .contentType("application/json")
                .content(body))
        .andExpect(status().isForbidden());
    for (String bad :
        List.of(
            source("Bad compilation").replace("$P{TITLE}", "notValidJava("),
            "<!DOCTYPE jasperReport [<!ENTITY xxe SYSTEM 'file:///etc/passwd'>]>" + source("&xxe;"),
            source("bad field").replace("java.lang.Integer", "java.net.URL"),
            "<jasperReport/>",
            "x".repeat(32769))) {
      mvc.perform(
              post("/api/v1/templates")
                  .with(user("author").roles("REPORT_ADMIN"))
                  .with(csrf())
                  .contentType("application/json")
                  .content(
                      json.writeValueAsBytes(
                          Map.of(
                              "name", "Invalid", "jrxml", bad, "trustedCodeAcknowledged", true))))
          .andExpect(status().isBadRequest());
    }
    mvc.perform(
            post("/api/v1/templates")
                .with(user("author").roles("REPORT_ADMIN"))
                .with(csrf())
                .contentType("application/json")
                .content(
                    json.writeValueAsBytes(
                        Map.of(
                            "name",
                            "Unacknowledged",
                            "jrxml",
                            source("Ack required"),
                            "trustedCodeAcknowledged",
                            false))))
        .andExpect(status().isBadRequest());
    assertThat(
            db.queryForObject(
                "SELECT count(*) FROM report_template WHERE name IN"
                    + " ('Unaccepted','Invalid','Unacknowledged')",
                Integer.class))
        .isZero();
  }

  @Test
  void acceptedTemplateSurvivesDefinitionRevisionAndColdCompilerCache(
      @org.junit.jupiter.api.io.TempDir java.nio.file.Path coldRoot) throws Exception {
    String a = source("PINNED TEMPLATE ALPHA");
    String b = source("NEW TEMPLATE BETA");
    String shaA = register(a);
    String shaB = register(b);
    var admin = user("author").roles("REPORT_ADMIN");
    var configuration =
        new HashMap<String, Object>(
            Map.of(
                "name",
                "Pinned templates",
                "description",
                "Test",
                "templateResource",
                "sha256:" + shaA,
                "enabled",
                true));
    mvc.perform(
            post("/api/v1/definitions")
                .with(admin)
                .with(csrf())
                .contentType("application/json")
                .content(
                    json.writeValueAsBytes(
                        Map.of("id", "template-pinning", "configuration", configuration))))
        .andExpect(status().isCreated());
    UUID old = accept("template-owner", "template-pinning", 1);
    configuration.put("templateResource", "sha256:" + shaB);
    mvc.perform(
            put("/api/v1/definitions/template-pinning")
                .with(admin)
                .with(csrf())
                .contentType("application/json")
                .content(
                    json.writeValueAsBytes(
                        Map.of("expectedVersion", 1, "configuration", configuration))))
        .andExpect(status().isOk());
    UUID revised = accept("template-owner", "template-pinning", 2);
    assertThat(render(old))
        .contains("PINNED TEMPLATE ALPHA", "Atlas notebook")
        .doesNotContain("NEW TEMPLATE BETA");
    assertThat(render(revised))
        .contains("NEW TEMPLATE BETA")
        .doesNotContain("PINNED TEMPLATE ALPHA");
    assertThat(
            json.readTree((String) jobs.get("template-owner", old).get("configuration"))
                .path("templateSha256")
                .asText())
        .isEqualTo(shaA);
    var cold = new Renderer(coldRoot.toString());
    byte[] stored = TemplateController.source(db, "sha256:" + shaA);
    try (var pdf =
        org.apache.pdfbox.Loader.loadPDF(
            cold.renderRegistered(stored, "Cold cache", ReportFormat.PDF))) {
      assertThat(new org.apache.pdfbox.text.PDFTextStripper().getText(pdf))
          .contains("PINNED TEMPLATE ALPHA", "Cold cache");
    }
    assertThatThrownBy(() -> TemplateController.source(db, "sha256:" + "0".repeat(64)))
        .hasMessageContaining("Template version unavailable");
  }
}
