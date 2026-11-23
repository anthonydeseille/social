package fr.deseille.social.questions;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.common.KeyValue;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class MetriquesQuestionsTest {

    @Test
    void etape_observee_mesuree_et_annotee_sans_rien_hors_etape() {
        SimpleMeterRegistry mesures = new SimpleMeterRegistry();
        List<Observation.Context> terminees = new ArrayList<>();
        ObservationRegistry observations = ObservationRegistry.create();
        observations.observationConfig()
                .observationHandler(new io.micrometer.core.instrument.observation.DefaultMeterObservationHandler(mesures))
                .observationHandler(new ObservationHandler<>() {
                    @Override
                    public void onStop(Observation.Context contexte) {
                        terminees.add(contexte);
                    }

                    @Override
                    public boolean supportsContext(Observation.Context contexte) {
                        return true;
                    }
                });
        MetriquesQuestions metriques = new MetriquesQuestions(mesures, observations);

        metriques.annoter("hors_etape", 1);
        String resultat = metriques.chronometrer("redaction", () -> {
            metriques.annoter("modele", "claude-opus-5-5");
            metriques.jetons("claude", "redaction", 1200, 300);
            metriques.cout("claude", "redaction", 0.0123);
            return "ok";
        });

        assertThat(resultat).isEqualTo("ok");
        assertThat(mesures.find("droits.questions.etape").tag("etape", "redaction").timer().count()).isEqualTo(1);
        assertThat(terminees).singleElement().satisfies(c -> {
            assertThat(c.getContextualName()).isEqualTo("redaction");
            assertThat(c.getHighCardinalityKeyValues()).extracting(KeyValue::getKey, KeyValue::getValue)
                    .contains(org.assertj.core.groups.Tuple.tuple("modele", "claude-opus-5-5"),
                            org.assertj.core.groups.Tuple.tuple("jetons.entree", "1200"),
                            org.assertj.core.groups.Tuple.tuple("cout_usd", "0.01230"))
                    .extracting(t -> t.toList().getFirst()).doesNotContain("hors_etape");
        });
    }
}
