package fr.deseille.social.questions;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.ErrorResponseException;

class QuotaQuestionsTest {

    private static final ZoneId PARIS = ZoneId.of("Europe/Paris");

    /** Horloge déplaçable pour simuler le changement de jour. */
    private static final class Horloge extends Clock {
        private Instant instant = Instant.parse("2026-11-01T10:00:00Z");

        @Override
        public ZoneId getZone() {
            return PARIS;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }

    @Test
    void refuse_au_dela_du_quota_puis_repart_le_lendemain() {
        Horloge horloge = new Horloge();
        SimpleMeterRegistry registre = new SimpleMeterRegistry();
        QuotaQuestions quota = new QuotaQuestions(new QuestionsProperties(2), horloge, new MetriquesQuestions(registre));

        quota.consommer();
        quota.consommer();
        assertThatThrownBy(quota::consommer)
                .isInstanceOfSatisfying(ErrorResponseException.class,
                        e -> org.assertj.core.api.Assertions.assertThat(e.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS));

        org.assertj.core.api.Assertions.assertThat(registre.counter("droits.questions.quota_atteint").count()).isEqualTo(1.0);
        horloge.instant = horloge.instant.plus(Duration.ofDays(1)).atZone(ZoneOffset.UTC).toInstant();
        assertThatCode(quota::consommer).doesNotThrowAnyException();
    }
}
