package br.com.ciclo.study.domain;

import static org.assertj.core.api.Assertions.*;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class CompetitionTest {
  @Test
  void onlyReviewedCompetitionCanBecomeActive() {
    var c = Competition.create(UUID.randomUUID(), "TRF", "Analista", "FCC", null);
    assertThatThrownBy(c::activate).isInstanceOf(IllegalStateException.class);
    c.processing();
    c.review();
    c.activate();
    assertThat(c.status()).isEqualTo(Competition.Status.ACTIVE);
  }
}
