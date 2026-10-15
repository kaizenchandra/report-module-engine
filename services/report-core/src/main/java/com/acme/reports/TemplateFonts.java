package com.acme.reports;

import java.awt.Font;
import java.io.*;
import java.util.*;
import net.sf.jasperreports.engine.*;
import net.sf.jasperreports.engine.fonts.*;
import net.sf.jasperreports.repo.*;
import org.w3c.dom.Element;

/** Bounded TrueType subsets, isolated by content hash and Jasper context. */
final class TemplateFonts {
  static final String NAME = "assets/[a-z][a-z0-9-]{0,31}\\.ttf";
  private static final String PREFIX = "report.bundle.font.";
  private static final String FAMILY = "BundleFont_";

  static boolean allowed(String name) {
    return Set.of("SansSerif", "Serif", "Monospaced").contains(name) || name.matches(NAME);
  }

  static Font validate(byte[] bytes) {
    if (bytes == null
        || bytes.length < 128
        || bytes.length > TemplateAssets.MAX_FILE_BYTES
        || java.nio.ByteBuffer.wrap(bytes).getInt() != 0x00010000) throw invalid();
    var directory = java.nio.ByteBuffer.wrap(bytes);
    int count = Short.toUnsignedInt(directory.getShort(4));
    if (count < 1 || count > 32 || 12 + count * 16 > bytes.length) throw invalid();
    var tags = new HashSet<Integer>();
    for (int i = 0; i < count; i++) {
      int at = 12 + i * 16;
      long offset = Integer.toUnsignedLong(directory.getInt(at + 8)),
          length = Integer.toUnsignedLong(directory.getInt(at + 12));
      if (!tags.add(directory.getInt(at))
          || offset < 12 + count * 16
          || offset % 4 != 0
          || length > bytes.length - offset) throw invalid();
    }
    try (var font =
        new org.apache.fontbox.ttf.TTFParser()
            .parse(new org.apache.pdfbox.io.RandomAccessReadBuffer(bytes))) {
      if (font.getNumberOfGlyphs() < 2
          || font.getNumberOfGlyphs() > 256
          || font.getOS2Windows() == null
          || font.getOS2Windows().getFsType() != 0
          || font.getUnicodeCmapLookup() == null
          || font.getName() == null
          || font.getTableMap().keySet().stream()
              .anyMatch(
                  Set.of(
                          "fvar", "gvar", "CFF ", "CFF2", "SVG ", "CBDT", "CBLC", "sbix", "COLR",
                          "CPAL")
                      ::contains)) throw invalid();
      var awt = Font.createFont(Font.TRUETYPE_FONT, new ByteArrayInputStream(bytes));
      if (!awt.getFamily(Locale.ROOT).matches("[A-Za-z][A-Za-z0-9 _-]{0,63}")
          || !font.getName().matches("[A-Za-z][A-Za-z0-9_-]{0,63}")) throw invalid();
      return awt;
    } catch (Exception e) {
      throw invalid();
    }
  }

  static Set<String> references(Element root) {
    var result = new HashSet<String>();
    var nodes = root.getElementsByTagName("*");
    if (root.hasAttribute("fontName") && root.getAttribute("fontName").matches(NAME))
      result.add(root.getAttribute("fontName"));
    for (int i = 0; i < nodes.getLength(); i++) {
      var element = (Element) nodes.item(i);
      if (element.hasAttribute("fontName") && element.getAttribute("fontName").matches(NAME))
        result.add(element.getAttribute("fontName"));
    }
    return result;
  }

  static void expand(Element root, Map<String, byte[]> assets) {
    var nodes = new ArrayList<Element>();
    nodes.add(root);
    var all = root.getElementsByTagName("*");
    for (int i = 0; i < all.getLength(); i++) nodes.add((Element) all.item(i));
    for (var element : nodes) {
      String path = element.getAttribute("fontName");
      if (path.matches(NAME))
        element.setAttribute("fontName", FAMILY + Renderer.sha256(assets.get(path)));
    }
  }

  static void embed(Element root, Map<String, byte[]> assets) {
    var seen = new HashSet<String>();
    for (var asset : assets.entrySet()) {
      if (!asset.getKey().endsWith(".ttf")) continue;
      String hash = Renderer.sha256(asset.getValue());
      if (!seen.add(hash)) continue;
      var property = root.getOwnerDocument().createElement("property");
      property.setAttribute("name", PREFIX + hash);
      property.setAttribute("value", Base64.getEncoder().encodeToString(asset.getValue()));
      root.insertBefore(property, root.getFirstChild());
    }
  }

  record Scope(JasperReportsContext context, Map<String, Font> fonts) {
    void check(JasperPrint print) throws JRException {
      for (var page : print.getPages()) checkElements(page.getElements());
    }

    private void checkElements(List<JRPrintElement> elements) throws JRException {
      for (var element : elements) {
        if (element instanceof JRPrintFrame frame) checkElements(frame.getElements());
        if (element instanceof JRPrintText text && fonts.containsKey(text.getFontName())) {
          Font font = fonts.get(text.getFontName());
          if (text.getFullText()
              .codePoints()
              .anyMatch(cp -> cp != '\n' && cp != '\r' && cp != '\t' && !font.canDisplay(cp)))
            throw new JRException("TEMPLATE_FONT_MISSING_GLYPH");
        }
      }
    }
  }

  static Scope scope(JasperReport report) {
    var data = new HashMap<String, byte[]>();
    var fonts = new HashMap<String, Font>();
    for (String name : report.getPropertiesMap().getPropertyNames()) {
      if (!name.startsWith(PREFIX)) continue;
      String hash = name.substring(PREFIX.length());
      String value = report.getPropertiesMap().getProperty(name);
      if (value.length() > 10924) throw invalid();
      byte[] bytes = Base64.getDecoder().decode(value);
      if (!hash.matches("[0-9a-f]{64}")
          || !hash.equals(Renderer.sha256(bytes))
          || data.size() >= TemplateAssets.MAX_COUNT) throw invalid();
      fonts.put(FAMILY + hash, validate(bytes));
      data.put("bundle-font:" + hash + ".ttf", bytes);
    }
    if (data.isEmpty()) return new Scope(DefaultJasperReportsContext.getInstance(), Map.of());
    var context = new SimpleJasperReportsContext(DefaultJasperReportsContext.getInstance());
    context.setProperty("net.sf.jasperreports.font.use.own.temp.file", "false");
    context.setExtensions(RepositoryService.class, List.of(new MemoryFonts(data)));
    var families = new ArrayList<FontFamily>();
    for (var entry : data.entrySet()) {
      String hash = entry.getKey().substring("bundle-font:".length(), entry.getKey().length() - 4);
      var face = new SimpleFontFace(context);
      face.setTtf(entry.getKey());
      var family = new SimpleFontFamily(context);
      family.setName(FAMILY + hash);
      family.setNormalFace(face);
      family.setPdfEncoding("Identity-H");
      family.setPdfEmbedded(true);
      String exportName = fonts.get(FAMILY + hash).getFamily(Locale.ROOT);
      family.setExportFonts(
          Map.of("net.sf.jasperreports.xlsx", exportName, "net.sf.jasperreports.docx", exportName));
      families.add(family);
    }
    context.setExtensions(FontFamily.class, families);
    return new Scope(context, fonts);
  }

  private record MemoryFonts(Map<String, byte[]> data) implements RepositoryService {
    @Override
    public Resource getResource(String name) {
      byte[] bytes = data.get(name);
      if (bytes == null) return null;
      var resource = new InputStreamResource();
      resource.setName(name);
      resource.setInputStream(new ByteArrayInputStream(bytes));
      return resource;
    }

    @Override
    public <K extends Resource> K getResource(String name, Class<K> type) {
      var resource = getResource(name);
      return type.isInstance(resource) ? type.cast(resource) : null;
    }

    @Override
    public void saveResource(String name, Resource resource) {
      throw new UnsupportedOperationException();
    }
  }

  private static IllegalArgumentException invalid() {
    return new IllegalArgumentException("Invalid or unsupported TrueType subset");
  }
}
