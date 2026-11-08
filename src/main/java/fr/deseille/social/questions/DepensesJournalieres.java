package fr.deseille.social.questions;

import java.time.Clock;
import java.time.LocalDate;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Component;
import org.springframework.web.ErrorResponseException;

/**
 * Dépense du jour en appels payants au modèle, en mémoire, et budget journalier
 * ({@code droits.questions.budget-journalier-usd}) : au-delà, les questions sont refusées (429)
 * jusqu'au lendemain. Le quota compte des questions, le budget des dollars : une rédaction longue
 * ou un modèle plus cher épuise le budget avant le quota.
 * <p>
 * Garde-fou <b>approximatif</b> : la dépense est constatée à la fin de chaque appel, pas réservée
 * avant. Des questions simultanées passent toutes le contrôle avant que la dépense n'augmente : le
 * dépassement est borné par le nombre de questions en cours fois le coût d'une question (quelques
 * dixièmes de dollar avec Opus), acceptable pour un budget de quelques dollars.
 * <p>
 * Alerte dans le journal à 80 % puis à 100 % du budget, une fois par jour ; la jauge
 * {@code droits.assistant.depense_jour_usd} permet d'alerter dans un tableau de bord. Remis à zéro
 * chaque jour (horloge de l'application) et au redémarrage : ce garde-fou complète la limite de
 * dépense mensuelle fixée dans la Console Anthropic, il ne la remplace pas.
 */
@Component
public class DepensesJournalieres {

    private static final Logger log = LoggerFactory.getLogger(DepensesJournalieres.class);

    private final double budget;
    private final Clock clock;
    private final MetriquesQuestions metriques;
    private LocalDate jour;
    private double depense;
    private boolean alerte80;
    private boolean alerte100;

    @Autowired
    public DepensesJournalieres(QuestionsProperties properties, Clock clock, MetriquesQuestions metriques) {
        this(properties.budgetJournalierUsd(), clock, metriques);
    }

    DepensesJournalieres(double budget, Clock clock, MetriquesQuestions metriques) {
        this(budget, clock, metriques, true);
    }

    private DepensesJournalieres(double budget, Clock clock, MetriquesQuestions metriques, boolean jauge) {
        this.budget = budget;
        this.clock = clock;
        this.metriques = metriques;
        if (jauge) {
            metriques.jauge("droits.assistant.depense_jour_usd", "Dépense du jour en appels au modèle (dollars)",
                    this::depenseDuJour);
        }
    }

    /**
     * Sans budget (tests, outils) : la dépense est suivie, jamais refusée, et la jauge n'est pas
     * enregistrée (Micrometer garderait la première instance d'un registre partagé).
     */
    public static DepensesJournalieres sansBudget(MetriquesQuestions metriques) {
        return new DepensesJournalieres(Double.POSITIVE_INFINITY, Clock.systemUTC(), metriques, false);
    }

    /** Refuse la question (429) si le budget du jour est épuisé. */
    public synchronized void verifier() {
        remettreAZeroSiNouveauJour();
        if (depense >= budget) {
            metriques.budgetAtteint();
            throw new ErrorResponseException(HttpStatus.TOO_MANY_REQUESTS, ProblemDetail.forStatusAndDetail(
                    HttpStatus.TOO_MANY_REQUESTS, "Budget du jour atteint : réessayez demain."), null);
        }
    }

    /** Ajoute le coût d'un appel ; un coût inconnu (modèle absent de {@link PrixModeles}) est ignoré. */
    public synchronized void ajouter(double dollars) {
        if (Double.isNaN(dollars) || dollars <= 0) {
            return;
        }
        remettreAZeroSiNouveauJour();
        depense += dollars;
        if (!alerte100 && depense >= budget) {
            alerte100 = true;
            log.warn("Budget du jour atteint : {} $ dépensés sur {} $ ; questions refusées jusqu'à demain",
                    String.format(Locale.ROOT, "%.2f", depense), budget);
        } else if (!alerte80 && depense >= 0.8 * budget) {
            alerte80 = true;
            log.warn("80 % du budget du jour atteint : {} $ dépensés sur {} $",
                    String.format(Locale.ROOT, "%.2f", depense), budget);
        }
    }

    public synchronized double depenseDuJour() {
        remettreAZeroSiNouveauJour();
        return depense;
    }

    private void remettreAZeroSiNouveauJour() {
        LocalDate aujourdHui = LocalDate.now(clock);
        if (!aujourdHui.equals(jour)) {
            jour = aujourdHui;
            depense = 0;
            alerte80 = false;
            alerte100 = false;
        }
    }
}
