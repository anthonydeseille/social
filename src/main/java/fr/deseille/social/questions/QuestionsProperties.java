package fr.deseille.social.questions;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Configuration du trajet des questions (préfixe {@code droits.questions}), commune aux fournisseurs.
 *
 * @param quotaJournalier nombre maximal de questions par jour, toutes origines confondues : garde-fou
 *                        de dépense (Claude, en complément de la limite fixée dans la console
 *                        Anthropic) ou de charge (modèle local)
 */
@ConfigurationProperties(prefix = "droits.questions")
public record QuestionsProperties(@DefaultValue("200") int quotaJournalier) {
}
