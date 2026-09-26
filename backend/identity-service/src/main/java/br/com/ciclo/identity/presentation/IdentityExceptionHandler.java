package br.com.ciclo.identity.presentation;

import br.com.ciclo.identity.application.IdentityApplicationService.*;
import br.com.ciclo.shared.http.ApiError;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.springframework.http.*;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;

@RestControllerAdvice
public class IdentityExceptionHandler {
  @ExceptionHandler(Conflict.class)
  ResponseEntity<ApiError> conflict(Conflict e, HttpServletRequest r) {
    return response(HttpStatus.CONFLICT, "identity.conflict", e, r);
  }

  @ExceptionHandler(Unauthorized.class)
  ResponseEntity<ApiError> unauthorized(Unauthorized e, HttpServletRequest r) {
    return response(HttpStatus.UNAUTHORIZED, "identity.unauthorized", e, r);
  }

  @ExceptionHandler(NotFound.class)
  ResponseEntity<ApiError> notFound(NotFound e, HttpServletRequest r) {
    return response(HttpStatus.NOT_FOUND, "identity.not_found", e, r);
  }

  @ExceptionHandler(MailUnavailable.class)
  ResponseEntity<ApiError> mailUnavailable(MailUnavailable e, HttpServletRequest r) {
    return response(HttpStatus.BAD_GATEWAY, "mail.unavailable", e, r);
  }

  @ExceptionHandler({IllegalArgumentException.class, MethodArgumentNotValidException.class})
  ResponseEntity<ApiError> invalid(Exception e, HttpServletRequest r) {
    return response(HttpStatus.BAD_REQUEST, "request.invalid", e, r);
  }

  private ResponseEntity<ApiError> response(
      HttpStatus status, String code, Exception e, HttpServletRequest request) {
    String correlation = request.getHeader("x-correlation-id");
    if (correlation == null) correlation = UUID.randomUUID().toString();
    return ResponseEntity.status(status).body(ApiError.of(code, e.getMessage(), correlation));
  }
}
