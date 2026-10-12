package com.acme.reports;

import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserService;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;

@Configuration
@EnableMethodSecurity
class SecurityConfig {
  static List<SimpleGrantedAuthority> roles(Map<String, Object> claims) {
    Object value = claims.get("roles");
    if (!(value instanceof Collection<?> values)) return List.of();
    return values.stream()
        .filter(String.class::isInstance)
        .map(String.class::cast)
        .filter(Set.of("REPORT_USER", "REPORT_ADMIN", "DELIVERY_SERVICE")::contains)
        .map(r -> new SimpleGrantedAuthority("ROLE_" + r))
        .toList();
  }

  @Bean
  JwtDecoder jwtDecoder(
      @Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri}") String issuer,
      @Value("${report.audience}") String audience) {
    NimbusJwtDecoder decoder = JwtDecoders.fromIssuerLocation(issuer);
    decoder.setJwtValidator(
        new DelegatingOAuth2TokenValidator<>(
            JwtValidators.createDefaultWithIssuer(issuer),
            jwt ->
                jwt.getAudience().contains(audience)
                    ? OAuth2TokenValidatorResult.success()
                    : OAuth2TokenValidatorResult.failure(
                        new OAuth2Error("invalid_token", "Wrong audience", null))));
    return decoder;
  }

  @Bean
  SecurityFilterChain security(HttpSecurity http, @Value("${report.origin}") String origin)
      throws Exception {
    var converter = new JwtAuthenticationConverter();
    converter.setJwtGrantedAuthoritiesConverter(jwt -> new ArrayList<>(roles(jwt.getClaims())));
    var oidc = new OidcUserService();
    http.authorizeHttpRequests(
            a ->
                a.requestMatchers("/actuator/health/**", "/oauth2/**", "/login/**")
                    .permitAll()
                    .requestMatchers("/api/v1/internal/**")
                    .hasRole("DELIVERY_SERVICE")
                    .requestMatchers("/api/v1/**")
                    .hasAnyRole("REPORT_USER", "REPORT_ADMIN")
                    .anyRequest()
                    .denyAll())
        .oauth2Login(
            o ->
                o.userInfoEndpoint(
                        u ->
                            u.oidcUserService(
                                request -> {
                                  var user = oidc.loadUser(request);
                                  return new DefaultOidcUser(
                                      roles(user.getClaims()),
                                      user.getIdToken(),
                                      user.getUserInfo());
                                }))
                    .defaultSuccessUrl(origin, true))
        .oauth2ResourceServer(o -> o.jwt(j -> j.jwtAuthenticationConverter(converter)))
        .exceptionHandling(
            e ->
                e.defaultAuthenticationEntryPointFor(
                    (req, res, ex) -> {
                      res.setStatus(401);
                      res.setContentType("application/problem+json");
                      res.getWriter()
                          .write("{\"title\":\"Authentication required\",\"status\":401}");
                    },
                    new AntPathRequestMatcher("/api/**")))
        .logout(
            l ->
                l.logoutSuccessUrl(origin)
                    .invalidateHttpSession(true)
                    .deleteCookies("REPORT_SESSION"));
    // Session requests keep Spring's synchronizer CSRF protection. Resource-server bearer requests
    // are exempted by Spring's OAuth2 resource-server configuration, not by arbitrary header
    // checks.
    return http.build();
  }
}
