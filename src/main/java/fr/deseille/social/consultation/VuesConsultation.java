package fr.deseille.social.consultation;

import fr.deseille.social.corpus.Article;
import fr.deseille.social.corpus.EtatJuridique;
import fr.deseille.social.corpus.JournalChangement;
import fr.deseille.social.corpus.Section;
import fr.deseille.social.corpus.Texte;
import fr.deseille.social.corpus.TypeChangement;
import fr.deseille.social.corpus.TypeTexte;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * Représentations JSON exposées par l'API de consultation. Les entités JPA ne sont jamais
 * sérialisées directement (relations paresseuses, données brutes internes).
 */
public final class VuesConsultation {

    private VuesConsultation() {
    }

    public record TexteResume(String sourceId, TypeTexte type, Integer idcc, String titre, EtatJuridique etat) {

        public static TexteResume de(Texte t) {
            return new TexteResume(t.getSourceId(), t.getType(), t.getIdcc(), t.getTitre(), t.getEtat());
        }
    }

    public record SectionResume(String sourceId, String titre, EtatJuridique etat) {

        public static SectionResume de(Section s) {
            return new SectionResume(s.getSourceId(), s.getTitre(), s.getEtat());
        }
    }

    public record ArticleResume(String sourceId, String num, EtatJuridique etat, boolean applicable,
                                LocalDate dateDebut, LocalDate dateFin) {

        public static ArticleResume de(Article a) {
            return new ArticleResume(a.getSourceId(), a.getNum(), a.getEtat(), VuesConsultation.applicable(a),
                    a.getDateDebut(), a.getDateFin());
        }
    }

    /** Élément d'un niveau du sommaire : une section (à déplier) ou un article. */
    public record ElementSommaire(Nature nature, String sourceId, String libelle, EtatJuridique etat,
                                  Integer intOrdre) {

        public enum Nature { SECTION, ARTICLE }

        public static ElementSommaire de(Section s) {
            return new ElementSommaire(Nature.SECTION, s.getSourceId(), s.getTitre(), s.getEtat(), s.getIntOrdre());
        }

        public static ElementSommaire de(Article a) {
            return new ElementSommaire(Nature.ARTICLE, a.getSourceId(), a.getNum(), a.getEtat(), a.getIntOrdre());
        }
    }

    /**
     * Un niveau du sommaire d'un texte.
     *
     * @param section   section dépliée, null pour la racine du texte
     * @param filAriane sections parentes, de la racine vers {@code section} incluse
     */
    public record Sommaire(TexteResume texte, SectionResume section, List<SectionResume> filAriane,
                           List<ElementSommaire> elements) {
    }

    public record ArticleDetail(String sourceId, String cid, String num, EtatJuridique etat, boolean applicable,
                                LocalDate dateDebut, LocalDate dateFin, String contenuTexte, String contenuHtml,
                                String nota, Instant disparuLe, TexteResume texte, List<SectionResume> filAriane) {
    }

    public record EvenementJournal(String articleSourceId, TypeChangement type, EtatJuridique etatAvant,
                                   EtatJuridique etatApres, Instant detecteLe, String revisionSource) {

        public static EvenementJournal de(JournalChangement j) {
            return new EvenementJournal(j.getArticleSourceId(), j.getTypeChangement(), j.getEtatAvant(),
                    j.getEtatApres(), j.getDetecteLe(), j.getRevisionSource());
        }
    }

    /**
     * @param versions   toutes les versions de l'article (même cid), applicables d'abord
     * @param evenements journal des changements de ces versions, du plus ancien au plus récent
     */
    public record Historique(String cid, List<ArticleResume> versions, List<EvenementJournal> evenements) {
    }

    /** Un article est applicable si son état l'est et qu'il n'a pas disparu du dump source. */
    static boolean applicable(Article a) {
        return a.getEtat() != null && a.getEtat().estApplicable() && a.getDisparuLe() == null;
    }
}
