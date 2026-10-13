package com.acme.reports;

import com.fasterxml.jackson.databind.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.Semaphore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/** Deployment-owned adapter. No caller-controlled URLs, SQL, credentials or driver properties. */
@Component
class JdbcSalesAdapter {
  private final String host, database, username, password, sslMode;
  private final int port;
  private final ObjectMapper json;
  private final Semaphore permits = new Semaphore(2);
  static final String SELECT =
      "SELECT product,quantity,amount FROM public.reporting_sales WHERE category=? ORDER BY id"
          + " LIMIT 501";

  JdbcSalesAdapter(
      @Value("${REPORT_JDBC_HOST:}") String host,
      @Value("${REPORT_JDBC_PORT:5432}") int port,
      @Value("${REPORT_JDBC_DATABASE:report_input}") String database,
      @Value("${REPORT_JDBC_USERNAME:report_reader}") String username,
      @Value("${REPORT_JDBC_PASSWORD:}") String password,
      @Value("${REPORT_JDBC_SSL_MODE:verify-full}") String sslMode,
      ObjectMapper json) {
    if (!host.isEmpty()
        && (!host.matches("[a-zA-Z0-9][a-zA-Z0-9.-]{0,252}")
            || port < 1
            || port > 65535
            || !database.matches("[a-zA-Z][a-zA-Z0-9_]{0,62}")
            || username.isBlank()
            || password.isBlank()
            || !Set.of("verify-full", "disable").contains(sslMode)))
      throw new IllegalArgumentException("Invalid deployment JDBC profile configuration");
    this.host = host;
    this.port = port;
    this.database = database;
    this.username = username;
    this.password = password;
    this.sslMode = sslMode;
    this.json = json;
  }

  boolean configured() {
    return !host.isEmpty();
  }

  Connection connect() throws SQLException {
    var properties = new Properties();
    properties.setProperty("user", username);
    properties.setProperty("password", password);
    properties.setProperty("sslmode", sslMode);
    properties.setProperty("connectTimeout", "3");
    properties.setProperty("loginTimeout", "5");
    properties.setProperty("socketTimeout", "5");
    properties.setProperty("cancelSignalTimeout", "1");
    properties.setProperty("maxResultBuffer", "262144");
    properties.setProperty("readOnlyMode", "transaction");
    var connection =
        DriverManager.getConnection(
            "jdbc:postgresql://" + host + ":" + port + "/" + database, properties);
    try {
      connection.setReadOnly(true);
      connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
      connection.setAutoCommit(false);
      return connection;
    } catch (SQLException e) {
      connection.close();
      throw e;
    }
  }

  JsonNode read(String category, boolean checkOnly) {
    if (!configured())
      throw new ResponseStatusException(
          HttpStatus.SERVICE_UNAVAILABLE, "JDBC profile is not configured");
    if (!permits.tryAcquire())
      throw new ResponseStatusException(
          HttpStatus.TOO_MANY_REQUESTS, "JDBC profile is busy; retry later");
    try (var connection = connect();
        var statement =
            connection.prepareStatement(
                checkOnly
                    ? "SELECT product,quantity,amount FROM public.reporting_sales WHERE category=?"
                          + " AND false ORDER BY id LIMIT 0"
                    : SELECT)) {
      statement.setQueryTimeout(3);
      statement.setMaxRows(501);
      statement.setString(1, checkOnly ? "" : category);
      var rows = json.createArrayNode();
      try (var result = statement.executeQuery()) {
        var metadata = result.getMetaData();
        if (!Set.of(Types.VARCHAR, Types.CHAR, Types.LONGVARCHAR)
                .contains(metadata.getColumnType(1))
            || metadata.getColumnType(2) != Types.INTEGER
            || !Set.of(Types.NUMERIC, Types.DECIMAL).contains(metadata.getColumnType(3)))
          throw new ResponseStatusException(
              HttpStatus.UNPROCESSABLE_ENTITY, "JDBC columns must be text, integer and numeric");
        while (result.next()) {
          var row = rows.addObject();
          row.put("product", result.getString(1));
          int quantity = result.getInt(2);
          if (result.wasNull()) row.putNull("quantity");
          else row.put("quantity", quantity);
          row.put("amount", result.getBigDecimal(3));
        }
      }
      connection.rollback();
      if (!checkOnly) StructuredData.rows(rows);
      return rows;
    } catch (SQLException e) {
      // Driver errors can contain endpoint, SQL and credential details. Never return or log them.
      throw new ResponseStatusException(
          HttpStatus.SERVICE_UNAVAILABLE, "JDBC source unavailable or query deadline exceeded");
    } finally {
      permits.release();
    }
  }
}
