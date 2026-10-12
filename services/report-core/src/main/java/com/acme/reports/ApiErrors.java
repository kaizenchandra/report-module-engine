package com.acme.reports;

import java.util.*;
import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestControllerAdvice
class ApiErrors {
  @ExceptionHandler(ResponseStatusException.class)
  ResponseEntity<ProblemDetail> domain(ResponseStatusException e) {
    var problem =
        ProblemDetail.forStatusAndDetail(
            e.getStatusCode(), Objects.requireNonNullElse(e.getReason(), "Request failed"));
    return ResponseEntity.status(e.getStatusCode()).body(problem);
  }

  @ExceptionHandler(MethodArgumentNotValidException.class)
  ResponseEntity<ProblemDetail> validation(MethodArgumentNotValidException e) {
    var problem =
        ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Request validation failed");
    problem.setProperty(
        "errors",
        e.getBindingResult().getFieldErrors().stream()
            .map(
                f ->
                    Map.of(
                        "field",
                        f.getField(),
                        "message",
                        Objects.requireNonNullElse(f.getDefaultMessage(), "Invalid value")))
            .toList());
    return ResponseEntity.badRequest().body(problem);
  }

  @ExceptionHandler(jakarta.validation.ConstraintViolationException.class)
  ResponseEntity<ProblemDetail> configuration(jakarta.validation.ConstraintViolationException e) {
    var problem =
        ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Request validation failed");
    problem.setProperty(
        "errors",
        e.getConstraintViolations().stream()
            .map(v -> Map.of("field", v.getPropertyPath().toString(), "message", v.getMessage()))
            .sorted(Comparator.comparing(v -> v.get("field")))
            .toList());
    return ResponseEntity.badRequest().body(problem);
  }

  @ExceptionHandler(HttpMessageNotReadableException.class)
  ResponseEntity<ProblemDetail> malformed() {
    return ResponseEntity.badRequest()
        .body(
            ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST, "Malformed JSON or unknown field"));
  }
}
