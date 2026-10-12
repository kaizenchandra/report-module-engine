package com.acme.reports;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.databind.*;
import jakarta.validation.*;
import java.io.*;
import java.math.*;
import java.nio.*;
import java.nio.charset.*;
import java.util.*;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.yaml.snakeyaml.*;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.events.*;
import org.yaml.snakeyaml.nodes.*;
import org.yaml.snakeyaml.representer.Representer;
import org.yaml.snakeyaml.resolver.Resolver;

@Component
class ConfigurationCodec {
  private final ObjectMapper json;
  private final Validator validator;

  ConfigurationCodec(ObjectMapper mapper, Validator validator) {
    this.validator = validator;
    this.json =
        mapper
            .copy()
            .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
            .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_IGNORED_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    for (var shape :
        List.of(
            com.fasterxml.jackson.databind.cfg.CoercionInputShape.Integer,
            com.fasterxml.jackson.databind.cfg.CoercionInputShape.Float,
            com.fasterxml.jackson.databind.cfg.CoercionInputShape.Boolean)) {
      this.json
          .coercionConfigFor(com.fasterxml.jackson.databind.type.LogicalType.Textual)
          .setCoercion(shape, com.fasterxml.jackson.databind.cfg.CoercionAction.Fail);
    }
    this.json
        .getFactory()
        .setStreamReadConstraints(
            StreamReadConstraints.builder()
                .maxNestingDepth(16)
                .maxStringLength(4096)
                .maxNumberLength(128)
                .build());
  }

  ReportRequest read(byte[] bytes, boolean yaml) {
    if (bytes.length > 65536)
      throw new ResponseStatusException(
          HttpStatus.PAYLOAD_TOO_LARGE, "Request body exceeds 64 KiB");
    ReportRequest request;
    try {
      String source =
          StandardCharsets.UTF_8
              .newDecoder()
              .onMalformedInput(CodingErrorAction.REPORT)
              .onUnmappableCharacter(CodingErrorAction.REPORT)
              .decode(ByteBuffer.wrap(bytes))
              .toString();
      JsonNode wire;
      if (yaml) {
        var parser = yaml();
        int documents = 0, depth = 0, count = 0;
        for (var event : parser.parse(new StringReader(source))) {
          if (++count > 2048
              || event instanceof AliasEvent
              || (event instanceof NodeEvent n && n.getAnchor() != null))
            throw new IllegalArgumentException();
          if (event instanceof DocumentStartEvent && ++documents > 1)
            throw new IllegalArgumentException();
          if (event instanceof CollectionStartEvent collection) {
            if (++depth > 16 || collection.getTag() != null) throw new IllegalArgumentException();
          }
          if (event instanceof CollectionEndEvent) depth--;
          if (event instanceof ScalarEvent scalar
              && (scalar.getTag() != null || scalar.getValue().length() > 4096))
            throw new IllegalArgumentException();
        }
        Object tree = tree(parser.compose(new StringReader(source)), 0);
        if (!(tree instanceof Map)) throw new IllegalArgumentException();
        wire = json.valueToTree(tree);
      } else {
        wire = json.readTree(source);
      }
      if (wire == null || !wire.isObject()) throw new IllegalArgumentException();
      // The shared DTO cannot distinguish omitted properties from explicit nulls. Preserve the
      // versioned wire contract before binding so v1/v2 fields cannot leak across variants.
      String version = wire.path("schemaVersion").asText();
      if (("1".equals(version) && (wire.has("deliveries") || wire.has("optionalOutputs")))
          || ("2".equals(version) && wire.has("destinationRef"))
          || (wire.has("optionalOutputs") && !wire.path("optionalOutputs").isArray()))
        throw new IllegalArgumentException();
      request = json.treeToValue(wire, ReportRequest.class);
    } catch (IOException | IllegalArgumentException | org.yaml.snakeyaml.error.YAMLException e) {
      // Parser diagnostics can echo parameters or source lines. Never send/log them.
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST,
          "Malformed configuration: use one UTF-8 JSON/YAML object with known fields, exact types,"
              + " unique keys and no YAML anchors, aliases, tags or merge keys");
    }
    var errors = validator.validate(request);
    if (!errors.isEmpty()) throw new ConstraintViolationException(errors);
    return request;
  }

  record Converted(String configuration, String mediaType, ReportRequest request) {}

  Converted convert(byte[] bytes, boolean yaml, String target) throws IOException {
    if (!Set.of("json", "yaml").contains(target))
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Target must be json or yaml");
    var request = read(bytes, yaml);
    String output =
        target.equals("yaml")
            ? yaml().dump(json.convertValue(request, LinkedHashMap.class))
            : json.writerWithDefaultPrettyPrinter().writeValueAsString(request);
    return new Converted(
        output, target.equals("yaml") ? "application/yaml" : "application/json", request);
  }

  private static Yaml yaml() {
    var options = new LoaderOptions();
    options.setAllowDuplicateKeys(false);
    options.setAllowRecursiveKeys(false);
    options.setMaxAliasesForCollections(0);
    options.setNestingDepthLimit(16);
    options.setCodePointLimit(65536);
    var dump = new DumperOptions();
    dump.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
    dump.setSplitLines(false);
    return new Yaml(
        new SafeConstructor(options),
        new Representer(dump),
        dump,
        options,
        new Resolver() {
          @Override
          protected void addImplicitResolvers() {
            // JSON-compatible scalar resolution: dates, yes/no, on/off and leading-zero numbers are
            // text.
            addImplicitResolver(Tag.BOOL, Pattern.compile("^(true|false)$"), "tf");
            addImplicitResolver(Tag.NULL, Pattern.compile("^(null|~|)$"), "n~\0");
            addImplicitResolver(Tag.INT, Pattern.compile("^-?(0|[1-9][0-9]*)$"), "-0123456789");
            addImplicitResolver(
                Tag.FLOAT,
                Pattern.compile("^-?(0|[1-9][0-9]*)(\\.[0-9]+([eE][+-]?[0-9]+)?|[eE][+-]?[0-9]+)$"),
                "-0123456789");
          }
        });
  }

  private static Object tree(Node node, int depth) {
    if (node == null || depth > 16) throw new IllegalArgumentException();
    if (node instanceof MappingNode map && node.getTag().equals(Tag.MAP)) {
      var result = new LinkedHashMap<String, Object>();
      for (var pair : map.getValue()) {
        if (!(pair.getKeyNode() instanceof ScalarNode key)
            || !key.getTag().equals(Tag.STR)
            || key.getValue().equals("<<")
            || result.containsKey(key.getValue())) throw new IllegalArgumentException();
        result.put(key.getValue(), tree(pair.getValueNode(), depth + 1));
      }
      return result;
    }
    if (node instanceof SequenceNode sequence && node.getTag().equals(Tag.SEQ)) {
      var result = new ArrayList<Object>();
      for (var item : sequence.getValue()) result.add(tree(item, depth + 1));
      return result;
    }
    if (node instanceof ScalarNode scalar) {
      String value = scalar.getValue();
      if (node.getTag().equals(Tag.STR)) return value;
      if (node.getTag().equals(Tag.NULL)) return null;
      if (node.getTag().equals(Tag.BOOL)) return Boolean.valueOf(value);
      if (value.length() > 128) throw new IllegalArgumentException();
      if (node.getTag().equals(Tag.INT)) return new BigInteger(value);
      if (node.getTag().equals(Tag.FLOAT)) return new BigDecimal(value);
    }
    throw new IllegalArgumentException();
  }
}
