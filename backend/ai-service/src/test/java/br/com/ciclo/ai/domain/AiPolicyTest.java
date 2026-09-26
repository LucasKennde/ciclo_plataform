package br.com.ciclo.ai.domain;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.Test;

class AiPolicyTest {
  @Test
  void rejectsIncoherentLimits() {
    assertThatThrownBy(() -> new AiPolicy(20, 10, 100, 1, 1, 1, false))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
