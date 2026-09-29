package br.com.ciclo.ai.presentation;

import br.com.ciclo.shared.http.ApiError;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;
import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice
public class AiExceptionHandler {
  @ExceptionHandler({IllegalArgumentException.class, IllegalStateException.class})
  ResponseEntity<ApiError> invalid(Exception e, HttpServletRequest r) {
    return unprocessable("ai.invalid", e.getMessage(), Map.of(), r);
  }

  /**
   * Sem isto, um campo faltando no corpo (ex.: o id do modelo) respondia 400 com o corpo padrão do
   * Spring, que não tem "message" — a tela caía no texto genérico e o admin não descobria o que
   * faltava.
   */
  @ExceptionHandler(MethodArgumentNotValidException.class)
  ResponseEntity<ApiError> validation(MethodArgumentNotValidException e, HttpServletRequest r) {
    Map<String, String> fields =
        e.getBindingResult().getFieldErrors().stream()
            .collect(
                Collectors.toMap(
                    error -> error.getField(),
                    error ->
                        error.getDefaultMessage() == null ? "inválido" : error.getDefaultMessage(),
                    (a, b) -> a,
                    LinkedHashMap::new));
    return unprocessable("ai.validation", "Revise os campos destacados.", fields, r);
  }

  /** Provedor ou operação fora do enum no path/corpo: 500 do Spring vira 422 legível. */
  @ExceptionHandler(MethodArgumentTypeMismatchException.class)
  ResponseEntity<ApiError> typeMismatch(
      MethodArgumentTypeMismatchException e, HttpServletRequest r) {
    return unprocessable(
        "ai.invalid",
        "Valor inválido para \"" + e.getName() + "\": " + e.getValue() + ".",
        Map.of(),
        r);
  }

  @ExceptionHandler(HttpMessageNotReadableException.class)
  ResponseEntity<ApiError> unreadable(HttpMessageNotReadableException e, HttpServletRequest r) {
    return unprocessable("ai.invalid", "Corpo da requisição inválido.", Map.of(), r);
  }

  private static ResponseEntity<ApiError> unprocessable(
      String code, String message, Map<String, String> fields, HttpServletRequest r) {
    String correlation =
        Optional.ofNullable(r.getHeader("x-correlation-id")).orElse(UUID.randomUUID().toString());
    return ResponseEntity.unprocessableEntity()
        .body(
            new ApiError(
                code,
                message == null ? "Requisição inválida." : message,
                correlation,
                Instant.now(),
                fields));
  }
}
