package com.acme.reports;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.*;
import org.xml.sax.*;
import org.xml.sax.helpers.DefaultHandler;

// Compatibility/resource validation for trusted code. This is deliberately NOT an execution
// sandbox.
final class TrustedTemplate {
  static final int MAX_BYTES = 32768;

  static byte[] validate(String source) {
    byte[] bytes = source.getBytes(StandardCharsets.UTF_8);
    if (!source.equals(new String(bytes, StandardCharsets.UTF_8))
        || bytes.length == 0
        || bytes.length > MAX_BYTES) throw invalid();
    try {
      var root = parse(bytes);
      if (!"jasperReport".equals(root.getTagName())
          || !"java".equals(root.getAttribute("language"))) throw invalid();
      var nodes = root.getElementsByTagName("*");
      if (nodes.getLength() > 1000) throw invalid();
      check(root);
      for (int i = 0; i < nodes.getLength(); i++) check((Element) nodes.item(i));
      var fields =
          Map.of(
              "product",
              "java.lang.String",
              "quantity",
              "java.lang.Integer",
              "amount",
              "java.math.BigDecimal");
      var seen = new HashSet<String>();
      var declared = root.getElementsByTagName("field");
      for (int i = 0; i < declared.getLength(); i++) {
        var field = (Element) declared.item(i);
        if (!seen.add(field.getAttribute("name"))
            || !Objects.equals(fields.get(field.getAttribute("name")), field.getAttribute("class")))
          throw invalid();
      }
      declarations(root);
      return bytes;
    } catch (Exception e) {
      throw invalid();
    }
  }

  record Declaration(String name, String type) {}

  static List<Declaration> parameters(byte[] source) {
    validate(new String(source, StandardCharsets.UTF_8));
    try {
      return declarations(parse(source));
    } catch (Exception e) {
      throw invalid();
    }
  }

  static void validateValues(byte[] source, Map<String, ReportRequest.ParameterValue> supplied) {
    var declared = parameters(source);
    var values = supplied == null ? Map.<String, ReportRequest.ParameterValue>of() : supplied;
    if (!values
        .keySet()
        .equals(
            declared.stream().map(Declaration::name).collect(java.util.stream.Collectors.toSet())))
      throw invalidParameters();
    for (var parameter : declared) {
      var value = values.get(parameter.name());
      if (value == null || !parameter.type().equals(value.type()) || !value.isValueValid())
        throw invalidParameters();
    }
  }

  private static org.springframework.web.server.ResponseStatusException invalidParameters() {
    return new org.springframework.web.server.ResponseStatusException(
        org.springframework.http.HttpStatus.BAD_REQUEST,
        "Typed parameters must exactly match the selected template's declared names and types");
  }

  private static List<Declaration> declarations(Element root) {
    var classes =
        Map.of(
            "java.lang.String",
            "TEXT",
            "java.lang.Integer",
            "INTEGER",
            "java.math.BigDecimal",
            "DECIMAL",
            "java.lang.Boolean",
            "BOOLEAN",
            "java.util.Date",
            "DATE");
    var result = new ArrayList<Declaration>();
    var names = new HashSet<String>();
    boolean title = false;
    var parameters = root.getElementsByTagName("parameter");
    if (parameters.getLength() > 9) throw invalid();
    for (int i = 0; i < parameters.getLength(); i++) {
      var parameter = (Element) parameters.item(i);
      String name = parameter.getAttribute("name"),
          type = classes.get(parameter.getAttribute("class"));
      if (parameter.getParentNode() != root || !names.add(name) || type == null) throw invalid();
      if ("TITLE".equals(name)) {
        if (!"TEXT".equals(type)) throw invalid();
        title = true;
      } else {
        if (!name.matches("P_[A-Z][A-Z0-9_]{0,29}")) throw invalid();
        result.add(new Declaration(name, type));
      }
    }
    if (!title) throw invalid();
    result.sort(Comparator.comparing(Declaration::name));
    return List.copyOf(result);
  }

  static Element parse(byte[] bytes) throws Exception {
    var factory = DocumentBuilderFactory.newDefaultInstance();
    factory.setNamespaceAware(true);
    factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
    factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
    factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
    factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
    factory.setXIncludeAware(false);
    factory.setExpandEntityReferences(false);
    factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
    factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
    factory.setAttribute("jdk.xml.maxElementDepth", "32");
    factory.setAttribute("jdk.xml.elementAttributeLimit", "64");
    var builder = factory.newDocumentBuilder();
    builder.setEntityResolver(
        (publicId, systemId) -> {
          throw new SAXException("External entity rejected");
        });
    builder.setErrorHandler(
        new DefaultHandler() {
          @Override
          public void error(SAXParseException e) throws SAXException {
            throw e;
          }

          @Override
          public void fatalError(SAXParseException e) throws SAXException {
            throw e;
          }
        });
    return builder.parse(new ByteArrayInputStream(bytes)).getDocumentElement();
  }

  private static void check(Element e) {
    if (e.getNamespaceURI() != null || e.getTagName().contains(":")) throw invalid();
    if (Set.of(
            "query",
            "queryString",
            "subDataset",
            "datasetRun",
            "scriptlet",
            "property",
            "propertyExpression",
            "subreportExpression",
            "imageExpression")
        .contains(e.getTagName())) throw invalid();
    if (e.hasAttribute("resourceBundle")
        || e.hasAttribute("scriptletClass")
        || e.hasAttribute("pdfFontName")) throw invalid();
    if (e.hasAttribute("fontName") && !TemplateFonts.allowed(e.getAttribute("fontName")))
      throw invalid();
    if ("template".equals(e.getTagName())) TemplateStyles.reference(e);
    if ("element".equals(e.getTagName()) && "subreport".equals(e.getAttribute("kind")))
      TemplateSubreports.reference(e);
    if ("element".equals(e.getTagName()) && "image".equals(e.getAttribute("kind")))
      TemplateAssets.reference(e);
    if ("element".equals(e.getTagName())
        && !Set.of(
                "textField",
                "staticText",
                "line",
                "rectangle",
                "ellipse",
                "frame",
                "break",
                "image",
                "subreport")
            .contains(e.getAttribute("kind"))) throw invalid();
  }

  private static IllegalArgumentException invalid() {
    return new IllegalArgumentException("Invalid or unsupported standalone template");
  }
}
