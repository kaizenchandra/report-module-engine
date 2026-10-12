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
      var root = builder.parse(new ByteArrayInputStream(bytes)).getDocumentElement();
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
      var parameters = root.getElementsByTagName("parameter");
      if (parameters.getLength() != 1) throw invalid();
      var title = (Element) parameters.item(0);
      if (!"TITLE".equals(title.getAttribute("name"))
          || !"java.lang.String".equals(title.getAttribute("class"))) throw invalid();
      return bytes;
    } catch (Exception e) {
      throw invalid();
    }
  }

  private static void check(Element e) {
    if (e.getNamespaceURI() != null || e.getTagName().contains(":")) throw invalid();
    if (Set.of(
            "query",
            "queryString",
            "subDataset",
            "datasetRun",
            "scriptlet",
            "template",
            "property",
            "propertyExpression",
            "subreportExpression",
            "imageExpression")
        .contains(e.getTagName())) throw invalid();
    if (e.hasAttribute("resourceBundle")
        || e.hasAttribute("scriptletClass")
        || e.hasAttribute("pdfFontName")) throw invalid();
    if (e.hasAttribute("fontName")
        && !Set.of("SansSerif", "Serif", "Monospaced").contains(e.getAttribute("fontName")))
      throw invalid();
    if ("element".equals(e.getTagName())
        && !Set.of("textField", "staticText", "line", "rectangle", "ellipse", "frame", "break")
            .contains(e.getAttribute("kind"))) throw invalid();
  }

  private static IllegalArgumentException invalid() {
    return new IllegalArgumentException("Invalid or unsupported standalone template");
  }
}
