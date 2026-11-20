package fr.deseille.social.questions;

import io.github.anthonydeseille.llmeval4j.budget.BudgetExceededException;
import io.github.anthonydeseille.llmeval4j.budget.BudgetListener;
import io.github.anthonydeseille.llmeval4j.budget.DailyBudget;
import java.time.Clock;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Component;
import org.springframework.web.ErrorResponseException;

/**
 * Dépense du jour en appels payants au modèle et budget journalier
 * ({@code droits.questions.budget-journalier-usd}) : au-delà, les questions sont refusées (429)
 * jusqu'au lendemain. Le quota compte des questions, le budget des dollars : une rédaction longue
 * ou un modèle plus cher épuise le budget avant le quota.
 * <p>
 * Le décompte est celui de {@link DailyBudget} (llmeval4j) ; cette classe le branche sur Spring : refus
 * en 429, alertes dans le journal à 80 % puis à 100 % (une fois par jour), compteur des refus et jauge
 * {@code droits.assistant.depense_jour_usd} pour un tableau de bord.
 * <p>
 * Garde-fou <b>approximatif</b> : la dépense est constatée à la fin de chaque appel, pas réservée
 * avant. Des questions simultanées passent toutes le contrôle avant que la dépense n'augmente : le
 * dépassement est borné par le nombre de questions en cours fois le coût d'une question (quelques
 * dixièmes de dollar avec Opus), acceptable pour un budget de quelques dollars. Remis à zéro chaque
 * jour (horloge de l'application) et au redémarrage : ce garde-fou complète la limite de dépense
 * mensuelle fixée dans la Console Anthropic, il ne la remplace pas.
 */
@Component
public class DepensesJournalieres {

    private static final Logger log = LoggerFactory.getLogger(DepensesJournalieres.class);

    private final DailyBudget budget;

    @Autowired
    public DepensesJournalieres(QuestionsProperties properties, Clock clock, MetriquesQuestions metriques) {
        this(properties.budgetJournalierUsd(), clock, metriques);
    }

    DepensesJournalieres(double budget, Clock clock, MetriquesQuestions metriques) {
        this(DailyBudget.builder(budget).clock(clock).listener(alertes(metriques)).build());
        metriques.jauge("droits.assistant.depense_jour_usd", "Dépense du jour en appels au modèle (dollars)",
                this::depenseDuJour);
    }

    private DepensesJournalieres(DailyBudget budget) {
        this.budget = budget;
    }

    /**
     * Sans budget (tests, outils) : la dépense est suivie, jamais refusée, et la jauge n'est pas
     * enregistrée (Micrometer garderait la première instance d'un registre partagé).
     */
    public static DepensesJournalieres sansBudget() {
        return new DepensesJournalieres(DailyBudget.unlimited());
    }

    /** Refuse la question (429) si le budget du jour est épuisé. */
    public void verifier() {
        try {
            budget.check();
        } catch (BudgetExceededException e) {
            throw new ErrorResponseException(HttpStatus.TOO_MANY_REQUESTS, ProblemDetail.forStatusAndDetail(
                    HttpStatus.TOO_MANY_REQUESTS, "Budget du jour atteint : réessayez demain."), e);
        }
    }

    /** Ajoute le coût d'un appel ; un coût inconnu (modèle sans prix, {@code NaN}) est ignoré. */
    public void ajouter(double dollars) {
        budget.add(dollars);
    }

    public double depenseDuJour() {
        return budget.spentToday();
    }

    private static BudgetListener alertes(MetriquesQuestions metriques) {
        return new BudgetListener() {
            @Override
            public void onWarning(double depense, double limite) {
                log.warn("80 % du budget du jour atteint : {} $ dépensés sur {} $", dollars(depense), limite);
            }

            @Override
            public void onExceeded(double depense, double limite) {
                log.warn("Budget du jour atteint : {} $ dépensés sur {} $ ; questions refusées jusqu'à demain",
                        dollars(depense), limite);
            }

            @Override
            public void onRefused(double depense, double limite) {
                metriques.budgetAtteint();
            }
        };
    }

    private static String dollars(double montant) {
        return String.format(Locale.ROOT, "%.2f", montant);
    }
}
