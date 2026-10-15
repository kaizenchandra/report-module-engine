package com.acme.reports;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Deployment-only identifiers. SQL fragments, qualification and expressions are never accepted. */
@Component
record JdbcSalesMapping(
    @Value("${REPORT_JDBC_SCHEMA:public}") String schema,
    @Value("${REPORT_JDBC_TABLE:reporting_sales}") String table,
    @Value("${REPORT_JDBC_PRODUCT_COLUMN:product}") String productColumn,
    @Value("${REPORT_JDBC_QUANTITY_COLUMN:quantity}") String quantityColumn,
    @Value("${REPORT_JDBC_AMOUNT_COLUMN:amount}") String amountColumn,
    @Value("${REPORT_JDBC_CATEGORY_COLUMN:category}") String categoryColumn,
    @Value("${REPORT_JDBC_ORDER_COLUMN:id}") String orderColumn) {
  JdbcSalesMapping {
    var names =
        List.of(
            schema,
            table,
            productColumn,
            quantityColumn,
            amountColumn,
            categoryColumn,
            orderColumn);
    if (names.stream().anyMatch(n -> !n.matches("[A-Za-z_][A-Za-z0-9_]{0,62}"))
        || new HashSet<>(
                    List.of(
                        productColumn, quantityColumn, amountColumn, categoryColumn, orderColumn))
                .size()
            != 5)
      throw new IllegalArgumentException("Invalid deployment JDBC mapping configuration");
  }

  static JdbcSalesMapping defaults() {
    return new JdbcSalesMapping(
        "public", "reporting_sales", "product", "quantity", "amount", "category", "id");
  }

  String canonical() {
    try {
      return new ObjectMapper().writeValueAsString(this);
    } catch (Exception e) {
      throw new IllegalStateException("JDBC_MAPPING_SERIALIZATION_FAILED");
    }
  }

  String sha256() {
    return Renderer.sha256(canonical().getBytes(StandardCharsets.UTF_8));
  }

  private static String quote(String identifier) {
    return "\"" + identifier + "\"";
  }

  String select(boolean checkOnly) {
    return "SELECT "
        + String.join(
            ",",
            List.of(
                quote(productColumn),
                quote(quantityColumn),
                quote(amountColumn),
                quote(orderColumn)))
        + " FROM "
        + quote(schema)
        + "."
        + quote(table)
        + " WHERE "
        + quote(categoryColumn)
        + "=?"
        + (checkOnly ? " AND false" : "")
        + " ORDER BY "
        + quote(orderColumn)
        + " LIMIT "
        + (checkOnly ? "0" : "501");
  }
}
