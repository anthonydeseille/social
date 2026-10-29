package fr.deseille.social.questions;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Configuration de Claude (préfixe {@code droits.claude}). La clé d'API est lue par le SDK dans la
 * variable d'environnement {@code ANTHROPIC_API_KEY}, jamais dans la configuration.
 *
 * @param modele modèle utilisé pour la reformulation et la rédaction
 */
@ConfigurationProperties(prefix = "droits.claude")
public record ClaudeProperties(@DefaultValue("claude-opus-5-5") String modele) {
}
