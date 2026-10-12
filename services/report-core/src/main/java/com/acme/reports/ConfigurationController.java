package com.acme.reports;

import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/configurations")
class ConfigurationController {
  private final ConfigurationCodec codec;

  ConfigurationController(ConfigurationCodec codec) {
    this.codec = codec;
  }

  @PostMapping(
      value = "/convert",
      consumes = {"application/json", "application/yaml"},
      produces = "application/json")
  ResponseEntity<ConfigurationCodec.Converted> convert(
      @RequestBody byte[] bytes,
      @RequestHeader("Content-Type") String contentType,
      @RequestParam String target)
      throws Exception {
    return ResponseEntity.ok()
        .header("Cache-Control", "no-store")
        .body(
            codec.convert(
                bytes,
                MediaType.parseMediaType(contentType)
                    .isCompatibleWith(MediaType.valueOf("application/yaml")),
                target));
  }
}
