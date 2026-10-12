package com.acme.reports;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/definitions")
class DefinitionController {
  static final String TEMPLATE = "templates/sales-summary.jrxml";
  private final JdbcTemplate db;

  DefinitionController(JdbcTemplate db) {
    this.db = db;
  }

  record Configuration(
      @NotBlank @Size(max = 100) @Pattern(regexp = "[^\\p{Cntrl}]+") String name,
      @NotNull @Size(max = 1000) String description,
      @NotNull @Pattern(regexp = "templates/sales-summary\\.jrxml|sha256:[0-9a-f]{64}")
          String templateResource,
      @NotNull Boolean enabled,
      @Pattern(regexp = "sample-v1|data:[0-9a-f]{64}") String dataSourceRef) {
    String sourceReference() {
      return dataSourceRef == null ? "sample-v1" : dataSourceRef;
    }
  }

  record Create(
      @NotNull @Pattern(regexp = "[a-z][a-z0-9-]{2,63}") String id,
      @NotNull @Valid Configuration configuration) {}

  record Replace(
      @NotNull @Min(1) @Max(1000000) Integer expectedVersion,
      @NotNull @Valid Configuration configuration) {}

  @GetMapping
  List<Map<String, Object>> list() {
    return db.queryForList(
        "SELECT id,version,name,description,template_resource,enabled,data_source_ref FROM"
            + " report_definition ORDER BY name,id");
  }

  @GetMapping("/{id}")
  Map<String, Object> get(@PathVariable String id) {
    var rows =
        db.queryForList(
            "SELECT id,version,name,description,template_resource,enabled,data_source_ref FROM"
                + " report_definition WHERE id=?",
            id);
    if (rows.isEmpty())
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Definition not found");
    return rows.getFirst();
  }

  @GetMapping("/{id}/versions")
  List<Map<String, Object>> versions(
      @PathVariable String id, @RequestParam(defaultValue = "0") int page) {
    get(id);
    if (page < 0 || page > 10000)
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid page");
    return db.queryForList(
        "SELECT definition_id AS"
            + " id,version,name,description,template_resource,enabled,data_source_ref,recorded_at"
            + " FROM report_definition_version WHERE definition_id=? ORDER BY version DESC LIMIT 20"
            + " OFFSET ?",
        id,
        page * 20);
  }

  @GetMapping("/{id}/versions/{version}")
  Map<String, Object> version(@PathVariable String id, @PathVariable int version) {
    var rows =
        db.queryForList(
            "SELECT definition_id AS"
                + " id,version,name,description,template_resource,enabled,data_source_ref,recorded_at"
                + " FROM report_definition_version WHERE definition_id=? AND version=?",
            id,
            version);
    if (rows.isEmpty())
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Definition version not found");
    return rows.getFirst();
  }

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  @PreAuthorize("hasRole('REPORT_ADMIN')")
  @Transactional
  Map<String, Object> create(@Valid @RequestBody Create request, Authentication actor) {
    var c = request.configuration();
    if (!"sample-v1".equals(c.sourceReference())
        && db.queryForObject(
                "SELECT count(*) FROM structured_source WHERE sha256=?",
                Integer.class,
                c.sourceReference().substring(5))
            == 0)
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Data source unavailable");
    if (!TEMPLATE.equals(c.templateResource())) TemplateController.source(db, c.templateResource());
    if (db.update(
            "INSERT INTO"
                + " report_definition(id,version,name,description,template_resource,enabled,data_source_ref)"
                + " VALUES (?,1,?,?,?,?,?) ON CONFLICT(id) DO NOTHING",
            request.id(),
            c.name(),
            c.description(),
            c.templateResource(),
            c.enabled(),
            c.sourceReference())
        == 0) throw new ResponseStatusException(HttpStatus.CONFLICT, "Definition already exists");
    audit(actor, request.id());
    return get(request.id());
  }

  @PutMapping("/{id}")
  @PreAuthorize("hasRole('REPORT_ADMIN')")
  @Transactional
  Map<String, Object> replace(
      @PathVariable String id, @Valid @RequestBody Replace request, Authentication actor) {
    var c = request.configuration();
    if (!"sample-v1".equals(c.sourceReference())
        && db.queryForObject(
                "SELECT count(*) FROM structured_source WHERE sha256=?",
                Integer.class,
                c.sourceReference().substring(5))
            == 0)
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Data source unavailable");
    if (!TEMPLATE.equals(c.templateResource())) TemplateController.source(db, c.templateResource());
    if (db.update(
            "UPDATE report_definition SET"
                + " name=?,description=?,template_resource=?,enabled=?,data_source_ref=?,version=version+1,updated_at=now()"
                + " WHERE id=? AND version=?",
            c.name(),
            c.description(),
            c.templateResource(),
            c.enabled(),
            c.sourceReference(),
            id,
            request.expectedVersion())
        == 0)
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "Definition changed or no longer exists; reload before saving");
    audit(actor, id);
    return get(id);
  }

  private void audit(Authentication actor, String id) {
    db.update(
        "INSERT INTO audit_event(actor,action,resource_id) VALUES (?,'DEFINITION_UPDATED',?)",
        actor.getName(),
        id);
  }
}
