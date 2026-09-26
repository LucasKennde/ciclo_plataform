package br.com.ciclo.identity.domain;

import static org.assertj.core.api.Assertions.*;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class UserTest {
  @Test
  void suspendedStudentCannotAuthenticate() {
    var user =
        new User(
            UUID.randomUUID(),
            "student@example.com",
            "Student",
            "hash",
            User.Role.STUDENT,
            User.Status.ACTIVE,
            Instant.now(),
            Instant.now());
    user.suspend();
    assertThat(user.canAuthenticate()).isFalse();
  }

  @Test
  void adminCannotBeSuspendedByStudentFlow() {
    var user =
        new User(
            UUID.randomUUID(),
            "admin@example.com",
            "Admin",
            "hash",
            User.Role.ADMIN,
            User.Status.ACTIVE,
            Instant.now(),
            Instant.now());
    assertThatThrownBy(user::suspend).isInstanceOf(IllegalStateException.class);
  }
}
