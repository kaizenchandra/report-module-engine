package com.acme.reports;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.*;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ReportRequest(
    @NotNull @Pattern(regexp = "1|2") String schemaVersion,
    @NotNull @Pattern(regexp = "[a-z][a-z0-9-]{2,63}") String definitionId,
    @NotNull @Min(1) @Max(1000001) Integer definitionVersion,
    @NotNull @Valid Parameters parameters,
    @NotNull @Size(min = 1, max = 5)
        List<@NotNull @Pattern(regexp = "PDF|XLSX|CSV|DOCX|HTML") String> outputs,
    @Pattern(regexp = "[a-z][a-z0-9-]{2,63}") String destinationRef,
    @Size(max = 4)
        List<@NotNull @Pattern(regexp = "PDF|XLSX|CSV|DOCX|HTML") String> optionalOutputs,
    @Size(min = 1, max = 6) List<@NotNull @Valid Delivery> deliveries,
    @Valid OutputOptions outputOptions) {
  // Keep the v1 JSON serialization and request fingerprint unchanged for lost-ACK recovery.
  public ReportRequest(
      String schemaVersion,
      String definitionId,
      Integer definitionVersion,
      Parameters parameters,
      List<String> outputs,
      String destinationRef) {
    this(
        schemaVersion,
        definitionId,
        definitionVersion,
        parameters,
        outputs,
        destinationRef,
        null,
        null,
        null);
  }

  public ReportRequest(
      String schemaVersion,
      String definitionId,
      Integer definitionVersion,
      Parameters parameters,
      List<String> outputs,
      String destinationRef,
      List<String> optionalOutputs,
      List<Delivery> deliveries) {
    this(
        schemaVersion,
        definitionId,
        definitionVersion,
        parameters,
        outputs,
        destinationRef,
        optionalOutputs,
        deliveries,
        null);
  }

  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record OutputOptions(
      @Pattern(regexp = "COMMA|SEMICOLON|TAB") String csvDelimiter,
      Boolean xlsxOnePagePerSheet,
      @Pattern(regexp = SHEET_NAME) String xlsxSheetName,
      Boolean csvIncludeBom) {
    public OutputOptions(String csvDelimiter, Boolean xlsxOnePagePerSheet, String xlsxSheetName) {
      this(csvDelimiter, xlsxOnePagePerSheet, xlsxSheetName, null);
    }

    static final String SHEET_NAME =
        "(?![Hh][Ii][Ss][Tt][Oo][Rr][Yy]$)[A-Za-z0-9](?:[A-Za-z0-9 ._-]{0,29}[A-Za-z0-9._-])?";

    public OutputOptions(String csvDelimiter, Boolean xlsxOnePagePerSheet) {
      this(csvDelimiter, xlsxOnePagePerSheet, null, null);
    }
  }

  @JsonIgnore
  @AssertTrue(
      message = "Output options must be nonempty and apply only to selected CSV/XLSX outputs")
  public boolean isOutputOptionsValid() {
    if (outputOptions == null) return true;
    return outputs != null
        && (outputOptions.csvDelimiter() != null
            || outputOptions.xlsxOnePagePerSheet() != null
            || outputOptions.xlsxSheetName() != null
            || outputOptions.csvIncludeBom() != null)
        && (outputOptions.csvDelimiter() == null || outputs.contains("CSV"))
        && (outputOptions.csvIncludeBom() == null || outputs.contains("CSV"))
        && (outputOptions.xlsxOnePagePerSheet() == null || outputs.contains("XLSX"))
        && (outputOptions.xlsxSheetName() == null || outputs.contains("XLSX"));
  }

  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record Parameters(
      @NotBlank @Size(max = 100) @Pattern(regexp = "[^\\p{Cntrl}]+") String title,
      @Pattern(regexp = "en-US|de-DE|fr-FR") String locale,
      @Pattern(regexp = "UTC|Asia/Kolkata|America/New_York|Europe/Berlin") String timeZone,
      @Size(min = 1, max = 8)
          Map<@Pattern(regexp = "P_[A-Z][A-Z0-9_]{0,29}") String, @NotNull @Valid ParameterValue>
              values) {
    public Parameters {
      if (values != null) values = Collections.unmodifiableMap(new TreeMap<>(values));
    }

    public Parameters(String title, String locale, String timeZone) {
      this(title, locale, timeZone, null);
    }

    public Parameters(String title) {
      this(title, null, null);
    }

    public String resolvedLocale() {
      return locale == null ? "en-US" : locale;
    }

    public String resolvedTimeZone() {
      return timeZone == null ? "UTC" : timeZone;
    }
  }

  public record ParameterValue(
      @NotNull @Pattern(regexp = "TEXT|INTEGER|DECIMAL|BOOLEAN|DATE") String type,
      @NotNull com.fasterxml.jackson.databind.JsonNode value) {
    public ParameterValue {
      if ("INTEGER".equals(type)
          && value != null
          && value.isIntegralNumber()
          && value.canConvertToInt())
        value = com.fasterxml.jackson.databind.node.IntNode.valueOf(value.intValue());
    }

    @JsonIgnore
    @AssertTrue(message = "Parameter value must match its type and permitted range")
    public boolean isValueValid() {
      if (type == null || value == null || value.isNull()) return false;
      return switch (type) {
        case "TEXT" ->
            value.isTextual()
                && value.textValue().length() <= 200
                && value.textValue().matches("[^\\p{Cc}]*");
        case "INTEGER" ->
            value.isIntegralNumber()
                && value.canConvertToInt()
                && Math.abs((long) value.intValue()) <= 1000000;
        case "DECIMAL" ->
            value.isTextual()
                && value.textValue().matches("-?(0|[1-9][0-9]{0,11})(\\.[0-9]{1,4})?");
        case "BOOLEAN" -> value.isBoolean();
        case "DATE" -> validDate(value);
        default -> false;
      };
    }

    private static boolean validDate(com.fasterxml.jackson.databind.JsonNode value) {
      if (!value.isTextual() || !value.textValue().matches("[0-9]{4}-[0-9]{2}-[0-9]{2}"))
        return false;
      try {
        int year = java.time.LocalDate.parse(value.textValue()).getYear();
        return year >= 1900 && year <= 2100;
      } catch (java.time.DateTimeException e) {
        return false;
      }
    }

    Object jasperValue(String zone, boolean csv) {
      if (!isValueValid()) throw new IllegalArgumentException("Invalid typed parameter");
      return switch (type) {
        case "TEXT" -> csv ? "'" + value.textValue() : value.textValue();
        case "INTEGER" -> value.intValue();
        case "DECIMAL" -> new java.math.BigDecimal(value.textValue());
        case "BOOLEAN" -> value.booleanValue();
        case "DATE" ->
            Date.from(
                java.time.LocalDate.parse(value.textValue())
                    .atStartOfDay(java.time.ZoneId.of(zone))
                    .toInstant());
        default -> throw new IllegalArgumentException("Unsupported parameter type");
      };
    }
  }

  public record Delivery(
      @NotNull @Pattern(regexp = "[a-z][a-z0-9-]{2,63}") String destinationRef,
      @NotNull @Size(min = 1, max = 5)
          List<@NotNull @Pattern(regexp = "PDF|XLSX|CSV|DOCX|HTML") String> outputs,
      @NotNull Boolean required) {}

  @JsonIgnore
  @AssertTrue(
      message =
          "Use v1 with one output and destinationRef, or v2 with unique outputs, optionalOutputs"
              + " and explicit deliveries; every output needs a mapping and required outputs need a"
              + " required delivery")
  public boolean isMappingValid() {
    if (outputs == null
        || outputs.stream().anyMatch(Objects::isNull)
        || new HashSet<>(outputs).size() != outputs.size()) return false;
    if ("1".equals(schemaVersion))
      return outputs.size() == 1
          && destinationRef != null
          && optionalOutputs == null
          && deliveries == null;
    if (!"2".equals(schemaVersion)
        || destinationRef != null
        || deliveries == null
        || deliveries.isEmpty()) return false;
    var optional = optionalOutputs == null ? List.<String>of() : optionalOutputs;
    if (optional.stream().anyMatch(Objects::isNull)
        || !outputs.containsAll(optional)
        || new HashSet<>(optional).size() != optional.size()
        || optional.size() >= outputs.size()) return false;
    var pairs = new HashSet<String>();
    var mapped = new HashSet<String>();
    var requiredMapped = new HashSet<String>();
    for (var d : deliveries) {
      if (d == null
          || d.destinationRef() == null
          || d.outputs() == null
          || d.required() == null
          || !outputs.containsAll(d.outputs())) return false;
      for (var f : d.outputs()) {
        if (f == null
            || !pairs.add(d.destinationRef() + ":" + f)
            || (d.required() && optional.contains(f))) return false;
        mapped.add(f);
        if (d.required()) requiredMapped.add(f);
      }
    }
    return mapped.containsAll(outputs)
        && outputs.stream().filter(f -> !optional.contains(f)).allMatch(requiredMapped::contains);
  }

  @JsonIgnore
  public List<Delivery> resolvedDeliveries() {
    return "1".equals(schemaVersion)
        ? List.of(new Delivery(destinationRef, outputs, true))
        : deliveries;
  }

  public boolean outputRequired(String format) {
    return optionalOutputs == null || !optionalOutputs.contains(format);
  }
}
