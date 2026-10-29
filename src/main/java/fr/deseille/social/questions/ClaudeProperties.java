package fr.deseille.social.questions;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Configuration de Claude (préfixe {@code droits.claude}). La clé d'API est lue par le SDK dans la
 * variable d'environnement {@code ANTHROPIC_API_KEY}, jamais dans la configuration.
 *
 * @param modele          modèle utilisé pour la reformulation et la rédaction
 * @param quotaJournalier nombre maximal de questions par jour, toutes origines confondues : garde-fou
 *                        de dépense en complément de la limite fixée dans la console Anthropic
 * @param delai           durée maximale d'un appel (la rédaction, avec réflexion, peut être longue)
 * @param tentatives      nouvelles tentatives du SDK sur erreur temporaire (429, 5xx, réseau)
 */
@ConfigurationProperties(prefix = "droits.claude")
public record ClaudeProperties(@DefaultValue("claude-opus-5-5") String modele,
                               @DefaultValue("200") int quotaJournalier,
                               @DefaultValue("3m") Duration delai,
                               @DefaultValue("2") int tentatives) {
}
