package fr.deseille.social.questions;

import static org.assertj.core.api.Assertions.assertThat;

import fr.deseille.social.consultation.VuesConsultation.ArticleDetail;
import fr.deseille.social.consultation.VuesConsultation.TexteResume;
import fr.deseille.social.corpus.EtatJuridique;
import fr.deseille.social.corpus.TypeTexte;
import fr.deseille.social.questions.Assistant.Citation;
import fr.deseille.social.questions.Assistant.Passage;
import java.util.List;
import org.junit.jupiter.api.Test;

class VerificationCitationsTest {

    private static final TexteResume SYNTEC =
            new TexteResume("KALICONT1", TypeTexte.CONVENTION_COLLECTIVE, 1486, "Syntec", null);
    private static final ArticleDetail ARTICLE = new ArticleDetail("KALIARTI1", null, "4.5", EtatJuridique.VIGUEUR_ETEN,
            true, null, null, null, "<p>Pour une ancienneté égale ou supérieure à 2 ans :\n 1/3&nbsp;de mois.</p>",
            "<p>Hors faute grave.</p>", null, SYNTEC, List.of());

    private static List<Citation> nonVerifiees(Citation... citations) {
        return VerificationCitations.nonVerifiees(List.of(new Passage("…", List.of(citations))), List.of(ARTICLE));
    }

    @Test
    void extrait_retrouve_aux_espaces_et_entites_pres_y_compris_dans_le_nota() {
        assertThat(nonVerifiees(
                new Citation("KALIARTI1", "4.5", "égale ou supérieure à 2 ans : 1/3 de mois."),
                new Citation("KALIARTI1", "4.5", "  ancienneté   égale "),
                new Citation("KALIARTI1", "4.5", "Hors faute grave."))).isEmpty();
    }

    @Test
    void espace_fine_insecable_de_la_typographie_francaise() {
        ArticleDetail article = new ArticleDetail("LEGIARTI1", null, "L3123-29", EtatJuridique.VIGUEUR, true, null,
                null, "majoration de 10\u202F% ; puis de 25\u202F%", null, null, null, SYNTEC, List.of());

        assertThat(VerificationCitations.nonVerifiees(List.of(new Passage("…",
                List.of(new Citation("LEGIARTI1", "L3123-29", "majoration de 10 % ; puis de 25 %")))), List.of(article)))
                .isEmpty();
    }

    @Test
    void citation_non_verifiee_listee_une_seule_fois_meme_dans_deux_passages() {
        Citation inventee = new Citation("KALIARTI1", "4.5", "1/2 mois par année");

        assertThat(VerificationCitations.nonVerifiees(List.of(new Passage("a", List.of(inventee)),
                new Passage("b", List.of(inventee))), List.of(ARTICLE))).containsExactly(inventee);
    }

    @Test
    void extrait_absent_vide_ou_article_inconnu_non_verifie() {
        Citation inventee = new Citation("KALIARTI1", "4.5", "1/2 mois par année");
        Citation vide = new Citation("KALIARTI1", "4.5", " ");
        Citation autreArticle = new Citation("KALIARTI2", "4.6", "1/3 de mois");

        assertThat(nonVerifiees(inventee, vide, autreArticle, inventee)).containsExactly(inventee, vide, autreArticle);
    }
}
