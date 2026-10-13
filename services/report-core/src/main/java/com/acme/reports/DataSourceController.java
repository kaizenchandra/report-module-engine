package com.acme.reports;

import com.fasterxml.jackson.databind.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/data-sources")
class DataSourceController {
  private final JdbcTemplate db;
  private final ObjectMapper json;

  private final SourceRegistry registry;

  DataSourceController(JdbcTemplate db, ObjectMapper json, SourceRegistry registry) {
    this.registry = registry;
    this.db = db;
    this.json = StructuredData.parser(json);
  }

  @GetMapping
  List<Map<String, Object>> list(@RequestParam(defaultValue = "0") int page) {
    if (page < 0 || page > 10000)
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid page");
    return db.queryForList(
        "SELECT sha256,name,row_count,created_at FROM structured_source ORDER BY created_at"
            + " DESC,sha256 LIMIT 20 OFFSET ?",
        page * 20);
  }

  @PostMapping(consumes = "application/json")
  @PreAuthorize("hasRole('REPORT_ADMIN')")
  ResponseEntity<Map<String, Object>> register(@RequestBody byte[] bytes, Authentication actor)
      throws Exception {
    JsonNode root;
    try {
      root =
          json.readTree(
              StandardCharsets.UTF_8
                  .newDecoder()
                  .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                  .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                  .decode(java.nio.ByteBuffer.wrap(bytes))
                  .toString());
    } catch (java.io.IOException e) {
      throw StructuredData.invalid();
    }
    if (root == null || !root.isObject() || root.size() != 2 || !root.path("name").isTextual())
      throw StructuredData.invalid();
    var registered =
        registry.register(root.path("name").asText(), root.path("rows"), actor.getName(), null);
    return ResponseEntity.status(registered.created() ? HttpStatus.CREATED : HttpStatus.OK)
        .body(registered.source());
  }
}
