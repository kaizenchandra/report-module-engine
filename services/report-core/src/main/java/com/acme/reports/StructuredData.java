package com.acme.reports;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

final class StructuredData {
  static List<Map<String, ?>> rows(JsonNode node) {
    if (!node.isArray() || node.isEmpty() || node.size() > 500) throw invalid();
    var result = new ArrayList<Map<String, ?>>();
    for (var row : node) {
      if (!row.isObject()
          || row.size() != 3
          || !row.path("product").isTextual()
          || !row.path("quantity").isIntegralNumber()
          || !row.path("quantity").canConvertToInt()
          || !row.path("amount").isNumber()) throw invalid();
      String product = row.path("product").textValue();
      int quantity = row.path("quantity").intValue();
      BigDecimal amount = row.path("amount").decimalValue().stripTrailingZeros();
      if (product.isBlank()
          || product.length() > 100
          || product.chars().anyMatch(Character::isISOControl)
          || !product.equals(
              new String(product.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8))
          || quantity < 0
          || quantity > 1000000
          || amount.scale() > 2
          || amount.abs().compareTo(new BigDecimal("999999999.99")) > 0) throw invalid();
      var values = new LinkedHashMap<String, Object>();
      values.put("product", product);
      values.put("quantity", quantity);
      values.put("amount", amount.setScale(2));
      result.add(values);
    }
    return result;
  }

  static ObjectMapper parser(ObjectMapper shared) {
    var copy = shared.copy();
    copy.getFactory()
        .setStreamReadConstraints(
            com.fasterxml.jackson.core.StreamReadConstraints.builder()
                .maxNestingDepth(16)
                .maxStringLength(1000)
                .maxNumberLength(32)
                .build());
    return copy.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
        .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
  }

  static List<Map<String, ?>> load(JdbcTemplate db, ObjectMapper json, String reference) {
    if (!reference.matches("data:[0-9a-f]{64}")) throw invalid();
    var sources =
        db.queryForList(
            "SELECT rows_json FROM structured_source WHERE sha256=?", reference.substring(5));
    if (sources.isEmpty())
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Data source unavailable");
    String payload = sources.getFirst().get("rows_json").toString();
    if (!Renderer.sha256(payload.getBytes(StandardCharsets.UTF_8)).equals(reference.substring(5)))
      throw new IllegalStateException("DATA_SOURCE_INTEGRITY_FAILURE");
    try {
      return rows(parser(json).readTree(payload));
    } catch (java.io.IOException e) {
      throw new IllegalStateException("DATA_SOURCE_INVALID");
    }
  }

  static ResponseStatusException invalid() {
    return new ResponseStatusException(
        HttpStatus.BAD_REQUEST,
        "Invalid structured data. Supply 1–500 rows with product text (1–100 characters), integer"
            + " quantity (0–1000000), and numeric amount (at most two decimal places; absolute"
            + " maximum 999999999.99). No null, missing or extra fields.");
  }
}
