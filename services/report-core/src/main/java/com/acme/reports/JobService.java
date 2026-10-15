package com.acme.reports;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

@Service
public class JobService {
  private final JdbcTemplate db;
  private final ObjectMapper json;
  private final Renderer renderer;

  private final JdbcSalesAdapter jdbc;
  private final SourceRegistry sources;
  private final TransactionTemplate transaction;

  JobService(
      JdbcTemplate db,
      ObjectMapper json,
      Renderer renderer,
      JdbcSalesAdapter jdbc,
      SourceRegistry sources,
      PlatformTransactionManager manager) {
    this.db = db;
    this.json = json;
    this.renderer = renderer;
    this.jdbc = jdbc;
    this.sources = sources;
    this.transaction = new TransactionTemplate(manager);
  }

  private record Admission(Map<String, Object> job, String category) {}

  @Transactional(propagation = Propagation.NOT_SUPPORTED)
  public Map<String, Object> submit(String subject, String key, ReportRequest request)
      throws Exception {
    // Preflight validates resources and recovers committed keys before any external read.
    var first = admission(subject, key, request, null);
    if (first.job() != null) return first.job();
    // No core transaction, advisory lock or mutable-definition lock spans this bounded read.
    var rows = jdbc.read(first.category(), false);
    return admission(subject, key, request, rows).job();
  }

  private Admission admission(String subject, String key, ReportRequest request, JsonNode rows) {
    return transaction.execute(
        status -> {
          try {
            return admit(subject, key, request, null, rows, null, null);
          } catch (RuntimeException e) {
            throw e;
          } catch (Exception e) {
            throw new IllegalStateException("Job admission failed", e);
          }
        });
  }

  @Transactional(rollbackFor = Exception.class)
  public Map<String, Object> reprocess(String subject, String key, UUID parentId) throws Exception {
    // Owner lookup precedes all diagnostics so another subject cannot probe parent state.
    var parent = get(subject, parentId);
    if (!Set.of("SUCCEEDED", "PARTIAL_SUCCESS", "FAILED", "CANCELLED")
        .contains(parent.get("status")))
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "Only terminal jobs can be reprocessed");
    var resolved = json.readTree((String) parent.get("configuration"));
    var request = json.treeToValue(resolved.path("request"), ReportRequest.class);
    return admit(
            subject,
            key,
            request,
            parentId,
            null,
            resolved.path("datasetVersion").asText(),
            resolved.hasNonNull("sourceImportId")
                ? UUID.fromString(resolved.path("sourceImportId").asText())
                : null)
        .job();
  }

  private Admission admit(
      String subject,
      String key,
      ReportRequest request,
      UUID parentId,
      JsonNode capturedRows,
      String reusedData,
      UUID reusedImport)
      throws Exception {
    if (key == null || !key.matches("[a-zA-Z0-9_-]{8,128}"))
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid idempotency key");
    String canonical = json.writeValueAsString(request);
    String fingerprint =
        Renderer.sha256(
            (parentId == null ? canonical : "reprocess:" + parentId + ":" + canonical)
                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
    // Per-subject serialization makes the quota check and idempotency lookup race-safe.
    db.queryForList("SELECT pg_advisory_xact_lock(hashtextextended(?,0))", subject);
    var existing =
        db.queryForList(
            "SELECT id,request_hash FROM report_job WHERE owner_subject=? AND idempotency_key=?",
            subject,
            key);
    if (!existing.isEmpty()) {
      if (!existing.getFirst().get("request_hash").equals(fingerprint))
        throw new ResponseStatusException(
            HttpStatus.CONFLICT, "Idempotency key has a different request");
      return new Admission(get(subject, (UUID) existing.getFirst().get("id")), null);
    }
    Integer active =
        db.queryForObject(
            "SELECT count(*) FROM report_job WHERE owner_subject=? AND status IN"
                + " ('QUEUED','RUNNING','DELIVERING')",
            Integer.class,
            subject);
    if (active != null && active >= 20)
      throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Active job quota reached");
    if (!request.isMappingValid())
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid output/delivery mappings");
    // Lock the head against retirement while selecting an immutable, explicitly requested version.
    var definitions =
        db.queryForList(
            "SELECT v.definition_id AS"
                + " id,v.version,v.name,v.description,v.template_resource,v.data_source_ref FROM"
                + " report_definition d JOIN report_definition_version v ON v.definition_id=d.id"
                + " WHERE d.id=? AND d.enabled=true AND v.version=? AND v.enabled=true FOR SHARE OF"
                + " d",
            request.definitionId(),
            request.definitionVersion());
    if (definitions.isEmpty())
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Definition version unavailable");
    var definition = definitions.getFirst();
    String templateHash =
        TemplateController.resolve(db, renderer, definition.get("template_resource").toString());
    String templateReference = definition.get("template_resource").toString();
    TrustedTemplate.validateValues(
        DefinitionController.TEMPLATE.equals(templateReference)
            ? renderer.bundledTemplate()
            : TemplateController.source(db, templateReference),
        request.parameters().values());
    String dataReference = definition.get("data_source_ref").toString();
    boolean live = dataReference.startsWith(DefinitionController.JDBC_PREFIX);
    if (live && reusedData != null) {
      if (!reusedData.matches("data:[0-9a-f]{64}") || reusedImport == null)
        throw new IllegalStateException("JDBC_SNAPSHOT_UNAVAILABLE");
      if (db.queryForObject(
              "SELECT count(*) FROM source_import WHERE id=? AND source_sha256=? AND"
                  + " profile_ref='sales-jdbc' AND category=?",
              Integer.class,
              reusedImport,
              reusedData.substring(5),
              definition
                  .get("data_source_ref")
                  .toString()
                  .substring(DefinitionController.JDBC_PREFIX.length()))
          != 1) throw new IllegalStateException("JDBC_SNAPSHOT_UNAVAILABLE");
      dataReference = reusedData;
    }
    if (!live || reusedData != null) {
      if (!"sample-v1".equals(dataReference)) StructuredData.load(db, json, dataReference);
    }
    // Resolve every mutable destination once, at acceptance, under shared locks in stable order.
    var snapshots = new LinkedHashMap<String, Map<String, Object>>();
    for (String reference :
        request.resolvedDeliveries().stream()
            .map(ReportRequest.Delivery::destinationRef)
            .distinct()
            .sorted()
            .toList()) {
      var rows =
          db.queryForList(
              "SELECT id,provider,bucket,prefix,version FROM destination WHERE id=? AND"
                  + " enabled=true FOR SHARE",
              reference);
      if (rows.isEmpty())
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Destination unavailable");
      snapshots.put(reference, rows.getFirst());
    }
    var targets = new HashSet<String>();
    for (var mapping : request.resolvedDeliveries()) {
      var d = snapshots.get(mapping.destinationRef());
      for (String format : mapping.outputs()) {
        if (!targets.add(
            d.get("provider") + ":" + d.get("bucket") + ":" + d.get("prefix") + ":" + format))
          throw new ResponseStatusException(
              HttpStatus.BAD_REQUEST, "Duplicate physical delivery target");
      }
    }
    UUID sourceImport = reusedImport;
    if (live && reusedData == null) {
      String category =
          definition
              .get("data_source_ref")
              .toString()
              .substring(DefinitionController.JDBC_PREFIX.length());
      if (!category.matches("[a-zA-Z0-9][a-zA-Z0-9 _-]{0,39}"))
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid JDBC category");
      if (capturedRows == null) return new Admission(null, category);
      var snapshot = sources.register("JDBC job snapshot", capturedRows, subject, category);
      dataReference = "data:" + snapshot.source().get("sha256");
      sourceImport = snapshot.importId();
    }
    UUID id = UUID.randomUUID();
    Map<String, Object> config =
        new LinkedHashMap<>(
            Map.of(
                "request",
                request,
                "templateSha256",
                templateHash,
                "datasetVersion",
                dataReference,
                "locale",
                request.parameters().resolvedLocale(),
                "timezone",
                request.parameters().resolvedTimeZone()));
    config.put("definition", definition);
    if (sourceImport != null) config.put("sourceImportId", sourceImport);
    if ("1".equals(request.schemaVersion()))
      config.put("destination", snapshots.get(request.destinationRef()));
    else config.put("destinations", snapshots);
    db.update(
        "INSERT INTO"
            + " report_job(id,owner_subject,idempotency_key,request_hash,resolved_config,status,stage,parent_job_id)"
            + " VALUES (?,?,?,?,?::jsonb,'QUEUED','accepted',?)",
        id,
        subject,
        key,
        fingerprint,
        json.writeValueAsString(config),
        parentId);
    for (String format : request.outputs()) {
      db.update(
          "INSERT INTO report_output(job_id,format,required) VALUES (?,?,?)",
          id,
          format,
          request.outputRequired(format));
    }
    db.update(
        "INSERT INTO audit_event(actor,action,resource_id) VALUES (?,'JOB_ACCEPTED',?)",
        subject,
        id.toString());
    if (parentId != null)
      db.update(
          "INSERT INTO audit_event(actor,action,resource_id) VALUES (?,'JOB_REPROCESSED',?)",
          subject,
          id.toString());
    return new Admission(get(subject, id), null);
  }

  Map<String, Object> get(String subject, UUID id) {
    var rows =
        db.queryForList(
            "SELECT"
                + " id,status,stage,attempt,created_at,updated_at,deadline,parent_job_id,failure_code,batch_execution_id,resolved_config::text"
                + " AS configuration FROM report_job WHERE id=? AND owner_subject=?",
            id,
            subject);
    if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Job not found");
    var row = rows.getFirst();
    row.put(
        "artifacts",
        db.queryForList(
            "SELECT id,sha256,size_bytes,media_type,format FROM report_artifact WHERE job_id=?"
                + " ORDER BY format",
            id));
    row.put(
        "deliveries",
        db.queryForList(
            "SELECT"
                + " d.id,d.artifact_id,d.status,d.destination_ref,d.object_key,d.required,d.receipt::text,a.format"
                + " FROM delivery_outbox d JOIN report_artifact a ON a.id=d.artifact_id WHERE"
                + " d.job_id=? ORDER BY a.format,d.destination_ref",
            id));
    row.put(
        "executions",
        db.queryForList(
            "SELECT e.job_execution_id,e.status,e.start_time,e.end_time,e.exit_code FROM"
                + " batch_job_execution e JOIN batch_job_execution_params p ON"
                + " p.job_execution_id=e.job_execution_id WHERE p.parameter_name='jobId' AND"
                + " p.parameter_value=? ORDER BY e.job_execution_id",
            id.toString()));
    row.put(
        "deliveryAttempts",
        db.queryForList(
            "SELECT a.delivery_id,a.number,a.phase,a.status,a.code,a.started_at,a.ended_at FROM"
                + " delivery_attempt a JOIN delivery_outbox d ON d.id=a.delivery_id WHERE"
                + " d.job_id=? ORDER BY a.delivery_id,a.number",
            id));
    row.put(
        "outputs",
        db.queryForList(
            "SELECT format,required,status,failure_code FROM report_output WHERE job_id=? ORDER BY"
                + " format",
            id));
    return row;
  }

  Map<String, Object> reprocessings(String subject, UUID parentId, int page) {
    get(subject, parentId);
    if (page < 0 || page > 10000)
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid page");
    return Map.of(
        "items",
        db.queryForList(
            "SELECT id,status,stage,attempt,created_at,updated_at,parent_job_id FROM report_job"
                + " WHERE owner_subject=? AND parent_job_id=? ORDER BY created_at DESC,id LIMIT 20"
                + " OFFSET ?",
            subject,
            parentId,
            page * 20),
        "total",
        Objects.requireNonNull(
            db.queryForObject(
                "SELECT count(*) FROM report_job WHERE owner_subject=? AND parent_job_id=?",
                Long.class,
                subject,
                parentId)),
        "page",
        page,
        "pageSize",
        20);
  }

  @Transactional(
      readOnly = true,
      isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
  public Map<String, Object> list(
      String subject,
      int page,
      String status,
      String definitionId,
      String acceptedFrom,
      String acceptedBefore,
      String sort) {
    if (page < 0
        || page > 10000
        || (status != null
            && !Set.of(
                    "QUEUED",
                    "RUNNING",
                    "DELIVERING",
                    "SUCCEEDED",
                    "PARTIAL_SUCCESS",
                    "FAILED",
                    "CANCELLED")
                .contains(status)))
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid page or status");
    if (definitionId != null && !definitionId.matches("[a-z0-9][a-z0-9-]{0,63}"))
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid definition ID");
    String order =
        switch (sort) {
          case "newest" -> "created_at DESC,id DESC";
          case "oldest" -> "created_at ASC,id ASC";
          default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid sort");
        };
    var from = historyDate(acceptedFrom);
    var before = historyDate(acceptedBefore);
    if (from != null && before != null && !from.isBefore(before))
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "Accepted before must follow accepted from");
    StringBuilder where = new StringBuilder(" WHERE owner_subject=?");
    var args = new ArrayList<Object>();
    args.add(subject);
    if (status != null) {
      where.append(" AND status=?");
      args.add(status);
    }
    if (definitionId != null) {
      where.append(" AND resolved_config->'request'->>'definitionId'=?");
      args.add(definitionId);
    }
    if (from != null) {
      where.append(" AND created_at>=?");
      args.add(from);
    }
    if (before != null) {
      where.append(" AND created_at<?");
      args.add(before);
    }
    long total =
        Objects.requireNonNull(
            db.queryForObject(
                "SELECT count(*) FROM report_job" + where, Long.class, args.toArray()));
    args.add(page * 20);
    var items =
        db.queryForList(
            "SELECT id,status,stage,attempt,created_at,updated_at,failure_code FROM report_job"
                + where
                + " ORDER BY "
                + order
                + " LIMIT 20 OFFSET ?",
            args.toArray());
    return Map.of("items", items, "total", total, "page", page, "pageSize", 20);
  }

  private static java.time.OffsetDateTime historyDate(String value) {
    if (value == null) return null;
    try {
      if (!value.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}") || value.startsWith("0000"))
        throw new IllegalArgumentException();
      return java.time.LocalDate.parse(value).atStartOfDay().atOffset(java.time.ZoneOffset.UTC);
    } catch (IllegalArgumentException | java.time.DateTimeException e) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "History dates must be valid YYYY-MM-DD UTC dates");
    }
  }

  @Transactional
  public void cancel(String subject, UUID id) {
    get(subject, id);
    int changed =
        db.update(
            "UPDATE report_job SET status='CANCELLED',stage='cancelled',updated_at=now() WHERE id=?"
                + " AND owner_subject=? AND status IN ('QUEUED','RUNNING','DELIVERING')",
            id,
            subject);
    if (changed == 0)
      throw new ResponseStatusException(HttpStatus.CONFLICT, "Job is already terminal");
    db.update(
        "UPDATE delivery_outbox SET status='CANCELLED',updated_at=now() WHERE job_id=? AND"
            + " status='PENDING'",
        id);
    db.update(
        "UPDATE report_output SET status='CANCELLED' WHERE job_id=? AND status='PENDING'", id);
    db.update(
        "INSERT INTO audit_event(actor,action,resource_id) VALUES (?,'JOB_CANCELLED',?)",
        subject,
        id.toString());
  }
}
