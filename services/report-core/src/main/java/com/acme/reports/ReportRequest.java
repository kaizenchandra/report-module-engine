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
    @Size(min = 1, max = 6) List<@NotNull @Valid Delivery> deliveries) {
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
        null);
  }

  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record Parameters(
      @NotBlank @Size(max = 100) @Pattern(regexp = "[^\\p{Cntrl}]+") String title,
      @Pattern(regexp = "en-US|de-DE|fr-FR") String locale,
      @Pattern(regexp = "UTC|Asia/Kolkata|America/New_York|Europe/Berlin") String timeZone) {
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
