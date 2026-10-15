package com.acme.reports;

import java.io.*;
import java.util.*;
import net.sf.jasperreports.engine.*;
import org.w3c.dom.*;

/**
 * Bounded row- and dataset-scoped report graphs. Uploaded bytes are XML, never serialized objects.
 */
public final class TemplateSubreports {
  static final int MAX_ELEMENTS = 4;
  static final int MAX_DEPTH = 2;
  static final int MAX_EXPANDED_ELEMENTS = 8;
  private static final LinkedHashMap<String, String> COMPILED =
      new LinkedHashMap<>(16, 0.75f, true);

  private TemplateSubreports() {}

  static String reference(Element element) {
    var parent = element.getParentNode();
    boolean detail =
        parent != null
            && "band".equals(parent.getNodeName())
            && parent.getParentNode() != null
            && "detail".equals(parent.getParentNode().getNodeName());
    if (!detail && !summary(element)) throw invalid();
    var expressions = element.getElementsByTagName("expression");
    if (expressions.getLength() != 1
        || expressions.item(0).getParentNode() != element
        || element.getElementsByTagName("*").getLength() != 1) throw invalid();
    String literal = expressions.item(0).getTextContent().trim();
    if (!literal.matches("\"assets/[a-z][a-z0-9-]{0,31}\\.jrxml\"")) throw invalid();
    return literal.substring(1, literal.length() - 1);
  }

  static void validate(byte[] source, Map<String, byte[]> assets) throws Exception {
    var referenced = new HashSet<String>();
    walk(
        "report.jrxml",
        source,
        assets,
        referenced,
        new HashSet<>(),
        new HashSet<>(),
        0,
        new int[2]);
    if (!referenced.equals(assets.keySet())) throw invalid();
  }

  private static void walk(
      String name,
      byte[] source,
      Map<String, byte[]> assets,
      Set<String> referenced,
      Set<String> active,
      Set<String> checked,
      int depth,
      int[] totals)
      throws Exception {
    if (depth > MAX_DEPTH || !active.add(name)) throw invalid();
    var root = TrustedTemplate.parse(source);
    var children = new ArrayList<String>();
    int images = collect(root, referenced, children);
    if (checked.add(name)) totals[0] += images;
    if (totals[0] > TemplateAssets.MAX_IMAGE_ELEMENTS) throw invalid();
    var elements = root.getElementsByTagName("element");
    for (int i = 0; i < elements.getLength(); i++) {
      var element = (Element) elements.item(i);
      if ("subreport".equals(element.getAttribute("kind"))
          && !summary(element)
          && root.getElementsByTagName("field").getLength() != 3) throw invalid();
    }
    var parameters = new HashMap<String, String>();
    for (var p : TrustedTemplate.parameters(source)) parameters.put(p.name(), p.type());
    for (String child : children) {
      if (++totals[1] > MAX_EXPANDED_ELEMENTS) throw invalid();
      byte[] bytes = assets.get(child);
      if (bytes == null) throw invalid();
      for (var p : TrustedTemplate.parameters(bytes))
        if (!Objects.equals(parameters.get(p.name()), p.type())) throw invalid();
      walk(child, bytes, assets, referenced, active, checked, depth + 1, totals);
    }
    TemplateStyles.merge(root, assets);
    referenced.addAll(TemplateFonts.references(root));
    active.remove(name);
  }

  private static boolean summary(Element element) {
    var parent = element.getParentNode();
    return parent instanceof Element
        && "summary".equals(parent.getNodeName())
        && parent.getParentNode() instanceof Element root
        && "jasperReport".equals(root.getTagName());
  }

  static boolean datasetScoped(byte[] source, Map<String, byte[]> assets) {
    var sources = new ArrayList<byte[]>();
    sources.add(source);
    assets.forEach(
        (name, bytes) -> {
          if (name.endsWith(".jrxml")) sources.add(bytes);
        });
    try {
      for (byte[] bytes : sources) {
        var elements = TrustedTemplate.parse(bytes).getElementsByTagName("element");
        for (int i = 0; i < elements.getLength(); i++) {
          var element = (Element) elements.item(i);
          if ("subreport".equals(element.getAttribute("kind")) && summary(element)) return true;
        }
      }
      return false;
    } catch (Exception e) {
      throw invalid();
    }
  }

  static boolean nested(Map<String, byte[]> assets) {
    try {
      for (var entry : assets.entrySet()) {
        if (!entry.getKey().endsWith(".jrxml")) continue;
        var elements = TrustedTemplate.parse(entry.getValue()).getElementsByTagName("element");
        for (int i = 0; i < elements.getLength(); i++)
          if ("subreport".equals(((Element) elements.item(i)).getAttribute("kind"))) return true;
      }
      return false;
    } catch (Exception e) {
      throw invalid();
    }
  }

  private static int collect(Element root, Set<String> referenced, List<String> children) {
    referenced.addAll(TemplateFonts.references(root));
    var imports = root.getElementsByTagName("template");
    var styles = new HashSet<String>();
    for (int i = 0; i < imports.getLength(); i++) {
      String path = TemplateStyles.reference((Element) imports.item(i));
      if (!styles.add(path)) throw invalid();
      referenced.add(path);
    }
    int images = 0, subreports = 0;
    var elements = root.getElementsByTagName("element");
    for (int i = 0; i < elements.getLength(); i++) {
      var element = (Element) elements.item(i);
      if ("image".equals(element.getAttribute("kind"))) {
        images++;
        referenced.add(TemplateAssets.reference(element));
      } else if ("subreport".equals(element.getAttribute("kind"))) {
        if (++subreports > MAX_ELEMENTS) throw invalid();
        String path = reference(element);
        children.add(path);
        referenced.add(path);
      }
    }
    return images;
  }

  static void merge(Element root, Map<String, byte[]> assets) throws Exception {
    merge(root, assets, 0, new HashSet<>());
  }

  private static void merge(Element root, Map<String, byte[]> assets, int depth, Set<String> active)
      throws Exception {
    if (depth > MAX_DEPTH) throw invalid();
    var elements = root.getElementsByTagName("element");
    var rowsParameter = root.getOwnerDocument().createElement("parameter");
    rowsParameter.setAttribute("name", "R_REPORT_ROWS");
    rowsParameter.setAttribute("class", "java.util.Collection");
    root.insertBefore(rowsParameter, root.getFirstChild());
    var compiled = new HashMap<String, String>();
    for (int i = 0; i < elements.getLength(); i++) {
      var element = (Element) elements.item(i);
      if (!"subreport".equals(element.getAttribute("kind"))) continue;
      String path = reference(element), parameter = compiled.get(path);
      if (parameter == null) {
        if (!active.add(path)) throw invalid();
        var child = TrustedTemplate.parse(assets.get(path));
        TemplateAssets.prepare(child, assets);
        merge(child, assets, depth + 1, active);
        String encoded = encoded(TemplateAssets.xml(child));
        var chunks = new ArrayList<String>();
        for (int offset = 0; offset < encoded.length(); offset += 16000)
          chunks.add(
              "\"" + encoded.substring(offset, Math.min(offset + 16000, encoded.length())) + "\"");
        parameter = "R_SUBREPORT_" + compiled.size();
        var declaration = root.getOwnerDocument().createElement("parameter");
        declaration.setAttribute("name", parameter);
        declaration.setAttribute("class", "net.sf.jasperreports.engine.JasperReport");
        append(
            declaration,
            "defaultValueExpression",
            "com.acme.reports.TemplateSubreports.load(new String[]{"
                + String.join(",", chunks)
                + "})");
        root.insertBefore(declaration, root.getFirstChild());
        compiled.put(path, parameter);
        active.remove(path);
      }
      String rows =
          summary(element)
              ? "$P{R_REPORT_ROWS}"
              : "java.util.List.of(java.util.Map.of(\"product\",$F{product},\"quantity\",$F{quantity},\"amount\",$F{amount}))";
      element.getElementsByTagName("expression").item(0).setTextContent("$P{" + parameter + "}");
      append(
          element,
          "parametersMapExpression",
          "com.acme.reports.TemplateSubreports.parameters($P{REPORT_PARAMETERS_MAP}," + rows + ")");
      append(
          element,
          "dataSourceExpression",
          "new net.sf.jasperreports.engine.data.JRMapCollectionDataSource(" + rows + ")");
    }
  }

  private static synchronized String encoded(byte[] source) throws Exception {
    String key = Renderer.sha256(source), encoded = COMPILED.get(key);
    if (encoded == null) {
      var report = JasperCompileManager.compileReport(new ByteArrayInputStream(source));
      var output = new ByteArrayOutputStream();
      try (var stream = new ObjectOutputStream(output)) {
        stream.writeObject(report);
      }
      encoded = Base64.getEncoder().encodeToString(output.toByteArray());
      if (encoded.length() > 262144) throw invalid();
      COMPILED.put(key, encoded);
      if (COMPILED.size() > 16) COMPILED.remove(COMPILED.keySet().iterator().next());
    }
    return encoded;
  }

  private static void append(Element parent, String tag, String text) {
    var child = parent.getOwnerDocument().createElement(tag);
    child.setTextContent(text);
    parent.appendChild(child);
  }

  /** Each report evaluates its own generated defaults, rather than inheriting its parent's. */
  public static Map<String, Object> parameters(Map<String, Object> parent) {
    var copy = new HashMap<>(parent);
    copy.keySet().removeIf(name -> name.startsWith("R_SUBREPORT_"));
    return copy;
  }

  /** A nested summary sees exactly the rows passed to its own report. */
  public static Map<String, Object> parameters(Map<String, Object> parent, Collection<?> rows) {
    var copy = parameters(parent);
    copy.put("R_REPORT_ROWS", rows);
    return copy;
  }

  /** Called only by generated defaults for reports compiled by this service. */
  public static JasperReport load(String[] chunks) {
    try {
      return (JasperReport)
          net.sf.jasperreports.engine.util.JRLoader.loadObject(
              new ByteArrayInputStream(Base64.getDecoder().decode(String.join("", chunks))));
    } catch (JRException e) {
      throw new IllegalStateException("SUBREPORT_LOAD_FAILED", e);
    }
  }

  private static IllegalArgumentException invalid() {
    return new IllegalArgumentException("Invalid subreport dependency");
  }
}
