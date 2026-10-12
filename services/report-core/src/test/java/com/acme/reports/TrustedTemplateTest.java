package com.acme.reports;

import static org.assertj.core.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

class TrustedTemplateTest {
  String sample() throws Exception {
    try (var input = getClass().getResourceAsStream("/templates/sales-summary.jrxml")) {
      return new String(input.readAllBytes(), StandardCharsets.UTF_8);
    }
  }

  @Test
  void hardenedXmlAndUnsupportedDependenciesFailClosed() throws Exception {
    String good = sample();
    assertThat(TrustedTemplate.validate(good)).isEqualTo(good.getBytes(StandardCharsets.UTF_8));
    for (String bad :
        List.of(
            "<!DOCTYPE jasperReport SYSTEM 'http://127.0.0.1:9/never'>" + good,
            "<!DOCTYPE jasperReport [<!ENTITY expand 'payload'>]>" + good,
            good.replace("language=\"java\"", "language=\"groovy\""),
            good.replace(
                "<parameter", "<template>http://127.0.0.1:9/template</template><parameter"),
            good.replace(
                "<parameter",
                "<property name=\"net.sf.jasperreports.compiler.java\""
                    + " value=\"Other\"/><parameter"),
            good.replace("kind=\"textField\"", "kind=\"image\""),
            good.replace("kind=\"textField\"", "kind=\"subreport\""),
            good.replace("<parameter", "<query>select * from example</query><parameter"),
            good.replace("fontSize=\"22.0\"", "fontName=\"https://example.invalid/font\""),
            good.replace("language=\"java\"", "resourceBundle=\"external\" language=\"java\""),
            good.replace("<parameter", "<subDataset name=\"other\"/><parameter"),
            good.replace(
                "<title height=\"90\">",
                "<title height=\"90\">" + "<frame>".repeat(34) + "</frame>".repeat(34)),
            good + " ".repeat(32768),
            good + "\ud800")) {
      assertThatThrownBy(() -> TrustedTemplate.validate(bad))
          .isInstanceOf(IllegalArgumentException.class);
    }
  }
}
