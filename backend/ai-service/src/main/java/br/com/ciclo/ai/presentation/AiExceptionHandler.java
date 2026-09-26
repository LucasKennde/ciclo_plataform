package br.com.ciclo.ai.presentation;

import br.com.ciclo.shared.http.ApiError;
import jakarta.servlet.http.HttpServletRequest;
import java.util.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestControllerAdvice
public class AiExceptionHandler {
  @ExceptionHandler({IllegalArgumentException.class, IllegalStateException.class})
  ResponseEntity<ApiError> invalid(Exception e, HttpServletRequest r) {
    String c =
        Optional.ofNullable(r.getHeader("x-correlation-id")).orElse(UUID.randomUUID().toString());
    return ResponseEntity.unprocessableEntity().body(ApiError.of("ai.invalid", e.getMessage(), c));
  }
}
