package com.acme.reports;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
class CorrelationFilter extends OncePerRequestFilter {
  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    String supplied = request.getHeader("X-Correlation-ID");
    String id =
        supplied != null && supplied.matches("[a-zA-Z0-9_-]{1,64}")
            ? supplied
            : UUID.randomUUID().toString();
    response.setHeader("X-Correlation-ID", id);
    MDC.put("correlationId", id);
    try {
      chain.doFilter(request, response);
    } finally {
      MDC.remove("correlationId");
    }
  }
}
