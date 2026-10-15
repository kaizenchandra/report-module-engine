package com.acme.reports;

import java.nio.charset.StandardCharsets;
import java.util.*;
import org.w3c.dom.*;

/** Constant named styles flattened into JRXML; no external template resolver is installed. */
final class TemplateStyles {
  static final int MAX_STYLES = 8;
  static final String NAME = "assets/[a-z][a-z0-9-]{0,31}\\.jrtx";
  private static final String STYLE_NAME = "[A-Za-z][A-Za-z0-9_-]{0,31}";
  private static final Set<String> ATTRIBUTES =
      Set.of(
          "name",
          "style",
          "fontName",
          "fontSize",
          "bold",
          "italic",
          "underline",
          "strikeThrough",
          "forecolor",
          "backcolor",
          "mode");

  static String reference(Element element) {
    if (element.getAttributes().getLength() != 0
        || !"jasperReport".equals(element.getParentNode().getNodeName())
        || element.getElementsByTagName("*").getLength() != 0) throw invalid();
    String literal = element.getTextContent().trim();
    if (!literal.matches("\"" + NAME + "\"")) throw invalid();
    return literal.substring(1, literal.length() - 1);
  }

  static List<Element> read(byte[] bytes) {
    if (bytes == null || bytes.length < 8 || bytes.length > TemplateAssets.MAX_FILE_BYTES)
      throw invalid();
    String text = new String(bytes, StandardCharsets.UTF_8);
    if (!Arrays.equals(bytes, text.getBytes(StandardCharsets.UTF_8))) throw invalid();
    try {
      var root = TrustedTemplate.parse(bytes);
      if (!"jasperTemplate".equals(root.getTagName())
          || root.getNamespaceURI() != null
          || root.getAttributes().getLength() != 0) throw invalid();
      var styles = new ArrayList<Element>();
      for (var node = root.getFirstChild(); node != null; node = node.getNextSibling()) {
        if (node instanceof Element style) {
          validateStyle(style);
          styles.add(style);
        } else if (!(node instanceof Comment) && !node.getTextContent().isBlank()) throw invalid();
      }
      if (styles.isEmpty() || styles.size() > MAX_STYLES) throw invalid();
      return styles;
    } catch (Exception e) {
      throw invalid();
    }
  }

  private static void validateStyle(Element style) {
    if (!"style".equals(style.getTagName())
        || style.getNamespaceURI() != null
        || style.getElementsByTagName("*").getLength() != 0) throw invalid();
    for (var child = style.getFirstChild(); child != null; child = child.getNextSibling())
      if (!child.getTextContent().isBlank()) throw invalid();
    for (int i = 0; i < style.getAttributes().getLength(); i++) {
      var attribute = style.getAttributes().item(i);
      String name = attribute.getNodeName(), value = attribute.getNodeValue();
      if (!ATTRIBUTES.contains(name)) throw invalid();
      if (Set.of("name", "style").contains(name) && !value.matches(STYLE_NAME)) throw invalid();
      if ("fontName".equals(name) && !TemplateFonts.allowed(value)) throw invalid();
      if ("fontSize".equals(name)) {
        double size = Double.parseDouble(value);
        if (!Double.isFinite(size) || size < 6 || size > 48) throw invalid();
      }
      if (Set.of("bold", "italic", "underline", "strikeThrough").contains(name)
          && !Set.of("true", "false").contains(value)) throw invalid();
      if (Set.of("forecolor", "backcolor").contains(name) && !value.matches("#[0-9a-fA-F]{6}"))
        throw invalid();
      if ("mode".equals(name) && !Set.of("Opaque", "Transparent").contains(value)) throw invalid();
    }
    if (!style.hasAttribute("name")) throw invalid();
  }

  static void merge(Element report, Map<String, byte[]> assets) {
    var imports = report.getElementsByTagName("template");
    if (imports.getLength() == 0) return;
    var definitions = new TreeMap<String, Element>();
    var existing = report.getElementsByTagName("style");
    for (int i = 0; i < existing.getLength(); i++) {
      var style = (Element) existing.item(i);
      validateStyle(style);
      if (style.getParentNode() != report
          || definitions.put(style.getAttribute("name"), style) != null) throw invalid();
    }
    var seenFiles = new HashSet<String>();
    while (imports.getLength() > 0) {
      var declaration = (Element) imports.item(0);
      String path = reference(declaration);
      if (!seenFiles.add(path)) throw invalid();
      for (var style : read(assets.get(path))) {
        if (definitions.put(style.getAttribute("name"), style) != null) throw invalid();
      }
      report.removeChild(declaration);
    }
    if (definitions.size() > MAX_STYLES) throw invalid();
    for (String name : definitions.keySet()) {
      var seen = new HashSet<String>();
      String current = name;
      while (!current.isEmpty()) {
        if (!seen.add(current) || !definitions.containsKey(current)) throw invalid();
        current = definitions.get(current).getAttribute("style");
      }
    }
    // Flatten inheritance: Jasper fill must not depend on declaration ordering.
    for (var style : definitions.values()) {
      var effective = new LinkedHashMap<String, String>();
      var chain = new ArrayList<Element>();
      var current = style;
      while (current != null) {
        chain.add(current);
        String parent = current.getAttribute("style");
        current = parent.isEmpty() ? null : definitions.get(parent);
      }
      Collections.reverse(chain);
      for (var member : chain)
        for (int i = 0; i < member.getAttributes().getLength(); i++) {
          var attribute = member.getAttributes().item(i);
          if (!Set.of("name", "style").contains(attribute.getNodeName()))
            effective.put(attribute.getNodeName(), attribute.getNodeValue());
        }
      var flattened = report.getOwnerDocument().createElement("style");
      flattened.setAttribute("name", style.getAttribute("name"));
      effective.forEach(flattened::setAttribute);
      if (style.getParentNode() == report) report.replaceChild(flattened, style);
      else report.insertBefore(flattened, report.getFirstChild());
    }
    var elements = report.getElementsByTagName("element");
    for (int i = 0; i < elements.getLength(); i++) {
      var element = (Element) elements.item(i);
      if (element.hasAttribute("style") && !definitions.containsKey(element.getAttribute("style")))
        throw invalid();
    }
  }

  private static IllegalArgumentException invalid() {
    return new IllegalArgumentException("Invalid or incomplete named style dependencies");
  }
}
