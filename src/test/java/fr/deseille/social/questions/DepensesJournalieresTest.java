package fr.deseille.social.questions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.web.ErrorResponseException;

class DepensesJournalieresTest {

    private final SimpleMeterRegistry registre = new SimpleMeterRegistry();
    private final AtomicReference<Instant> maintenant = new AtomicReference<>(Instant.parse("2026-11-08T10:00:00Z"));
    private final Clock horloge = new Clock() {
        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return maintenant.get();
        }
    };
    private final DepensesJournalieres depenses = new DepensesJournalieres(1.0, horloge, new MetriquesQuestions(registre));

    @Test
    void budget_epuise_refuse_les_questions_jusqu_au_lendemain() {
        depenses.ajouter(0.6);
        depenses.verifier();
        depenses.ajouter(0.4);

        assertThatThrownBy(depenses::verifier).isInstanceOf(ErrorResponseException.class)
                .hasMessageContaining("429");
        assertThat(registre.counter("droits.questions.budget_atteint").count()).isEqualTo(1.0);
        assertThat(registre.get("droits.assistant.depense_jour_usd").gauge().value()).isEqualTo(1.0);

        maintenant.set(Instant.parse("2026-11-09T00:00:01Z"));
        depenses.verifier();
        assertThat(depenses.depenseDuJour()).isZero();
    }

    @Test
    void cout_inconnu_ou_nul_ignore() {
        depenses.ajouter(Double.NaN);
        depenses.ajouter(0);

        assertThat(depenses.depenseDuJour()).isZero();
    }

    @Test
    void sans_budget_jamais_refuse() {
        DepensesJournalieres libre = DepensesJournalieres.sansBudget(new MetriquesQuestions(new SimpleMeterRegistry()));
        libre.ajouter(1_000_000);

        libre.verifier();
        assertThat(libre.depenseDuJour()).isEqualTo(1_000_000);
    }

    @Test
    void prix_des_modeles() {
        assertThat(PrixModeles.cout("claude-haiku-5-5", 1_000_000, 1_000_000)).isEqualTo(0.60);
        assertThat(PrixModeles.cout("modele-inconnu", 10, 10)).isNaN();
    }
}
