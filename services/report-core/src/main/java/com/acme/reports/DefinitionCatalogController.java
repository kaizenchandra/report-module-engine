package com.acme.reports;

import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/definition-catalog")
class DefinitionCatalogController {
  private final JdbcTemplate db;

  DefinitionCatalogController(JdbcTemplate db) {
    this.db = db;
  }

  @GetMapping
  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public Map<String, Object> list(
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "") String q,
      @RequestParam(defaultValue = "all") String status,
      @RequestParam(defaultValue = "name") String sort) {
    if (page < 0 || page > 10000 || q.length() > 100 || q.chars().anyMatch(Character::isISOControl))
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST,
          "Invalid catalog page or search; search supports up to 100 characters without controls");
    String order =
        switch (sort) {
          case "name" -> "lower(name),id";
          case "updated" -> "updated_at DESC,id DESC";
          default ->
              throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid catalog sort");
        };
    StringBuilder where = new StringBuilder(" WHERE true");
    var args = new ArrayList<Object>();
    switch (status) {
      case "all" -> {}
      case "enabled" -> where.append(" AND enabled=true");
      case "disabled" -> where.append(" AND enabled=false");
      default ->
          throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid catalog status");
    }
    String search = q.strip();
    if (!search.isEmpty()) {
      // Literal substring matching: '%' and '_' have no wildcard meaning.
      where.append(
          " AND (strpos(lower(id),lower(?))>0 OR strpos(lower(name),lower(?))>0 OR"
              + " strpos(lower(description),lower(?))>0)");
      args.addAll(List.of(search, search, search));
    }
    long total =
        Objects.requireNonNull(
            db.queryForObject(
                "SELECT count(*) FROM report_definition" + where, Long.class, args.toArray()));
    args.add(page * 20);
    var items =
        db.queryForList(
            "SELECT id,version,name,description,template_resource,enabled,data_source_ref FROM"
                + " report_definition"
                + where
                + " ORDER BY "
                + order
                + " LIMIT 20 OFFSET ?",
            args.toArray());
    return Map.of("items", items, "total", total, "page", page, "pageSize", 20);
  }
}
