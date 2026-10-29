package fr.deseille.social.ingestion;

import fr.deseille.social.corpus.EtatJuridique;
import fr.deseille.social.corpus.TypeTexte;
import java.time.LocalDate;

/**
 * Commande d'écriture normalisée produite par {@link NoeudCorpusProcessor} et appliquée par
 * {@link CorpusWriter}. La classification des changements d'article est faite par le writer,
 * au moment de l'écriture, pour ne pas dépendre du découpage en chunks.
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

    record Article(String sourceId, String cid, String sectionSourceId, String num,
                   EtatJuridique etat, LocalDate dateDebut, LocalDate dateFin, Integer intOrdre,
                   String contenuTexte, String contenuHtml, String nota, String contenuHash,
                   String metaHash, String donneesBrutes, String ref) implements CommandeEcriture {
    }
}
