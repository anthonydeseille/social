package fr.deseille.social.questions;

import fr.deseille.social.consultation.VuesConsultation.ArticleDetail;
import fr.deseille.social.profil.ProfilService.ContexteProfil;
import java.util.List;

/**
 * Contrat des deux étapes confiées à un modèle de langage, indépendant du fournisseur :
 * {@code AssistantClaude} ou {@code AssistantOllama}, selon {@code droits.assistant.fournisseur} ;
 * les tests en utilisent de fausses.
 */
public interface Assistant {

    /**
     * Transforme la question d'un salarié en requêtes de recherche plein texte (mots-clés
     * juridiques, synonymes), de 1 à 4.
     */
    List<String> reformuler(String question, ContexteProfil contexte);

    /**
     * Rédige la réponse à partir des seuls articles fournis, chaque affirmation citant sa source.
     *
     * @param calcul calcul fait par le code pour la question ({@link CalculsQuestions}), que le modèle
     *               explique sans le refaire ; {@code null} pour une question sans calcul
     * @throws AssistantIndisponibleException si le service du modèle ne répond pas
     */
    Reponse rediger(String question, ContexteProfil contexte, List<ArticleDetail> articles, CalculQuestion calcul);

    /**
     * Articles transmis à la rédaction au plus : 8 par défaut, assez pour couvrir Code et convention
     * sans diluer ; moins pour un petit modèle, dont la lecture est lente.
     */
    default int articlesMax() {
        return 8;
    }

    /** Modèle utilisé, pour les rapports d'évaluation. */
    default String modele() {
        return "inconnu";
    }

    /**
     * Versions des consignes envoyées au modèle, par étape ({@link Consignes#version}) : inscrites
     * dans chaque réponse, les métriques et les rapports d'évaluation.
     */
    default VersionsConsignes consignes() {
        return VersionsConsignes.INCONNUES;
    }

    record VersionsConsignes(String reformulation, String redaction) {

        public static final VersionsConsignes INCONNUES = new VersionsConsignes("inconnue", "inconnue");
    }

    /** Modèle de la reformulation, s'il diffère de celui de la rédaction. */
    default String modeleReformulation() {
        return modele();
    }

    /** Consommation cumulée depuis le démarrage (mesure du coût réel, évaluation). */
    default Consommation consommation() {
        return new Consommation(0, 0, 0, 0);
    }

    /** @param coutUsd coût cumulé en dollars, au prix de chaque appel ({@code NaN} si un modèle est inconnu) */
    record Consommation(long appels, long jetonsEntree, long jetonsSortie, double coutUsd) {
    }

    /**
     * @param passages  réponse découpée en passages, chacun avec ses citations (vides pour un
     *                  passage de liaison)
     * @param refus     le modèle a refusé de répondre ; les passages sont alors vides
     * @param tronquee  la réponse a été interrompue avant sa fin (limite de jetons)
     */
    record Reponse(List<Passage> passages, boolean refus, boolean tronquee) {
    }

    record Passage(String texte, List<Citation> citations) {
    }

    /**
     * @param articleSourceId article cité (identifiant du corpus)
     * @param num             numéro de l'article (ex. L1225-4, 4.5)
     * @param texteCite       extrait exact de l'article sur lequel s'appuie le passage
     */
    record Citation(String articleSourceId, String num, String texteCite) {
    }
}
