package fr.deseille.social.questions;

import io.github.anthonydeseille.llmeval4j.prompts.Prompts;

/**
 * Consignes données au modèle, lues dans les fichiers {@code prompts/} du classpath (application :
 * {@code src/main/resources/prompts/}, juge de l'évaluation : {@code src/test/resources/prompts/}).
 * <p>
 * La version d'une consigne est l'empreinte courte du texte exact envoyé au modèle
 * ({@link Prompts#version}, llmeval4j) : elle change d'elle-même à chaque retouche, sans numéro à
 * incrémenter, et relie une réponse, un rapport ou une ligne d'historique aux consignes qui l'ont
 * produite. Le texte est normalisé à la lecture (fins de ligne, blancs finaux), pour que la version ne
 * dépende ni du système ni de l'éditeur.
 */
public final class Consignes {

    private Consignes() {
    }

    /** Consigne du fichier {@code prompts/<nom>.md} ; une consigne absente empêche le démarrage. */
    public static String lire(String nom) {
        return Prompts.load("/prompts/" + nom + ".md");
    }
}
