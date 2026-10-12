package com.acme.reports;

import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
class PlatformController {
  private final JdbcTemplate db;

  PlatformController(JdbcTemplate db) {
    this.db = db;
  }

  @GetMapping("/me")
  Map<String, Object> me(Authentication user, CsrfToken csrf) {
    return Map.of(
        "subject",
        user.getName(),
        "roles",
        user.getAuthorities().stream().map(Object::toString).toList(),
        "csrfToken",
        csrf.getToken(),
        "csrfHeader",
        csrf.getHeaderName());
  }

  @GetMapping("/summary")
  Map<String, Object> summary(Authentication user) {
    return Map.of(
        "counts",
        db.queryForList(
            "SELECT status,count(*) AS count FROM report_job WHERE owner_subject=? GROUP BY status",
            user.getName()),
        "timezone",
        "UTC",
        "locale",
        "en-US");
  }
}
