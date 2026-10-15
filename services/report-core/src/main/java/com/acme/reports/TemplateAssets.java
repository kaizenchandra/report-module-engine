package com.acme.reports;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.*;
import javax.imageio.ImageIO;
import javax.xml.XMLConstants;
import javax.xml.transform.*;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import org.w3c.dom.Element;

/** Bounded dependency closure for trusted PNG/JRTX/JRXML assets. No runtime resource lookup. */
final class TemplateAssets {
  static final int MAX_COUNT = 4;
  static final int MAX_IMAGE_ELEMENTS = 8;
  static final int MAX_FILE_BYTES = 8192;
  static final int MAX_TOTAL_BYTES = 16384;
  static final int MAX_DIMENSION = 512;
  static final String NAME = "assets/[a-z][a-z0-9-]{0,31}\\.(png|jrtx|jrxml|ttf)";

  record Content(byte[] source, Map<String, byte[]> assets) {
    String fingerprint() {
      if (assets.isEmpty()) return Renderer.sha256(source);
      var manifest =
          new StringBuilder(
                  TemplateSubreports.datasetScoped(source, assets)
                      ? "template-dataset-subreport-assets-v6\n"
                      : assets.keySet().stream().anyMatch(n -> n.endsWith(".ttf"))
                          ? "template-font-assets-v5\n"
                          : TemplateSubreports.nested(assets)
                              ? "template-nested-subreport-assets-v4\n"
                              : assets.keySet().stream().anyMatch(n -> n.endsWith(".jrxml"))
                                  ? "template-subreport-assets-v3\n"
                                  : assets.keySet().stream().anyMatch(n -> n.endsWith(".jrtx"))
                                      ? "template-style-assets-v2\n"
                                      : "template-png-assets-v1\n")
              .append(Renderer.sha256(source))
              .append('\n');
      assets.entrySet().stream()
          .sorted(Map.Entry.comparingByKey())
          .forEach(
              e ->
                  manifest
                      .append(e.getKey())
                      .append(':')
                      .append(Renderer.sha256(e.getValue()))
                      .append('\n'));
      return Renderer.sha256(manifest.toString().getBytes(StandardCharsets.UTF_8));
    }

    byte[] renderSource() {
      if (assets.isEmpty()) return source.clone();
      try {
        var root = TrustedTemplate.parse(source);
        prepare(root, assets);
        TemplateSubreports.merge(root, assets);
        TemplateFonts.embed(root, assets);
        return xml(root);
      } catch (Exception e) {
        throw invalid();
      }
    }

    byte[] archive() {
      try {
        var output = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(output, StandardCharsets.UTF_8)) {
          put(zip, "report.jrxml", source);
          for (var asset : new TreeMap<>(assets).entrySet())
            put(zip, asset.getKey(), asset.getValue());
        }
        return output.toByteArray();
      } catch (IOException e) {
        throw invalid();
      }
    }
  }

  static byte[] xml(Element root) throws Exception {
    var factory = TransformerFactory.newDefaultInstance();
    factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
    factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
    factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_STYLESHEET, "");
    var transformer = factory.newTransformer();
    transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
    var output = new ByteArrayOutputStream();
    transformer.transform(new DOMSource(root.getOwnerDocument()), new StreamResult(output));
    return output.toByteArray();
  }

  static void prepare(Element root, Map<String, byte[]> assets) {
    TemplateStyles.merge(root, assets);
    TemplateFonts.expand(root, assets);
    var nodes = root.getElementsByTagName("element");
    for (int i = 0; i < nodes.getLength(); i++) {
      var element = (Element) nodes.item(i);
      if (!"image".equals(element.getAttribute("kind"))) continue;
      element
          .getElementsByTagName("expression")
          .item(0)
          .setTextContent(
              "new java.io.ByteArrayInputStream(java.util.Base64.getDecoder().decode(\""
                  + Base64.getEncoder().encodeToString(assets.get(reference(element)))
                  + "\"))");
    }
  }

  private static void put(ZipOutputStream zip, String name, byte[] bytes) throws IOException {
    var entry = new ZipEntry(name);
    entry.setTime(0);
    zip.putNextEntry(entry);
    zip.write(bytes);
    zip.closeEntry();
  }

  static Content decode(String jrxml, Map<String, String> encoded) {
    var assets = new TreeMap<String, byte[]>();
    if (encoded != null) {
      if (encoded.isEmpty() || encoded.size() > MAX_COUNT) throw invalid();
      for (var entry : encoded.entrySet()) {
        if (entry.getValue() == null || entry.getValue().length() > 10924) throw invalid();
        try {
          byte[] bytes = Base64.getDecoder().decode(entry.getValue());
          if (!Base64.getEncoder().encodeToString(bytes).equals(entry.getValue())) throw invalid();
          assets.put(entry.getKey(), bytes);
        } catch (IllegalArgumentException e) {
          throw invalid();
        }
      }
    }
    return validate(TrustedTemplate.validate(jrxml), assets);
  }

  static Content validate(byte[] source, Map<String, byte[]> assets) {
    TrustedTemplate.validate(new String(source, StandardCharsets.UTF_8));
    if (assets.size() > MAX_COUNT) throw invalid();
    int total = 0;
    var copy = new TreeMap<String, byte[]>();
    for (var entry : assets.entrySet()) {
      if (entry.getKey() == null || !entry.getKey().matches(NAME)) throw invalid();
      byte[] bytes = entry.getValue();
      if (entry.getKey().endsWith(".png")) validatePng(bytes);
      else if (entry.getKey().endsWith(".jrtx")) TemplateStyles.read(bytes);
      else if (entry.getKey().endsWith(".ttf")) TemplateFonts.validate(bytes);
      else {
        if (bytes == null || bytes.length < 8 || bytes.length > MAX_FILE_BYTES) throw invalid();
        if (!Arrays.equals(
            bytes, TrustedTemplate.validate(new String(bytes, StandardCharsets.UTF_8))))
          throw invalid();
      }
      total += bytes.length;
      if (total > MAX_TOTAL_BYTES) throw invalid();
      copy.put(entry.getKey(), bytes.clone());
    }
    try {
      TemplateSubreports.validate(source, copy);
      return new Content(source.clone(), Collections.unmodifiableMap(copy));
    } catch (Exception e) {
      throw invalid();
    }
  }

  static String reference(Element image) {
    if ((image.hasAttribute("lazy") && !"false".equals(image.getAttribute("lazy")))
        || (image.hasAttribute("onErrorType")
            && !"Error".equals(image.getAttribute("onErrorType")))) throw invalid();
    var expressions = image.getElementsByTagName("expression");
    if (expressions.getLength() != 1 || expressions.item(0).getParentNode() != image)
      throw invalid();
    String literal = expressions.item(0).getTextContent().trim();
    if (!literal.matches("\"assets/[a-z][a-z0-9-]{0,31}\\.png\"")) throw invalid();
    return literal.substring(1, literal.length() - 1);
  }

  private static void validatePng(byte[] bytes) {
    byte[] signature = {(byte) 137, 80, 78, 71, 13, 10, 26, 10};
    if (bytes == null
        || bytes.length < 8
        || bytes.length > MAX_FILE_BYTES
        || !Arrays.equals(signature, Arrays.copyOf(bytes, 8))) throw invalid();
    try (var stream = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
      var readers = ImageIO.getImageReaders(stream);
      if (!readers.hasNext()) throw invalid();
      var reader = readers.next();
      try {
        reader.setInput(stream, true, true);
        int width = reader.getWidth(0), height = reader.getHeight(0);
        if (!"png".equalsIgnoreCase(reader.getFormatName())
            || width < 1
            || height < 1
            || width > MAX_DIMENSION
            || height > MAX_DIMENSION
            || reader.read(0) == null) throw invalid();
      } finally {
        reader.dispose();
      }
    } catch (IOException | RuntimeException e) {
      throw invalid();
    }
  }

  private static IllegalArgumentException invalid() {
    return new IllegalArgumentException("Invalid or incomplete template assets");
  }
}
