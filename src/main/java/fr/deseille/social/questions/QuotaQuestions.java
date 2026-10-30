package fr.deseille.social.questions;

import java.time.Clock;
import java.time.LocalDate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Component;
import org.springframework.web.ErrorResponseException;

/**
 * Quota journalier global de questions, en mémoire : garde-fou contre une dépense incontrôlée tant
 * que l'API n'a ni authentification ni limitation par client. Remis à zéro chaque jour (horloge de
 * l'application) et au redémarrage.
 */
@Component
public class QuotaQuestions {

    private final int quotaJournalier;
    private final Clock clock;
    private final MetriquesQuestions metriques;
    private LocalDate jour;
    private int consommees;

    public QuotaQuestions(QuestionsProperties properties, Clock clock, MetriquesQuestions metriques) {
        this.quotaJournalier = properties.quotaJournalier();
        this.clock = clock;
        this.metriques = metriques;
    }

    /** Compte une question, ou la refuse (429) si le quota du jour est atteint. */
    public synchronized void consommer() {
        LocalDate aujourdHui = LocalDate.now(clock);
        if (!aujourdHui.equals(jour)) {
            jour = aujourdHui;
            consommees = 0;
        }
        if (consommees >= quotaJournalier) {
            metriques.quotaAtteint();
            throw new ErrorResponseException(HttpStatus.TOO_MANY_REQUESTS, ProblemDetail.forStatusAndDetail(
                    HttpStatus.TOO_MANY_REQUESTS, "Nombre maximal de questions atteint pour aujourd'hui."), null);
        }
        consommees++;
    }
}
