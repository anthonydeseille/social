package fr.deseille.social.questions;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Propriétés lues quel que soit le fournisseur : le quota, et celles de Claude, dont l'évaluation a
 * besoin pour son juge même quand les réponses viennent d'un modèle local.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({QuestionsProperties.class, ClaudeProperties.class})
class ConfigurationQuestions {
}
