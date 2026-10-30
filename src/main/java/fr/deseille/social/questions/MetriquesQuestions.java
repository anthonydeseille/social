package fr.deseille.social.questions;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;

/**
 * Métriques du trajet des questions (Micrometer, exposées par l'Actuator et au format Prometheus).
 * Aucune donnée personnelle : ni question, ni profil, seulement des comptes, des durées et des
 * jetons, étiquetés par étape ou par issue.
 * <ul>
 *   <li>{@code droits.questions} (issue) : repondue, aucun_article, refus, tronquee, indisponible,
 *       invalide, erreur ;</li>
 *   <li>{@code droits.questions.etape} (etape) : durée de reformulation, recherche, rédaction ;</li>
 *   <li>{@code droits.assistant.appels} (fournisseur, etape, issue) : succes, refus, tronquee,
 *       illisible, erreur ;</li>
 *   <li>{@code droits.assistant.jetons} (fournisseur, etape, sens) : jetons en entrée et en sortie ;</li>
 *   <li>{@code droits.citations} (verification) : verifiee, non_verifiee ;</li>
 *   <li>{@code droits.questions.quota_atteint} : questions refusées par le quota journalier.</li>
 * </ul>
 */
@Component
public class MetriquesQuestions {

    private final MeterRegistry registre;

    public MetriquesQuestions(MeterRegistry registre) {
        this.registre = registre;
    }

    public <T> T chronometrer(String etape, Supplier<T> action) {
        return Timer.builder("droits.questions.etape")
                .description("Durée d'une étape du trajet d'une question")
                .tag("etape", etape)
                .register(registre)
                .record(action);
    }

    public void question(String issue) {
        Counter.builder("droits.questions").description("Questions traitées, par issue")
                .tag("issue", issue).register(registre).increment();
    }

    /** @param fournisseur claude ou ollama */
    public void appelModele(String fournisseur, String etape, String issue) {
        Counter.builder("droits.assistant.appels").description("Appels au modèle de langage, par étape et par issue")
                .tag("fournisseur", fournisseur).tag("etape", etape).tag("issue", issue).register(registre).increment();
    }

    public void jetons(String fournisseur, String etape, long entree, long sortie) {
        Counter.builder("droits.assistant.jetons").description("Jetons consommés").tag("fournisseur", fournisseur)
                .tag("etape", etape).tag("sens", "entree").register(registre).increment(entree);
        Counter.builder("droits.assistant.jetons").description("Jetons consommés").tag("fournisseur", fournisseur)
                .tag("etape", etape).tag("sens", "sortie").register(registre).increment(sortie);
    }

    public void citations(int total, int nonVerifiees) {
        Counter.builder("droits.citations").description("Citations, selon leur vérification")
                .tag("verification", "verifiee").register(registre).increment(total - nonVerifiees);
        Counter.builder("droits.citations").description("Citations, selon leur vérification")
                .tag("verification", "non_verifiee").register(registre).increment(nonVerifiees);
    }

    public void quotaAtteint() {
        Counter.builder("droits.questions.quota_atteint").description("Questions refusées par le quota journalier")
                .register(registre).increment();
    }
}
