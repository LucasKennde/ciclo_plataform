package br.com.ciclo.study.domain;

import java.time.*;
import java.util.*;

public final class Competition {
  public enum Status {
    DRAFT,
    PROCESSING,
    REVIEW,
    ACTIVE,
    ARCHIVED
  }

  private final UUID id, workspaceId;
  private String title, role, board;
  private LocalDate examDate;
  private Status status;
  private final Instant createdAt;
  private Instant updatedAt;

  public Competition(
      UUID id,
      UUID workspaceId,
      String title,
      String role,
      String board,
      LocalDate examDate,
      Status status,
      Instant createdAt,
      Instant updatedAt) {
    this.id = Objects.requireNonNull(id);
    this.workspaceId = Objects.requireNonNull(workspaceId);
    this.title = required(title);
    this.role = required(role);
    this.board = required(board);
    this.examDate = examDate;
    this.status = Objects.requireNonNull(status);
    this.createdAt = createdAt;
    this.updatedAt = updatedAt;
  }

  public static Competition create(
      UUID workspaceId, String title, String role, String board, LocalDate examDate) {
    var now = Instant.now();
    return new Competition(
        UUID.randomUUID(), workspaceId, title, role, board, examDate, Status.DRAFT, now, now);
  }

  public void processing() {
    if (status != Status.DRAFT && status != Status.REVIEW)
      throw new IllegalStateException("Transição inválida.");
    status = Status.PROCESSING;
    updatedAt = Instant.now();
  }

  public void review() {
    if (status != Status.PROCESSING) throw new IllegalStateException("Transição inválida.");
    status = Status.REVIEW;
    updatedAt = Instant.now();
  }

  public void activate() {
    if (status != Status.REVIEW) throw new IllegalStateException("O edital precisa ser revisado.");
    status = Status.ACTIVE;
    updatedAt = Instant.now();
  }

  public UUID id() {
    return id;
  }

  public UUID workspaceId() {
    return workspaceId;
  }

  public String title() {
    return title;
  }

  public String role() {
    return role;
  }

  public String board() {
    return board;
  }

  public LocalDate examDate() {
    return examDate;
  }

  public Status status() {
    return status;
  }

  public Instant createdAt() {
    return createdAt;
  }

  public Instant updatedAt() {
    return updatedAt;
  }

  private static String required(String value) {
    if (value == null || value.isBlank()) throw new IllegalArgumentException("Campo obrigatório.");
    return value.trim();
  }
}
