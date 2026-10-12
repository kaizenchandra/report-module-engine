package com.acme.reports;

import java.util.Locale;

enum ReportFormat {
  PDF("application/pdf"),
  XLSX("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"),
  CSV("text/csv; charset=UTF-8"),
  DOCX("application/vnd.openxmlformats-officedocument.wordprocessingml.document"),
  HTML("application/zip");
  final String mediaType;

  ReportFormat(String mediaType) {
    this.mediaType = mediaType;
  }

  String extension() {
    return this == HTML ? "html.zip" : name().toLowerCase(Locale.ROOT);
  }
}
