package fr.deseille.social.corpus;

/**
 * Type de changement consigné dans le journal lors d'une ingestion.
 */
public enum TypeChangement {

    /** Article jamais vu auparavant (nouvel identifiant, cid inconnu). */
    CREATION,

    /** Nouvelle version d'un article existant (même cid, nouvel identifiant de version). */
    NOUVELLE_VERSION,

    /** Contenu modifié pour un même identifiant de version (correction du dump). */
    MODIFICATION,

    /** État juridique modifié pour un même identifiant de version. */
    CHANGEMENT_ETAT,

    /** Métadonnées modifiées (section, ordre, dates, cid) sans changement de contenu ni d'état. */
    CHANGEMENT_METADONNEES,

    /** Article présent en base mais absent du dump courant (consigné une seule fois). */
    DISPARITION,

    /** Article précédemment disparu, de nouveau présent dans le dump. */
    REAPPARITION
}
