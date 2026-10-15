package com.acme.reports;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.util.*;
import javax.sql.DataSource;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.*;
import org.springframework.batch.core.explore.JobExplorer;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.*;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;

@Configuration
@EnableScheduling
class ReportBatch {
  @Bean
  Job reportJob(
      JobRepository repository,
      PlatformTransactionManager transactionManager,
      JdbcTemplate db,
      Renderer renderer,
      ObjectMapper json,
      JobOutcomes outcomes) {
    Step render =
        new StepBuilder("render-and-stage", repository)
            .tasklet(
                (contribution, context) -> {
                  UUID id =
                      UUID.fromString(
                          context.getStepContext().getJobParameters().get("jobId").toString());
                  long epoch =
                      Long.parseLong(
                          context.getStepContext().getJobParameters().get("epoch").toString());
                  String config =
                      db.queryForObject(
                          "SELECT resolved_config::text FROM report_job WHERE id=?",
                          String.class,
                          id);
                  var resolved = json.readTree(config);
                  String reference =
                      resolved
                          .path("definition")
                          .path("template_resource")
                          .asText(DefinitionController.TEMPLATE);
                  var templateContent =
                      DefinitionController.TEMPLATE.equals(reference)
                          ? null
                          : TemplateController.content(db, reference);
                  byte[] registeredSource =
                      DefinitionController.TEMPLATE.equals(reference)
                          ? null
                          : templateContent.renderSource();
                  String actualHash =
                      registeredSource == null
                          ? renderer.templateHash()
                          : templateContent.fingerprint();
                  if (!actualHash.equals(resolved.path("templateSha256").asText()))
                    throw new IllegalStateException("TEMPLATE_VERSION_UNAVAILABLE");
                  String dataReference = resolved.path("datasetVersion").asText("sample-v1");
                  var rows =
                      "sample-v1".equals(dataReference)
                          ? null
                          : StructuredData.load(db, json, dataReference);
                  var request = json.treeToValue(resolved.path("request"), ReportRequest.class);
                  record Staged(UUID artifactId, ReportFormat format, String sha, int size) {}
                  var staged = new ArrayList<Staged>();
                  var failures = new ArrayList<ReportFormat>();
                  for (String name : request.outputs()) {
                    var format = ReportFormat.valueOf(name);
                    UUID artifactId = "1".equals(request.schemaVersion()) ? id : UUID.randomUUID();
                    try {
                      byte[] bytes =
                          request.parameters().values() != null
                              ? renderer.renderRequest(
                                  registeredSource,
                                  request.parameters(),
                                  format,
                                  rows,
                                  request.outputOptions())
                              : request.outputOptions() != null
                                  ? renderer.renderConfigured(
                                      registeredSource,
                                      request.parameters().title(),
                                      format,
                                      rows,
                                      resolved.path("locale").asText("en-US"),
                                      resolved.path("timezone").asText("UTC"),
                                      request.outputOptions())
                                  : (!"en-US".equals(resolved.path("locale").asText("en-US"))
                                          || !"UTC".equals(resolved.path("timezone").asText("UTC")))
                                      ? renderer.renderConfigured(
                                          registeredSource,
                                          request.parameters().title(),
                                          format,
                                          rows,
                                          resolved.path("locale").asText("en-US"),
                                          resolved.path("timezone").asText("UTC"))
                                      : rows != null
                                          ? renderer.renderData(
                                              registeredSource,
                                              request.parameters().title(),
                                              format,
                                              rows)
                                          : registeredSource == null
                                              ? renderer.render(
                                                  request.parameters().title(), format)
                                              : renderer.renderRegistered(
                                                  registeredSource,
                                                  request.parameters().title(),
                                                  format);
                      renderer.persist(artifactId, epoch, bytes, format);
                      staged.add(
                          new Staged(artifactId, format, Renderer.sha256(bytes), bytes.length));
                    } catch (net.sf.jasperreports.engine.JRException | java.io.IOException e) {
                      // Each export is atomic; an optional failure must not discard other formats.
                      // Error messages may contain source data, so expose only a bounded
                      // diagnostic.
                      failures.add(format);
                    }
                  }
                  // Lock only after expensive generation: cancellation remains available while
                  // Jasper runs.
                  String status =
                      db.queryForObject(
                          "SELECT status FROM report_job WHERE id=? FOR UPDATE", String.class, id);
                  Boolean expired =
                      db.queryForObject(
                          "SELECT deadline<now() FROM report_job WHERE id=?", Boolean.class, id);
                  Long currentEpoch =
                      db.queryForObject(
                          "SELECT render_epoch FROM report_job WHERE id=?", Long.class, id);
                  if (!Objects.equals(currentEpoch, epoch))
                    throw new IllegalStateException("STALE_RENDER_ATTEMPT");
                  if ("CANCELLED".equals(status)) return RepeatStatus.FINISHED;
                  if (!"RUNNING".equals(status))
                    throw new IllegalStateException("INVALID_RENDER_STATE");
                  if (Boolean.TRUE.equals(expired))
                    throw new IllegalStateException("DEADLINE_EXCEEDED");
                  for (var failure : failures) {
                    db.update(
                        "UPDATE report_output SET status='FAILED',failure_code='EXPORT_FAILED'"
                            + " WHERE job_id=? AND format=?",
                        id,
                        failure.name());
                  }
                  for (var artifact : staged) {
                    db.update(
                        "INSERT INTO"
                            + " report_artifact(id,job_id,sha256,size_bytes,storage_key,format,media_type)"
                            + " VALUES (?,?,?,?,?,?,?)",
                        artifact.artifactId(),
                        id,
                        artifact.sha(),
                        artifact.size(),
                        artifact.artifactId() + "." + epoch + "." + artifact.format().extension(),
                        artifact.format().name(),
                        artifact.format().mediaType);
                    db.update(
                        "UPDATE report_output SET status='AVAILABLE',failure_code=NULL WHERE"
                            + " job_id=? AND format=?",
                        id,
                        artifact.format().name());
                    for (var mapping : request.resolvedDeliveries()) {
                      if (!mapping.outputs().contains(artifact.format().name())) continue;
                      var destination =
                          "1".equals(request.schemaVersion())
                              ? resolved.path("destination")
                              : resolved.path("destinations").path(mapping.destinationRef());
                      if (destination.isMissingNode())
                        throw new IllegalStateException("DESTINATION_SNAPSHOT_MISSING");
                      UUID deliveryId =
                          "1".equals(request.schemaVersion()) ? id : UUID.randomUUID();
                      db.update(
                          "INSERT INTO"
                              + " delivery_outbox(id,job_id,artifact_id,destination_ref,object_key,provider,bucket,required)"
                              + " VALUES (?,?,?,?,?,?,?,?)",
                          deliveryId,
                          id,
                          artifact.artifactId(),
                          mapping.destinationRef(),
                          destination.path("prefix").asText()
                              + artifact.artifactId()
                              + "."
                              + artifact.format().extension(),
                          destination.path("provider").asText(),
                          destination.path("bucket").asText(),
                          mapping.required());
                    }
                  }
                  db.update(
                      "UPDATE report_job SET"
                          + " status='DELIVERING',stage='delivering',updated_at=now(),batch_execution_id=?"
                          + " WHERE id=?",
                      contribution.getStepExecution().getJobExecutionId(),
                      id);
                  db.update(
                      "INSERT INTO audit_event(actor,action,resource_id) VALUES"
                          + " ('renderer','ARTIFACT_STAGED',?)",
                      id.toString());
                  outcomes.settle(id);
                  return RepeatStatus.FINISHED;
                },
                transactionManager)
            .build();
    return new JobBuilder("report-pdf-v1", repository)
        .listener(
            new JobExecutionListener() {
              @Override
              public void beforeJob(JobExecution execution) {
                UUID id = UUID.fromString(execution.getJobParameters().getString("jobId"));
                Long epoch = execution.getJobParameters().getLong("epoch");
                db.update(
                    "UPDATE report_job SET batch_execution_id=? WHERE id=? AND render_epoch=? AND"
                        + " status='RUNNING'",
                    execution.getId(),
                    id,
                    epoch);
              }
            })
        .start(render)
        .build();
  }
}

@Component
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
    name = "report.dispatch.enabled",
    havingValue = "true",
    matchIfMissing = true)
class ReportDispatcher {
  private final DataSource source;
  private final JdbcTemplate db;
  private final JobLauncher launcher;
  private final Job job;
  private final JobExplorer explorer;
  private final JobRepository repository;

  ReportDispatcher(
      DataSource source,
      JdbcTemplate db,
      JobLauncher launcher,
      Job reportJob,
      JobExplorer explorer,
      JobRepository repository) {
    this.source = source;
    this.db = db;
    this.launcher = launcher;
    this.job = reportJob;
    this.explorer = explorer;
    this.repository = repository;
  }

  @Scheduled(fixedDelay = 1000)
  void dispatch() {
    // A connection-owned lock deliberately bounds the first slice to one renderer across instances.
    // Dropping this connection releases ownership. General fenced multi-worker recovery is Phase 2.
    try (var connection = source.getConnection();
        var lock = connection.prepareStatement("SELECT pg_try_advisory_lock(72931842)")) {
      var result = lock.executeQuery();
      result.next();
      if (!result.getBoolean(1)) return;
      try {
        recover();
        db.update(
            "WITH expired AS (UPDATE report_job SET"
                + " status='FAILED',stage='complete',failure_code='DEADLINE_EXCEEDED',updated_at=now()"
                + " WHERE status IN ('QUEUED','DELIVERING') AND deadline<now() RETURNING id) UPDATE"
                + " delivery_outbox SET status='CANCELLED',updated_at=now() WHERE job_id IN (SELECT"
                + " id FROM expired) AND status='PENDING'");
        db.update(
            "UPDATE report_output o SET status='FAILED',failure_code=j.failure_code FROM report_job"
                + " j WHERE j.id=o.job_id AND j.status='FAILED' AND o.status='PENDING'");
        var jobs =
            db.queryForList(
                "SELECT id FROM report_job WHERE status='QUEUED' ORDER BY created_at,id LIMIT 1");
        if (jobs.isEmpty()) return;
        UUID id = (UUID) jobs.getFirst().get("id");
        if (db.update(
                "UPDATE report_job SET"
                    + " status='RUNNING',stage='rendering',attempt=attempt+1,render_epoch=render_epoch+1,updated_at=now()"
                    + " WHERE id=? AND status='QUEUED'",
                id)
            == 0) return;
        long epoch =
            Objects.requireNonNull(
                db.queryForObject(
                    "SELECT render_epoch FROM report_job WHERE id=?", Long.class, id));
        try {
          var execution =
              launcher.run(
                  job,
                  new JobParametersBuilder()
                      .addString("jobId", id.toString(), true)
                      .addLong("epoch", epoch, false)
                      .toJobParameters());
          if (execution.getStatus() != BatchStatus.COMPLETED) fail(id, epoch, "RENDER_FAILED");
        } catch (Exception e) {
          fail(id, epoch, "BATCH_LAUNCH_FAILED");
        }
      } finally {
        try (var unlock = connection.prepareStatement("SELECT pg_advisory_unlock(72931842)")) {
          unlock.execute();
        }
      }
    } catch (Exception e) {
      LoggerFactory.getLogger(getClass())
          .warn("Report dispatch unavailable: {}", e.getClass().getSimpleName());
    }
  }

  private void recover() {
    // Only called while holding the global renderer lock. Increment the fence before recovering
    // Batch metadata so a worker whose lock connection died cannot publish its stale artifact.
    for (var row : db.queryForList("SELECT id,attempt FROM report_job WHERE status='RUNNING'")) {
      UUID id = (UUID) row.get("id");
      db.update(
          "UPDATE report_job SET render_epoch=render_epoch+1 WHERE id=? AND status='RUNNING'", id);
      var executions =
          db.queryForList(
              "SELECT e.job_execution_id FROM batch_job_execution e JOIN batch_job_execution_params"
                  + " p ON p.job_execution_id=e.job_execution_id WHERE p.parameter_name='jobId' AND"
                  + " p.parameter_value=? ORDER BY e.job_execution_id DESC LIMIT 1",
              id.toString());
      if (!executions.isEmpty()) {
        var execution =
            explorer.getJobExecution(
                ((Number) executions.getFirst().get("job_execution_id")).longValue());
        if (execution != null && execution.getStatus().isRunning()) {
          for (var step : execution.getStepExecutions())
            if (step.getStatus().isRunning()) {
              step.setStatus(BatchStatus.FAILED);
              step.setExitStatus(new ExitStatus("FAILED", "WORKER_INTERRUPTED"));
              step.setEndTime(LocalDateTime.now());
              repository.update(step);
            }
          execution.setStatus(BatchStatus.FAILED);
          execution.setExitStatus(new ExitStatus("FAILED", "WORKER_INTERRUPTED"));
          execution.setEndTime(LocalDateTime.now());
          repository.update(execution);
        }
      }
      int attempts = ((Number) row.get("attempt")).intValue();
      db.update(
          "UPDATE report_job SET status=?,stage=?,failure_code=?,updated_at=now() WHERE id=? AND"
              + " status='RUNNING'",
          attempts < 3 ? "QUEUED" : "FAILED",
          attempts < 3 ? "accepted" : "complete",
          attempts < 3 ? null : "RENDER_RETRY_EXHAUSTED",
          id);
    }
  }

  private void fail(UUID id, long epoch, String code) {
    db.update(
        "UPDATE report_job SET status='FAILED',stage='complete',failure_code=?,updated_at=now()"
            + " WHERE id=? AND status='RUNNING' AND render_epoch=?",
        code,
        id,
        epoch);
    db.update(
        "UPDATE report_output SET status='FAILED',failure_code=? WHERE job_id=? AND"
            + " status='PENDING' AND EXISTS(SELECT 1 FROM report_job WHERE id=? AND status='FAILED'"
            + " AND render_epoch=?)",
        code,
        id,
        id,
        epoch);
  }
}
