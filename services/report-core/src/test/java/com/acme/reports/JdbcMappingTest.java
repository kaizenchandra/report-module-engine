package com.acme.reports;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.*;
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
class JdbcMappingTest {
  @Container static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17.6");
  @Container static PostgreSQLContainer<?> input = new PostgreSQLContainer<>("postgres:17.6");
  static final JdbcSalesMapping MAPPING =
      new JdbcSalesMapping("Reporting", "Order", "Select", "Units", "Net", "Segment", "Sequence");

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry r) {
    r.add("REPORT_JDBC_HOST", input::getHost);
    r.add("REPORT_JDBC_PORT", input::getFirstMappedPort);
    r.add("REPORT_JDBC_DATABASE", input::getDatabaseName);
    r.add("REPORT_JDBC_USERNAME", () -> "mapped_reader");
    r.add("REPORT_JDBC_PASSWORD", () -> "synthetic-mapped-only");
    r.add("REPORT_JDBC_SSL_MODE", () -> "disable");
    r.add("REPORT_JDBC_SCHEMA", MAPPING::schema);
    r.add("REPORT_JDBC_TABLE", MAPPING::table);
    r.add("REPORT_JDBC_PRODUCT_COLUMN", MAPPING::productColumn);
    r.add("REPORT_JDBC_QUANTITY_COLUMN", MAPPING::quantityColumn);
    r.add("REPORT_JDBC_AMOUNT_COLUMN", MAPPING::amountColumn);
    r.add("REPORT_JDBC_CATEGORY_COLUMN", MAPPING::categoryColumn);
    r.add("REPORT_JDBC_ORDER_COLUMN", MAPPING::orderColumn);
    r.add("spring.datasource.url", postgres::getJdbcUrl);
    r.add("spring.datasource.username", postgres::getUsername);
    r.add("spring.datasource.password", postgres::getPassword);
  }

  @Autowired ObjectMapper json;
  @Autowired JdbcTemplate db;
  @Autowired JdbcImportService importer;
  @Autowired JobService jobs;
  @Autowired org.springframework.test.web.servlet.MockMvc mvc;
  @MockitoSpyBean JdbcSalesAdapter adapter;
  @MockitoBean JwtDecoder decoder;

  @MockitoBean
  org.springframework.security.oauth2.client.registration.ClientRegistrationRepository clients;

  Connection admin() throws Exception {
    return DriverManager.getConnection(
        input.getJdbcUrl(), input.getUsername(), input.getPassword());
  }

  void sql(String sql) throws Exception {
    try (var c = admin();
        var st = c.createStatement()) {
      st.execute(sql);
    }
  }

  @BeforeEach
  void fixture() throws Exception {
    sql(
        "DO $$ BEGIN IF NOT EXISTS(SELECT FROM pg_roles WHERE rolname='mapped_reader') THEN CREATE"
            + " ROLE mapped_reader LOGIN PASSWORD 'synthetic-mapped-only'; END IF; END $$");
    sql("DROP SCHEMA IF EXISTS \"Reporting\" CASCADE");
    sql("CREATE SCHEMA \"Reporting\"");
    sql(
        "CREATE TABLE \"Reporting\".\"Order\"(\"Select\" text,\"Units\" integer,\"Net\""
            + " numeric,\"Segment\" text,\"Sequence\" bigint)");
    sql(
        "INSERT INTO \"Reporting\".\"Order\" VALUES('Second mapped"
            + " row',2,25,'stationery',2),('First mapped"
            + " row',1,12.50,'stationery',1),('Hidden',1,1,'equipment',3)");
    sql("GRANT USAGE ON SCHEMA \"Reporting\", public TO mapped_reader");
    sql("GRANT SELECT ON \"Reporting\".\"Order\" TO mapped_reader");
    sql("DROP TABLE IF EXISTS public.reporting_sales");
    sql(
        "CREATE TABLE public.reporting_sales(id bigint,category text,product text,quantity"
            + " integer,amount numeric)");
    sql(
        "INSERT INTO public.reporting_sales VALUES(1,'stationery','First mapped"
            + " row',1,12.50),(2,'stationery','Second mapped row',2,25)");
    sql("GRANT SELECT ON public.reporting_sales TO mapped_reader");
  }

  @Test
  void quotedMappingReadsRenamedColumnsInOrderAndKeepsBoundCategoryAndStrictTypes()
      throws Exception {
    assertThat(adapter.mapping()).isEqualTo(MAPPING);
    assertThat(adapter.read(null, true).size()).isZero();
    var rows = adapter.read("stationery", false);
    assertThat(rows.size()).isEqualTo(2);
    assertThat(rows.get(0).path("product").asText()).isEqualTo("First mapped row");
    assertThat(rows.get(1).path("amount").decimalValue()).isEqualByComparingTo("25");
    assertThat(rows.toString()).doesNotContain("Hidden");
    assertThatThrownBy(() -> adapter.read("' OR true --", false)).hasMessageContaining("400");
    sql("ALTER TABLE \"Reporting\".\"Order\" ALTER COLUMN \"Units\" TYPE numeric");
    assertThatThrownBy(() -> adapter.read(null, true)).hasMessageContaining("422");
  }

  @Test
  void invalidOrderingFailsBeforeCoreWritesAndSourceErrorsAreRedacted() throws Exception {
    String actor = "order-owner-" + UUID.randomUUID();
    for (String bad : List.of("NULL", "2")) {
      sql(
          "UPDATE \"Reporting\".\"Order\" SET \"Sequence\"="
              + bad
              + " WHERE \"Select\"='First mapped row'");
      assertThatThrownBy(
              () ->
                  importer.snapshot("Ordering", "stationery", actor, UUID.randomUUID().toString()))
          .hasMessageContaining("422");
    }
    for (String table : List.of("structured_source", "source_import", "audit_event")) {
      String actorColumn = table.equals("audit_event") ? "actor" : "created_by";
      assertThat(
              db.queryForObject(
                  "SELECT count(*) FROM " + table + " WHERE " + actorColumn + "=?",
                  Integer.class,
                  actor))
          .isZero();
    }
    sql("ALTER TABLE \"Reporting\".\"Order\" ALTER COLUMN \"Sequence\" TYPE text");
    assertThatThrownBy(() -> adapter.read(null, true)).hasMessageContaining("422");
    sql("REVOKE SELECT ON \"Reporting\".\"Order\" FROM mapped_reader");
    assertThatThrownBy(() -> adapter.read("stationery", false))
        .hasMessageContaining("503")
        .hasMessageNotContaining("Reporting")
        .hasMessageNotContaining("mapped_reader")
        .hasMessageNotContaining("synthetic-mapped-only");
  }

  @Test
  void mappingChangesPreserveOriginalImportKeysAndAcceptedJobSnapshotsOffline() throws Exception {
    String actor = "mapping-owner-" + UUID.randomUUID(), key = UUID.randomUUID().toString();
    var first = importer.snapshot("Mapped data", "stationery", actor, key);
    var original = db.queryForMap("SELECT * FROM source_import WHERE id=?", first.importId());
    assertThat(original)
        .containsEntry("mapping_json", MAPPING.canonical())
        .containsEntry("mapping_sha256", MAPPING.sha256())
        .containsEntry("adapter_version", "postgres-sales-v2");
    var defaults = JdbcSalesMapping.defaults();
    var other =
        new JdbcSalesAdapter(
            input.getHost(),
            input.getFirstMappedPort(),
            input.getDatabaseName(),
            "mapped_reader",
            "synthetic-mapped-only",
            "disable",
            json,
            defaults);
    doReturn(defaults).when(adapter).mapping();
    doAnswer(
            inv -> {
              assertThat(
                      org.springframework.transaction.support.TransactionSynchronizationManager
                          .isActualTransactionActive())
                  .isFalse();
              return other.read(inv.getArgument(0), inv.getArgument(1));
            })
        .when(adapter)
        .read(any(), anyBoolean());
    var replay = importer.snapshot("Mapped data", "stationery", actor, key);
    assertThat(replay.replayed()).isTrue();
    assertThat(replay.importId()).isEqualTo(first.importId());
    var second =
        importer.snapshot("Mapped data", "stationery", actor, UUID.randomUUID().toString());
    assertThat(second.source().get("sha256")).isEqualTo(first.source().get("sha256"));
    assertThat(second.created()).isFalse();
    assertThat(
            db.queryForObject(
                "SELECT mapping_sha256 FROM source_import WHERE id=?",
                String.class,
                second.importId()))
        .isEqualTo(defaults.sha256());
    sql("UPDATE public.reporting_sales SET product='New mapping capture' WHERE id=1");
    String definition = "mapped-" + UUID.randomUUID();
    mvc.perform(
            post("/api/v1/definitions")
                .with(user(actor).roles("REPORT_ADMIN"))
                .with(csrf())
                .contentType("application/json")
                .content(
                    json.writeValueAsBytes(
                        Map.of(
                            "id",
                            definition,
                            "configuration",
                            Map.of(
                                "name",
                                "Mapped live data",
                                "description",
                                "Mapping test",
                                "enabled",
                                true,
                                "templateResource",
                                DefinitionController.TEMPLATE,
                                "dataSourceRef",
                                "jdbc:sales-jdbc:stationery")))))
        .andExpect(status().isCreated());
    var request =
        new ReportRequest(
            "1",
            definition,
            1,
            new ReportRequest.Parameters("Mapping capture"),
            List.of("CSV"),
            "local-s3");
    String jobKey = UUID.randomUUID().toString();
    var job = jobs.submit(actor, jobKey, request);
    var config = json.readTree(job.get("configuration").toString());
    UUID importId = UUID.fromString(config.path("sourceImportId").asText());
    assertThat(
            db.queryForObject(
                "SELECT mapping_sha256 FROM source_import WHERE id=?", String.class, importId))
        .isEqualTo(defaults.sha256());
    sql("REVOKE SELECT ON public.reporting_sales FROM mapped_reader");
    assertThat(jobs.submit(actor, jobKey, request).get("id")).isEqualTo(job.get("id"));
    assertThat(importer.snapshot("Mapped data", "stationery", actor, key).importId())
        .isEqualTo(first.importId());
    assertThatThrownBy(() -> jobs.submit(actor, UUID.randomUUID().toString(), request))
        .hasMessageContaining("503");
    jobs.cancel(actor, (UUID) job.get("id"));
    var child = jobs.reprocess(actor, UUID.randomUUID().toString(), (UUID) job.get("id"));
    var childConfig = json.readTree(child.get("configuration").toString());
    assertThat(childConfig.path("datasetVersion")).isEqualTo(config.path("datasetVersion"));
    assertThat(childConfig.path("sourceImportId")).isEqualTo(config.path("sourceImportId"));
    assertThat(
            StructuredData.load(db, json, childConfig.path("datasetVersion").asText()).toString())
        .contains("New mapping capture");
  }

  @Test
  void legacyMigrationBackfillsOriginalMappingAndKeepsProvenanceAppendOnly() throws Exception {
    String schema = "legacy_mapping_" + UUID.randomUUID().toString().replace("-", "");
    var config =
        org.flywaydb.core.Flyway.configure()
            .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
            .schemas(schema)
            .defaultSchema(schema);
    config.target("24").load().migrate();
    UUID id = UUID.randomUUID();
    String sha = "a".repeat(64);
    try (var c =
            DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        var st = c.createStatement()) {
      c.setSchema(schema);
      st.execute(
          "INSERT INTO structured_source(sha256,name,rows_json,row_count,created_by) VALUES('"
              + sha
              + "','Legacy','[]',1,'legacy')");
      st.execute(
          "INSERT INTO"
              + " source_import(id,source_sha256,profile_ref,adapter_version,category,created_by)"
              + " VALUES('"
              + id
              + "','"
              + sha
              + "','sales-jdbc','postgres-sales-v1','stationery','legacy')");
    }
    config.target("latest").load().migrate();
    try (var c =
            DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        var st = c.createStatement()) {
      c.setSchema(schema);
      try (var result =
          st.executeQuery(
              "SELECT mapping_json,mapping_sha256,adapter_version FROM source_import WHERE id='"
                  + id
                  + "'")) {
        assertThat(result.next()).isTrue();
        assertThat(result.getString(1)).isEqualTo(JdbcSalesMapping.defaults().canonical());
        assertThat(result.getString(2)).isEqualTo(JdbcSalesMapping.defaults().sha256());
        assertThat(result.getString(3)).isEqualTo("postgres-sales-v1");
      }
      assertThatThrownBy(
              () -> st.execute("UPDATE source_import SET category='changed' WHERE id='" + id + "'"))
          .isInstanceOf(SQLException.class);
      assertThatThrownBy(
              () ->
                  st.execute(
                      "INSERT INTO"
                          + " source_import(id,source_sha256,profile_ref,adapter_version,category,created_by,mapping_sha256)"
                          + " VALUES('"
                          + UUID.randomUUID()
                          + "','"
                          + sha
                          + "','sales-jdbc','postgres-sales-v2','stationery','legacy','"
                          + "b".repeat(64)
                          + "')"))
          .isInstanceOf(SQLException.class);
    }
  }

}
