package fr.deseille.social.questions;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Configuration de Claude (préfixe {@code droits.claude}). La clé d'API est lue par le SDK dans la
 * variable d'environnement {@code ANTHROPIC_API_KEY}, jamais dans la configuration.
 *
 * Lue quel que soit le fournisseur choisi : le juge de l'évaluation reste Claude.
 *
 * @param modele     modèle utilisé pour la reformulation et la rédaction
 * @param delai      durée maximale d'un appel (la rédaction, avec réflexion, peut être longue)
 * @param tentatives nouvelles tentatives du SDK sur erreur temporaire (429, 5xx, réseau)
 */
@ConfigurationProperties(prefix = "droits.claude")
public record ClaudeProperties(@DefaultValue("claude-opus-5-5") String modele,
                               @DefaultValue("3m") Duration delai,
                               @DefaultValue("2") int tentatives) {
}
