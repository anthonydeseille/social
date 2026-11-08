package fr.deseille.social.questions;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Configuration du trajet des questions (préfixe {@code droits.questions}), commune aux fournisseurs.
 *
 * @param quotaJournalier nombre maximal de questions par jour, toutes origines confondues : garde-fou
 *                        de dépense (Claude, en complément de la limite fixée dans la console
 *                        Anthropic) ou de charge (modèle local)
 * @param budgetJournalierUsd dépense maximale du jour en appels payants au modèle, en dollars
 *                        ({@link DepensesJournalieres}) ; au-delà, les questions sont refusées
 */
@ConfigurationProperties(prefix = "droits.questions")
public record QuestionsProperties(@DefaultValue("200") int quotaJournalier,
                                  @DefaultValue("10") double budgetJournalierUsd) {
}
