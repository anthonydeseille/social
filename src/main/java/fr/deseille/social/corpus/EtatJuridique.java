package fr.deseille.social.corpus;

/**
 * État juridique d'un texte, d'une section ou d'un article, tel que publié par la DILA
 * (bases LEGI et KALI). Les valeurs reprennent exactement celles des dumps SocialGouv ; un
 * état absent de cette liste fait échouer l'ingestion (voir {@code MappeurNoeud#etat}).
 */
public enum EtatJuridique {

    VIGUEUR,
    /** En vigueur à une date future. */
    VIGUEUR_DIFF,
    VIGUEUR_ETEN,
    VIGUEUR_NON_ETEN,
    ABROGE,
    /** Abrogé à une date future (encore applicable jusque-là). */
    ABROGE_DIFF,
    MODIFIE,
    MODIFIE_MORT_NE,
    MORT_NE,
    ANNULE,
    DISJOINT,
    TRANSFERE,
    SUBSTITUE,
    PERIME,
    DENONCE,
    REMPLACE;

    /**
     * Indique si une disposition dans cet état peut s'appliquer, sous réserve de ses dates de
     * début et de fin : en vigueur (étendue ou non), en vigueur différée (à partir de sa date de
     * début) ou abrogée de façon différée (jusqu'à sa date de fin). Voir
     * {@link Article#estApplicableLe}.
     */
    public boolean peutEtreApplicable() {
        return switch (this) {
            case VIGUEUR, VIGUEUR_DIFF, VIGUEUR_ETEN, VIGUEUR_NON_ETEN, ABROGE_DIFF -> true;
            default -> false;
        };
    }
}
