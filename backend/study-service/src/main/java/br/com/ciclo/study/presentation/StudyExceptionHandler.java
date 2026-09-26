package br.com.ciclo.study.presentation;

import br.com.ciclo.shared.http.ApiError;
import br.com.ciclo.study.application.StudyApplicationService.NotFound;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestControllerAdvice
public class StudyExceptionHandler {
  @ExceptionHandler(NotFound.class)
  ResponseEntity<ApiError> notFound(Exception e, HttpServletRequest r) {
    return response(HttpStatus.NOT_FOUND, "study.not_found", e, r);
  }

  @ExceptionHandler({IllegalArgumentException.class, IllegalStateException.class})
  ResponseEntity<ApiError> invalid(Exception e, HttpServletRequest r) {
    return response(HttpStatus.UNPROCESSABLE_ENTITY, "study.invalid", e, r);
  }

  private ResponseEntity<ApiError> response(
      HttpStatus status, String code, Exception e, HttpServletRequest request) {
    String correlation = request.getHeader("x-correlation-id");
    if (correlation == null) correlation = UUID.randomUUID().toString();
    return ResponseEntity.status(status).body(ApiError.of(code, e.getMessage(), correlation));
  }
}
