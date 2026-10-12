package com.acme.reports;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
class JobOutcomes {
  private final JdbcTemplate db;

  JobOutcomes(JdbcTemplate db) {
    this.db = db;
  }

  // Caller owns a transaction. Serialize receipts for distinct deliveries of the same job.
  void settle(UUID id) {
    String state =
        db.queryForObject("SELECT status FROM report_job WHERE id=? FOR UPDATE", String.class, id);
    if (!"DELIVERING".equals(state)) return;
    var outputs = db.queryForList("SELECT required,status FROM report_output WHERE job_id=?", id);
    var deliveries =
        db.queryForList("SELECT required,status FROM delivery_outbox WHERE job_id=?", id);
    if (outputs.stream().anyMatch(o -> o.get("status").equals("PENDING"))
        || deliveries.stream().anyMatch(d -> d.get("status").equals("PENDING"))) return;
    boolean requiredFailed =
        outputs.stream()
                .anyMatch(o -> (Boolean) o.get("required") && !o.get("status").equals("AVAILABLE"))
            || deliveries.stream()
                .anyMatch(d -> (Boolean) d.get("required") && !d.get("status").equals("VERIFIED"));
    boolean anyFailed =
        outputs.stream().anyMatch(o -> !o.get("status").equals("AVAILABLE"))
            || deliveries.stream().anyMatch(d -> !d.get("status").equals("VERIFIED"));
    String result = requiredFailed ? "FAILED" : anyFailed ? "PARTIAL_SUCCESS" : "SUCCEEDED";
    db.update(
        "UPDATE report_job SET status=?,stage='complete',failure_code=?,updated_at=now() WHERE"
            + " id=?",
        result,
        requiredFailed ? "REQUIRED_WORK_FAILED" : anyFailed ? "OPTIONAL_WORK_FAILED" : null,
        id);
    db.update(
        "INSERT INTO audit_event(actor,action,resource_id) VALUES ('core',?,?)",
        "JOB_" + result,
        id.toString());
  }
}
