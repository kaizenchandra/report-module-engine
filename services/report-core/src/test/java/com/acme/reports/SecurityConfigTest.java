package com.acme.reports;

import static org.assertj.core.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.Test;

class SecurityConfigTest {
  @Test
  void allowlistsRolesAndRejectsMalformedClaims() {
    assertThat(SecurityConfig.roles(Map.of("roles", List.of("REPORT_USER", "ADMIN", 23))))
        .extracting(Object::toString)
        .containsExactly("ROLE_REPORT_USER");
    assertThat(SecurityConfig.roles(Map.of("roles", "REPORT_ADMIN"))).isEmpty();
    assertThat(SecurityConfig.roles(Map.of())).isEmpty();
  }
}
