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

  @Test
  void typedParametersBindExactVersionsRecoverIdempotentlyAndReprocess() throws Exception {
    String sha =
        register(new String(TypedParameterTest.fixture(), java.nio.charset.StandardCharsets.UTF_8));
    String definition = "typed-" + UUID.randomUUID();
    var configuration =
        new HashMap<String, Object>(
            Map.of(
                "name",
                "Typed template",
                "description",
                "Binding verification",
                "templateResource",
                "sha256:" + sha,
                "enabled",
                true));
    mvc.perform(
            post("/api/v1/definitions")
                .with(user("author").roles("REPORT_ADMIN"))
                .with(csrf())
                .contentType("application/json")
                .content(
                    json.writeValueAsBytes(
                        Map.of("id", definition, "configuration", configuration))))
        .andExpect(status().isCreated());
    String metadata = "/api/v1/definitions/" + definition + "/versions/1/parameters";
    mvc.perform(get(metadata)).andExpect(status().isUnauthorized());
    mvc.perform(get(metadata).with(user("reader").roles("REPORT_USER")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(5))
        .andExpect(jsonPath("$[0].name").value("P_APPROVED"))
        .andExpect(jsonPath("$[0].type").value("BOOLEAN"));
    mvc.perform(get(metadata.replace("/1/", "/999/")).with(user("reader").roles("REPORT_USER")))
        .andExpect(status().isNotFound());
    var parameters =
        new ReportRequest.Parameters(
            "Typed integration", "en-US", "America/New_York", TypedParameterTest.values());
    var request = new ReportRequest("1", definition, 1, parameters, List.of("PDF"), "local-s3");
    assertThatThrownBy(
            () ->
                jobs.submit(
                    "typed-owner",
                    UUID.randomUUID().toString(),
                    new ReportRequest(
                        "1",
                        definition,
                        1,
                        new ReportRequest.Parameters("Missing"),
                        List.of("PDF"),
                        "local-s3")))
        .hasMessageContaining("exactly match");
    var wrong = new HashMap<>(TypedParameterTest.values());
    wrong.put(
        "P_COUNT",
        new ReportRequest.ParameterValue(
            "TEXT", com.fasterxml.jackson.databind.node.TextNode.valueOf("7")));
    assertThatThrownBy(
            () ->
                jobs.submit(
                    "typed-owner",
                    UUID.randomUUID().toString(),
                    new ReportRequest(
                        "1",
                        definition,
                        1,
                        new ReportRequest.Parameters("Wrong", null, null, wrong),
                        List.of("PDF"),
                        "local-s3")))
        .hasMessageContaining("exactly match");
    assertThat(
            db.queryForObject(
                "SELECT count(*) FROM report_job WHERE owner_subject='typed-owner'", Integer.class))
        .isZero();
    String key = UUID.randomUUID().toString();
    UUID id = (UUID) jobs.submit("typed-owner", key, request).get("id");
    var reversed = new LinkedHashMap<String, ReportRequest.ParameterValue>();
    parameters.values().keySet().stream()
        .sorted(Comparator.reverseOrder())
        .forEach(name -> reversed.put(name, parameters.values().get(name)));
    assertThat(
            jobs.submit(
                    "typed-owner",
                    key,
                    new ReportRequest(
                        "1",
                        definition,
                        1,
                        new ReportRequest.Parameters(
                            parameters.title(),
                            parameters.locale(),
                            parameters.timeZone(),
                            reversed),
                        request.outputs(),
                        request.destinationRef()))
                .get("id"))
        .isEqualTo(id);
    reversed.put(
        "P_COUNT",
        new ReportRequest.ParameterValue(
            "INTEGER", com.fasterxml.jackson.databind.node.IntNode.valueOf(8)));
    assertThatThrownBy(
            () ->
                jobs.submit(
                    "typed-owner",
                    key,
                    new ReportRequest(
                        "1",
                        definition,
                        1,
                        new ReportRequest.Parameters(
                            parameters.title(),
                            parameters.locale(),
                            parameters.timeZone(),
                            reversed),
                        request.outputs(),
                        request.destinationRef())))
        .hasMessageContaining("409");
    configuration.put("templateResource", DefinitionController.TEMPLATE);
    mvc.perform(
            put("/api/v1/definitions/" + definition)
                .with(user("author").roles("REPORT_ADMIN"))
                .with(csrf())
                .contentType("application/json")
                .content(
                    json.writeValueAsBytes(
                        Map.of("expectedVersion", 1, "configuration", configuration))))
        .andExpect(status().isOk());
    mvc.perform(get(metadata.replace("/1/", "/2/")).with(user("reader").roles("REPORT_USER")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(0));
    assertThat(render(id))
        .contains(
            "Count=7:Integer",
            "Rate=12.3400:BigDecimal",
            "Approved=false:Boolean",
            "2024-03-10 00:00 -05:00");
    var snapshot = json.readTree((String) jobs.get("typed-owner", id).get("configuration"));
    assertThat(
            snapshot
                .path("request")
                .path("parameters")
                .path("values")
                .path("P_RATE")
                .path("value")
                .asText())
        .isEqualTo("12.3400");
    // Dispatch is disabled in this PostgreSQL/Batch test. Terminal state is synthetic; bytes are
    // real.
    db.update("UPDATE report_job SET status='SUCCEEDED' WHERE id=?", id);
    UUID child = (UUID) jobs.reprocess("typed-owner", UUID.randomUUID().toString(), id).get("id");
    assertThat(render(child)).contains("Count=7:Integer", "Rate=12.3400:BigDecimal");
  }

  @Test
  void immutablePngClosurePinsJobsAndProtectsBundleDownloads() throws Exception {
    byte[] red = TemplateAssetsTest.png(16, 16, java.awt.Color.RED);
    byte[] blue = TemplateAssetsTest.png(16, 16, java.awt.Color.BLUE);
    String source = TemplateAssetsTest.source();
    String redSha = null, blueSha = null;
    for (byte[] image : List.of(red, blue)) {
      var body =
          Map.of(
              "name",
              "Image version",
              "jrxml",
              source,
              "trustedCodeAcknowledged",
              true,
              "assets",
              Map.of("assets/logo.png", Base64.getEncoder().encodeToString(image)));
      var result =
          mvc.perform(
                  post("/api/v1/templates")
                      .with(user("author").roles("REPORT_ADMIN"))
                      .with(csrf())
                      .contentType("application/json")
                      .content(json.writeValueAsBytes(body)))
              .andExpect(status().isCreated())
              .andExpect(jsonPath("$.asset_count").value(1))
              .andReturn();
      String sha = json.readTree(result.getResponse().getContentAsString()).path("sha256").asText();
      mvc.perform(
              post("/api/v1/templates")
                  .with(user("author").roles("REPORT_ADMIN"))
                  .with(csrf())
                  .contentType("application/json")
                  .content(json.writeValueAsBytes(body)))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.sha256").value(sha));
      if (image == red) redSha = sha;
      else blueSha = sha;
    }
    assertThat(redSha).isNotEqualTo(blueSha);
    String download = "/api/v1/templates/" + redSha + "/bundle";
    mvc.perform(get(download)).andExpect(status().isUnauthorized());
    mvc.perform(get(download).with(user("reader").roles("REPORT_USER")))
        .andExpect(status().isForbidden());
    var archive =
        mvc.perform(get(download).with(user("author").roles("REPORT_ADMIN")))
            .andExpect(status().isOk())
            .andExpect(header().string("Content-Type", "application/zip"))
            .andExpect(header().string("Cache-Control", "no-store"))
            .andReturn()
            .getResponse()
            .getContentAsByteArray();
    var entries = new TreeMap<String, byte[]>();
    try (var zip = new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(archive))) {
      for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry())
        entries.put(entry.getName(), zip.readAllBytes());
    }
    assertThat(entries.keySet()).containsExactly("assets/logo.png", "report.jrxml");
    assertThat(entries.get("assets/logo.png")).isEqualTo(red);
    assertThat(entries.get("report.jrxml"))
        .isEqualTo(source.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    String definition = "png-" + UUID.randomUUID();
    var configuration =
        new HashMap<String, Object>(
            Map.of(
                "name",
                "Image pins",
                "description",
                "Image dependency versioning",
                "templateResource",
                "sha256:" + redSha,
                "enabled",
                true));
    mvc.perform(
            post("/api/v1/definitions")
                .with(user("author").roles("REPORT_ADMIN"))
                .with(csrf())
                .contentType("application/json")
                .content(
                    json.writeValueAsBytes(
                        Map.of("id", definition, "configuration", configuration))))
        .andExpect(status().isCreated());
    UUID accepted = accept("png-owner", definition, 1);
    configuration.put("templateResource", "sha256:" + blueSha);
    mvc.perform(
            put("/api/v1/definitions/" + definition)
                .with(user("author").roles("REPORT_ADMIN"))
                .with(csrf())
                .contentType("application/json")
                .content(
                    json.writeValueAsBytes(
                        Map.of("expectedVersion", 1, "configuration", configuration))))
        .andExpect(status().isOk());
    UUID revised = accept("png-owner", definition, 2);
    render(accepted);
    render(revised);
    for (var entry : Map.of(accepted, 0xff0000, revised, 0x0000ff).entrySet()) {
      String key =
          db.queryForObject(
              "SELECT storage_key FROM report_artifact WHERE job_id=?",
              String.class,
              entry.getKey());
      try (var pdf =
          org.apache.pdfbox.Loader.loadPDF(java.nio.file.Files.readAllBytes(renderer.path(key)))) {
        int count = 0;
        for (var page : pdf.getPages())
          for (var name : page.getResources().getXObjectNames()) {
            var object = page.getResources().getXObject(name);
            if (object instanceof org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject image) {
              count++;
              assertThat(image.getImage().getRGB(0, 0) & 0xffffff).isEqualTo(entry.getValue());
            }
          }
        assertThat(count).isPositive();
      }
    }
    String originalSha = redSha;
    assertThatThrownBy(
            () ->
                db.update(
                    "UPDATE report_template_asset SET content=? WHERE template_sha256=?",
                    blue,
                    originalSha))
        .hasMessageContaining("immutable");
    assertThatThrownBy(
            () ->
                db.update("DELETE FROM report_template_asset WHERE template_sha256=?", originalSha))
        .hasMessageContaining("immutable");
    // Controlled corruption in the disposable test schema proves read-time integrity enforcement.
    db.execute("ALTER TABLE report_template_asset DISABLE TRIGGER template_asset_guard");
    try {
      db.update("UPDATE report_template_asset SET content=? WHERE template_sha256=?", blue, redSha);
      assertThatThrownBy(() -> TemplateController.content(db, "sha256:" + originalSha))
          .hasMessageContaining("TEMPLATE_ASSET_INTEGRITY_FAILURE");
    } finally {
      db.update("UPDATE report_template_asset SET content=? WHERE template_sha256=?", red, redSha);
      db.execute("ALTER TABLE report_template_asset ENABLE TRIGGER template_asset_guard");
    }
    assertThat(TemplateController.content(db, "sha256:" + redSha).fingerprint()).isEqualTo(redSha);
    mvc.perform(
            post("/api/v1/templates")
                .with(user("author").roles("REPORT_ADMIN"))
                .with(csrf())
                .contentType("application/json")
                .content(
                    json.writeValueAsBytes(
                        Map.of(
                            "name",
                            "Missing closure",
                            "jrxml",
                            source,
                            "trustedCodeAcknowledged",
                            true))))
        .andExpect(status().isBadRequest());
    assertThat(
            db.queryForObject(
                "SELECT count(*) FROM report_template WHERE name='Missing closure'", Integer.class))
        .isZero();
  }

  @Test
  void styleVersionsRemainPinnedAcrossDefinitionEdits() throws Exception {
    String source = TemplateStylesTest.source();
    var hashes = new ArrayList<String>();
    for (String color : List.of("#ff0000", "#0000ff")) {
      var response =
          mvc.perform(
                  post("/api/v1/templates")
                      .with(user("author").roles("REPORT_ADMIN"))
                      .with(csrf())
                      .contentType("application/json")
                      .content(
                          json.writeValueAsBytes(
                              Map.of(
                                  "name",
                                  "Named style version",
                                  "jrxml",
                                  source,
                                  "trustedCodeAcknowledged",
                                  true,
                                  "assets",
                                  Map.of(
                                      "assets/brand.jrtx",
                                      Base64.getEncoder()
                                          .encodeToString(TemplateStylesTest.styles(color)))))))
              .andExpect(status().isCreated())
              .andReturn()
              .getResponse();
      hashes.add(json.readTree(response.getContentAsString()).path("sha256").asText());
    }
    assertThat(hashes.get(0)).isNotEqualTo(hashes.get(1));
    String definition = "style-" + UUID.randomUUID();
    var configuration =
        new HashMap<String, Object>(
            Map.of(
                "name",
                "Style versions",
                "description",
                "Pins",
                "templateResource",
                "sha256:" + hashes.get(0),
                "enabled",
                true));
    mvc.perform(
            post("/api/v1/definitions")
                .with(user("author").roles("REPORT_ADMIN"))
                .with(csrf())
                .contentType("application/json")
                .content(
                    json.writeValueAsBytes(
                        Map.of("id", definition, "configuration", configuration))))
        .andExpect(status().isCreated());
    UUID old = accept("style-owner", definition, 1);
    configuration.put("templateResource", "sha256:" + hashes.get(1));
    mvc.perform(
            put("/api/v1/definitions/" + definition)
                .with(user("author").roles("REPORT_ADMIN"))
                .with(csrf())
                .contentType("application/json")
                .content(
                    json.writeValueAsBytes(
                        Map.of("expectedVersion", 1, "configuration", configuration))))
        .andExpect(status().isOk());
    UUID newer = accept("style-owner", definition, 2);
    render(old);
    render(newer);
    String oldKey =
        db.queryForObject(
            "SELECT storage_key FROM report_artifact WHERE job_id=?", String.class, old);
    String newKey =
        db.queryForObject(
            "SELECT storage_key FROM report_artifact WHERE job_id=?", String.class, newer);
    assertThat(
            TemplateStylesTest.redPixels(java.nio.file.Files.readAllBytes(renderer.path(oldKey))))
        .isGreaterThan(50);
    assertThat(
            TemplateStylesTest.redPixels(java.nio.file.Files.readAllBytes(renderer.path(newKey))))
        .isZero();
    var archive = TemplateController.content(db, "sha256:" + hashes.get(0)).archive();
    try (var zip = new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(archive))) {
      boolean found = false;
      for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
        if (entry.getName().equals("assets/brand.jrtx")) {
          found = true;
          assertThat(zip.readAllBytes()).isEqualTo(TemplateStylesTest.styles("#ff0000"));
        }
      }
      assertThat(found).isTrue();
    }
  }

  @Test
  void subreportVersionsRemainPinnedAcrossDefinitionEdits() throws Exception {
    String source = TemplateSubreportsTest.parent();
    var hashes = new ArrayList<String>();
    for (String marker : List.of("Original:", "Revised:")) {
      var response =
          mvc.perform(
                  post("/api/v1/templates")
                      .with(user("author").roles("REPORT_ADMIN"))
                      .with(csrf())
                      .contentType("application/json")
                      .content(
                          json.writeValueAsBytes(
                              Map.of(
                                  "name",
                                  "Named subreport version",
                                  "jrxml",
                                  source,
                                  "trustedCodeAcknowledged",
                                  true,
                                  "assets",
                                  Map.of(
                                      "assets/subreport-row.jrxml",
                                      Base64.getEncoder()
                                          .encodeToString(
                                              new String(
                                                      TemplateSubreportsTest.child(),
                                                      java.nio.charset.StandardCharsets.UTF_8)
                                                  .replace("Child:", marker)
                                                  .getBytes(
                                                      java.nio.charset.StandardCharsets.UTF_8)))))))
              .andExpect(status().isCreated())
              .andReturn()
              .getResponse();
      hashes.add(json.readTree(response.getContentAsString()).path("sha256").asText());
    }
    assertThat(hashes.get(0)).isNotEqualTo(hashes.get(1));
    String definition = "subreport-" + UUID.randomUUID();
    var configuration =
        new HashMap<String, Object>(
            Map.of(
                "name",
                "Subreport versions",
                "description",
                "Pins",
                "templateResource",
                "sha256:" + hashes.get(0),
                "enabled",
                true));
    mvc.perform(
            post("/api/v1/definitions")
                .with(user("author").roles("REPORT_ADMIN"))
                .with(csrf())
                .contentType("application/json")
                .content(
                    json.writeValueAsBytes(
                        Map.of("id", definition, "configuration", configuration))))
        .andExpect(status().isCreated());
    UUID old = accept("subreport-owner", definition, 1);
    configuration.put("templateResource", "sha256:" + hashes.get(1));
    mvc.perform(
            put("/api/v1/definitions/" + definition)
                .with(user("author").roles("REPORT_ADMIN"))
                .with(csrf())
                .contentType("application/json")
                .content(
                    json.writeValueAsBytes(
                        Map.of("expectedVersion", 1, "configuration", configuration))))
        .andExpect(status().isOk());
    UUID newer = accept("subreport-owner", definition, 2);
    assertThat(render(old)).contains("Original: Atlas notebook").doesNotContain("Revised:");
    assertThat(render(newer)).contains("Revised: Atlas notebook").doesNotContain("Original:");
    var archive = TemplateController.content(db, "sha256:" + hashes.get(0)).archive();
    try (var zip = new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(archive))) {
      boolean found = false;
      for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
        if (entry.getName().equals("assets/subreport-row.jrxml")) {
          found = true;
          assertThat(zip.readAllBytes())
              .isEqualTo(
                  new String(
                          TemplateSubreportsTest.child(), java.nio.charset.StandardCharsets.UTF_8)
                      .replace("Child:", "Original:")
                      .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
      }
      assertThat(found).isTrue();
    }
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
  void nestedDescendantVersionsRemainPinnedAcrossDefinitionEdits(boolean dataset) throws Exception {
    String source =
        dataset
            ? new String(
                    TemplateDatasetSubreportsTest.parent(), java.nio.charset.StandardCharsets.UTF_8)
                .replace("assets/subreport-row.jrxml", "assets/nested-section.jrxml")
            : TemplateNestedSubreportsTest.parent();
    var hashes = new ArrayList<String>();
    for (String marker : List.of("Original:", "Revised:")) {
      var response =
          mvc.perform(
                  post("/api/v1/templates")
                      .with(user("author").roles("REPORT_ADMIN"))
                      .with(csrf())
                      .contentType("application/json")
                      .content(
                          json.writeValueAsBytes(
                              Map.of(
                                  "name",
                                  "Named nested version",
                                  "jrxml",
                                  source,
                                  "trustedCodeAcknowledged",
                                  true,
                                  "assets",
                                  Map.of(
                                      "assets/nested-section.jrxml",
                                      Base64.getEncoder()
                                          .encodeToString(TemplateNestedSubreportsTest.section()),
                                      "assets/subreport-row.jrxml",
                                      Base64.getEncoder()
                                          .encodeToString(
                                              new String(
                                                      TemplateSubreportsTest.child(),
                                                      java.nio.charset.StandardCharsets.UTF_8)
                                                  .replace("Child:", marker)
                                                  .getBytes(
                                                      java.nio.charset.StandardCharsets.UTF_8)))))))
              .andExpect(status().isCreated())
              .andReturn()
              .getResponse();
      hashes.add(json.readTree(response.getContentAsString()).path("sha256").asText());
    }
    assertThat(hashes.get(0)).isNotEqualTo(hashes.get(1));
    String definition = "nested-" + UUID.randomUUID();
    var configuration =
        new HashMap<String, Object>(
            Map.of(
                "name",
                "Nested versions",
                "description",
                "Pins",
                "templateResource",
                "sha256:" + hashes.get(0),
                "enabled",
                true));
    mvc.perform(
            post("/api/v1/definitions")
                .with(user("author").roles("REPORT_ADMIN"))
                .with(csrf())
                .contentType("application/json")
                .content(
                    json.writeValueAsBytes(
                        Map.of("id", definition, "configuration", configuration))))
        .andExpect(status().isCreated());
    UUID old = accept("nested-owner", definition, 1);
    configuration.put("templateResource", "sha256:" + hashes.get(1));
    mvc.perform(
            put("/api/v1/definitions/" + definition)
                .with(user("author").roles("REPORT_ADMIN"))
                .with(csrf())
                .contentType("application/json")
                .content(
                    json.writeValueAsBytes(
                        Map.of("expectedVersion", 1, "configuration", configuration))))
        .andExpect(status().isOk());
    UUID newer = accept("nested-owner", definition, 2);
    assertThat(render(old)).contains("Original: Atlas notebook").doesNotContain("Revised:");
    assertThat(render(newer)).contains("Revised: Atlas notebook").doesNotContain("Original:");
    var archive = TemplateController.content(db, "sha256:" + hashes.get(0)).archive();
    try (var zip = new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(archive))) {
      boolean found = false;
      for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
        if (entry.getName().equals("assets/subreport-row.jrxml")) {
          found = true;
          assertThat(zip.readAllBytes())
              .isEqualTo(
                  new String(
                          TemplateSubreportsTest.child(), java.nio.charset.StandardCharsets.UTF_8)
                      .replace("Child:", "Original:")
                      .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
      }
      assertThat(found).isTrue();
    }
  }

  @Test
  void fontVersionsRemainPinnedAndArchivesRetainOriginalProgramBytes() throws Exception {
    var hashes = new ArrayList<String>();
    for (boolean wide : List.of(false, true)) {
      var response =
          mvc.perform(
                  post("/api/v1/templates")
                      .with(user("font-author").roles("REPORT_ADMIN"))
                      .with(csrf())
                      .contentType("application/json")
                      .content(
                          json.writeValueAsBytes(
                              Map.of(
                                  "name",
                                  "Embedded font version",
                                  "jrxml",
                                  TemplateFontsTest.source(),
                                  "trustedCodeAcknowledged",
                                  true,
                                  "assets",
                                  Map.of(
                                      "assets/digits.ttf",
                                      Base64.getEncoder()
                                          .encodeToString(TemplateFontsTest.font(wide)))))))
              .andExpect(status().isCreated())
              .andReturn()
              .getResponse();
      hashes.add(json.readTree(response.getContentAsString()).path("sha256").asText());
    }
    assertThat(hashes.get(0)).isNotEqualTo(hashes.get(1));
    String definition = "font-" + UUID.randomUUID();
    var configuration =
        new HashMap<String, Object>(
            Map.of(
                "name",
                "Font pins",
                "description",
                "Immutable font widths",
                "templateResource",
                "sha256:" + hashes.get(0),
                "enabled",
                true));
    mvc.perform(
            post("/api/v1/definitions")
                .with(user("font-author").roles("REPORT_ADMIN"))
                .with(csrf())
                .contentType("application/json")
                .content(
                    json.writeValueAsBytes(
                        Map.of("id", definition, "configuration", configuration))))
        .andExpect(status().isCreated());
    UUID old = accept("font-owner", definition, 1);
    configuration.put("templateResource", "sha256:" + hashes.get(1));
    mvc.perform(
            put("/api/v1/definitions/" + definition)
                .with(user("font-author").roles("REPORT_ADMIN"))
                .with(csrf())
                .contentType("application/json")
                .content(
                    json.writeValueAsBytes(
                        Map.of("expectedVersion", 1, "configuration", configuration))))
        .andExpect(status().isOk());
    UUID newer = accept("font-owner", definition, 2);
    render(old);
    render(newer);
    String oldKey =
        db.queryForObject(
            "SELECT storage_key FROM report_artifact WHERE job_id=?", String.class, old);
    String newKey =
        db.queryForObject(
            "SELECT storage_key FROM report_artifact WHERE job_id=?", String.class, newer);
    assertThat(
            TemplateFontsTest.embeddedWidth(
                java.nio.file.Files.readAllBytes(renderer.path(oldKey))))
        .isEqualTo(3600);
    assertThat(
            TemplateFontsTest.embeddedWidth(
                java.nio.file.Files.readAllBytes(renderer.path(newKey))))
        .isEqualTo(4500);
    try (var zip =
        new java.util.zip.ZipInputStream(
            new java.io.ByteArrayInputStream(
                TemplateController.content(db, "sha256:" + hashes.get(0)).archive()))) {
      boolean found = false;
      for (var e = zip.getNextEntry(); e != null; e = zip.getNextEntry())
        if (e.getName().equals("assets/digits.ttf")) {
          found = true;
          assertThat(zip.readAllBytes()).isEqualTo(TemplateFontsTest.font(false));
        }
      assertThat(found).isTrue();
    }
  }
}
