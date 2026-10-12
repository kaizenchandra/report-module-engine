package com.acme.reports;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Validation;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;

class ConfigurationCodecTest {
  static final String YAML =
      """
      # Plain dates and yes/on are strings, with no implicit YAML 1.1 conversions.
      schemaVersion: '1'
      definitionId: sales-summary
      definitionVersion: 1
      parameters: {title: yes}
      outputs: [PDF]
      destinationRef: local-s3
      """;

  static byte[] utf8(String text) {
    return text.getBytes(StandardCharsets.UTF_8);
  }

  @Test
  void yamlJsonRoundTripPreservesExactTypedContractAndLegacyFingerprint() throws Exception {
    try (var factory = Validation.buildDefaultValidatorFactory()) {
      var codec = new ConfigurationCodec(new ObjectMapper(), factory.getValidator());
      var request = codec.read(utf8(YAML), true);
      assertThat(request.parameters().title()).isEqualTo("yes");
      var toJson = codec.convert(utf8(YAML), true, "json");
      var toYaml = codec.convert(utf8(toJson.configuration()), false, "yaml");
      assertThat(codec.read(utf8(toYaml.configuration()), true)).isEqualTo(request);
      for (String title : List.of("on", "2026-10-12", "0123", "<script>&text", "true")) {
        var r =
            new ReportRequest(
                "1",
                "sales-summary",
                1,
                new ReportRequest.Parameters(title),
                List.of("PDF"),
                "local-s3");
        String json = new ObjectMapper().writeValueAsString(r);
        assertThat(codec.read(utf8(codec.convert(utf8(json), false, "yaml").configuration()), true))
            .isEqualTo(r);
      }
      assertThat(new ObjectMapper().writeValueAsString(request))
          .isEqualTo(
              "{\"schemaVersion\":\"1\",\"definitionId\":\"sales-summary\",\"definitionVersion\":1,\"parameters\":{\"title\":\"yes\"},\"outputs\":[\"PDF\"],\"destinationRef\":\"local-s3\"}");
    }
  }

  @Test
  void rejectsYamlFeaturesDuplicatesDepthAndUnsafeTypesWithoutEchoingValues() {
    try (var factory = Validation.buildDefaultValidatorFactory()) {
      var codec = new ConfigurationCodec(new ObjectMapper(), factory.getValidator());
      for (String invalid :
          List.of(
              YAML + "---\n" + YAML,
              YAML + "destinationRef: second\n",
              YAML.replace("title: yes", "title: &anchor private-canary"),
              YAML.replace("title: yes", "title: *anchor"),
              YAML.replace("title: yes", "title: !!str private-canary"),
              YAML.replace("title: yes", "<<: {title: private-canary}"),
              YAML.replace("title: yes", "42: private-canary"),
              YAML.replace("'1'", "1"),
              YAML.replace("definitionVersion: 1", "definitionVersion: 1.5"),
              YAML.replace("title: yes", "title: true"),
              YAML.replace("title: yes", "title: !!java.lang.Runtime {}"),
              "x: " + "[".repeat(20) + "0" + "]".repeat(20),
              "",
              "null",
              "[]",
              YAML.replace("title: yes", "title: " + "x".repeat(4097)))) {
        assertThatThrownBy(() -> codec.read(utf8(invalid), true))
            .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
            .hasMessageNotContaining("private-canary");
      }
      assertThatThrownBy(() -> codec.read(new byte[] {(byte) 0xc3, 0x28}, true))
          .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
      assertThatThrownBy(() -> codec.read(utf8("#".repeat(65537)), true))
          .hasMessageContaining("413");
    }
  }

  @Test
  void jsonRejectsDuplicateFieldsTrailingDocumentsAndScalarCoercion() throws Exception {
    try (var factory = Validation.buildDefaultValidatorFactory()) {
      var codec = new ConfigurationCodec(new ObjectMapper(), factory.getValidator());
      String valid = codec.convert(utf8(YAML), true, "json").configuration();
      for (String invalid :
          List.of(
              valid + valid,
              valid.replace("\"schemaVersion\" : \"1\"", "\"schemaVersion\" : 1"),
              valid.replace("\"definitionVersion\" : 1", "\"definitionVersion\" : 1.5"),
              valid.replace("\"title\" : \"yes\"", "\"title\":\"yes\",\"title\":\"duplicate\""),
              valid.replace("\"title\" : \"yes\"", "\"title\":true")))
        assertThatThrownBy(() -> codec.read(utf8(invalid), false))
            .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
    }
  }

  @Test
  void versionSpecificFieldsCannotBeSmuggledThroughExplicitNulls() throws Exception {
    try (var factory = Validation.buildDefaultValidatorFactory()) {
      var codec = new ConfigurationCodec(new ObjectMapper(), factory.getValidator());
      for (String field : List.of("deliveries", "optionalOutputs")) {
        assertThatThrownBy(() -> codec.read(utf8(YAML + field + ": null\n"), true))
            .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        var node =
            new ObjectMapper().readTree(codec.convert(utf8(YAML), true, "json").configuration());
        ((com.fasterxml.jackson.databind.node.ObjectNode) node).putNull(field);
        assertThatThrownBy(() -> codec.read(utf8(node.toString()), false))
            .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
      }
      String v2 =
          "{\"schemaVersion\":\"2\",\"definitionId\":\"sales-summary\",\"definitionVersion\":1,\"parameters\":{\"title\":\"Test\"},\"outputs\":[\"PDF\"],\"deliveries\":[{\"destinationRef\":\"local-s3\",\"outputs\":[\"PDF\"],\"required\":true}],\"destinationRef\":null}";
      assertThatThrownBy(() -> codec.read(utf8(v2), false))
          .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
    }
  }
}
