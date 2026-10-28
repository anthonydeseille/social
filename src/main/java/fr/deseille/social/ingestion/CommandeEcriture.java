package fr.deseille.social.ingestion;

import fr.deseille.social.corpus.EtatJuridique;
import fr.deseille.social.corpus.TypeChangement;
import fr.deseille.social.corpus.TypeTexte;
import java.time.LocalDate;

/**
 * Commande d'écriture normalisée produite par {@link NoeudCorpusProcessor} et appliquée par
 * {@link CorpusWriter}. Pour les articles, la classification du changement est déjà décidée.
 */
public sealed interface CommandeEcriture
        permits CommandeEcriture.Texte, CommandeEcriture.Section, CommandeEcriture.Article {

    record Texte(String sourceId, TypeTexte type, Integer idcc, String titre,
                 EtatJuridique etat, LocalDate dateDebut, LocalDate dateFin) implements CommandeEcriture {
    }

    record Section(String sourceId, String cid, String parentSourceId, String titre,
                   EtatJuridique etat, Integer intOrdre, LocalDate dateDebut, LocalDate dateFin)
            implements CommandeEcriture {
    }

    /**
     * @param changement type de changement détecté, ou {@code null} si l'article est inchangé
     *                   (upsert sans écriture — seul le marquage « vu » est effectué)
     */
    record Article(String sourceId, String cid, String sectionSourceId, String num,
                   EtatJuridique etat, LocalDate dateDebut, LocalDate dateFin, Integer intOrdre,
                   String contenuTexte, String contenuHtml, String nota, String contenuHash,
                   String donneesBrutes, TypeChangement changement,
                   EtatJuridique etatAvant, String hashAvant, String ref) implements CommandeEcriture {
    }
}
