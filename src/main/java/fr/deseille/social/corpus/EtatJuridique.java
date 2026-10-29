package fr.deseille.social.corpus;

/**
 * État juridique d'un texte, d'une section ou d'un article, tel que publié par la DILA
 * (bases LEGI et KALI). Les valeurs reprennent exactement celles des dumps SocialGouv.
 */
public enum EtatJuridique {

    VIGUEUR,
    VIGUEUR_ETEN,
    VIGUEUR_NON_ETEN,
    ABROGE,
    MODIFIE,
    PERIME,
    DENONCE,
    REMPLACE
}
