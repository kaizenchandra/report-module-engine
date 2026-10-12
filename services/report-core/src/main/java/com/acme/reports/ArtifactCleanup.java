package com.acme.reports;

import java.nio.file.*;
import java.time.Instant;
import java.util.UUID;
import javax.sql.DataSource;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
class ArtifactCleanup {
  private final DataSource source;
  private final JdbcTemplate db;
  private final Renderer renderer;

  ArtifactCleanup(DataSource source, JdbcTemplate db, Renderer renderer) {
    this.source = source;
    this.db = db;
    this.renderer = renderer;
  }

  @Scheduled(fixedDelay = 3600000, initialDelay = 60000)
  void scheduled() {
    try {
      sweep(Instant.now());
    } catch (Exception e) {
      LoggerFactory.getLogger(getClass())
          .warn("Artifact cleanup failed: {}", e.getClass().getSimpleName());
    }
  }

  int sweep(Instant now) throws Exception {
    // Same connection lock as the synchronous renderer prevents publication/deletion races.
    try (var connection = source.getConnection();
        var lock = connection.prepareStatement("SELECT pg_try_advisory_lock(72931842)")) {
      try (var result = lock.executeQuery()) {
        result.next();
        if (!result.getBoolean(1)) return 0;
      }
      try (var files = Files.newDirectoryStream(renderer.root())) {
        int removed = 0;
        for (var file : files) {
          String name = file.getFileName().toString();
          if (!name.matches(
                  "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}(\\.[0-9]+)?\\.(pdf|xlsx|csv|docx|html\\.zip)")
              && !name.matches(
                  "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}[0-9]+\\.tmp"))
            continue;
          if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)
              || !Files.getLastModifiedTime(file, LinkOption.NOFOLLOW_LINKS)
                  .toInstant()
                  .isBefore(now.minusSeconds(86400))) continue;
          UUID owner = UUID.fromString(name.substring(0, 36));
          if (db.queryForObject(
                  "SELECT count(*) FROM report_artifact WHERE storage_key=?", Integer.class, name)
              > 0) continue;
          if (db.queryForObject(
                  "SELECT count(*) FROM report_job WHERE id=? AND status IN ('QUEUED','RUNNING')",
                  Integer.class,
                  owner)
              > 0) continue;
          if (Files.deleteIfExists(file)) removed++;
        }
        if (removed > 0)
          LoggerFactory.getLogger(getClass())
              .info("Removed {} unreferenced staging files", removed);
        return removed;
      } finally {
        try (var unlock = connection.prepareStatement("SELECT pg_advisory_unlock(72931842)")) {
          unlock.execute();
        }
      }
    }
  }
}
