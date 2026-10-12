package com.acme.reports;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.*;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
class RequestLimits extends OncePerRequestFilter {
  private static final int LIMIT = 65536;

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    if (!request.getRequestURI().contains("/api/")
        || !("POST".equals(request.getMethod())
            || "PATCH".equals(request.getMethod())
            || "PUT".equals(request.getMethod()))) {
      chain.doFilter(request, response);
      return;
    }
    byte[] body = request.getInputStream().readNBytes(LIMIT + 1);
    if (body.length > LIMIT) {
      response.setStatus(413);
      response.setContentType("application/problem+json");
      response.getWriter().write("{\"status\":413,\"title\":\"Request body exceeds 64 KiB\"}");
      return;
    }
    chain.doFilter(
        new HttpServletRequestWrapper(request) {
          @Override
          public ServletInputStream getInputStream() {
            var stream = new ByteArrayInputStream(body);
            return new ServletInputStream() {
              public int read() {
                return stream.read();
              }

              public boolean isFinished() {
                return stream.available() == 0;
              }

              public boolean isReady() {
                return true;
              }

              public void setReadListener(ReadListener listener) {
                throw new UnsupportedOperationException("Async request bodies are not supported");
              }
            };
          }

          @Override
          public BufferedReader getReader() {
            return new BufferedReader(
                new InputStreamReader(getInputStream(), java.nio.charset.StandardCharsets.UTF_8));
          }
        },
        response);
  }
}
