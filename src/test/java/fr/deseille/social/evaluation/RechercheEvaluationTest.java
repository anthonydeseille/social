package fr.deseille.social.evaluation;

import static org.assertj.core.api.Assertions.assertThat;

import fr.deseille.social.evaluation.JeuEvaluation.ArticleAttendu;
import fr.deseille.social.evaluation.JeuEvaluation.Question;
import fr.deseille.social.evaluation.RechercheEvaluationIT.Mesure;
import fr.deseille.social.evaluation.RechercheEvaluationIT.MesureQuestion;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Comparaison d'une mesure de la recherche à sa référence, sans Spring ni Docker. */
class RechercheEvaluationTest {

    private static final Question ESSAI = question("essai", new ArticleAttendu("L1221-19", "CODE", "LEGIARTI1"),
            new ArticleAttendu("3.4", "SYNTEC", "KALIARTI1"));
    private static final Question PREAVIS = question("preavis", new ArticleAttendu("L1234-1", "CODE", "LEGIARTI2"),
            new ArticleAttendu("4.2", "SYNTEC", "KALIARTI2"));

    private static Question question(String id, ArticleAttendu... attendus) {
        return new Question(id, "t", true, "q ?", null, "r", List.of(), List.of(attendus), List.of());
    }

    private static Mesure mesure(List<String> essai, List<String> preavis) {
        Map<String, MesureQuestion> questions = new LinkedHashMap<>();
        questions.put("essai", new MesureQuestion(essai, 2, List.of()));
        questions.put("preavis", new MesureQuestion(preavis, 2, List.of()));
        return new Mesure("requêtes", essai.size() + preavis.size(), 4, questions);
    }

    /** Cas de la PR #24 : des gains d'un côté, des pertes de l'autre, total inchangé. */
    @Test
    void perte_detectee_meme_si_le_total_est_inchange() {
        Mesure reference = mesure(List.of("KALIARTI1"), List.of("KALIARTI2"));
        Mesure apres = mesure(List.of("KALIARTI1", "LEGIARTI1"), List.of());

        assertThat(apres.articlesTrouves()).isEqualTo(reference.articlesTrouves());
        assertThat(RechercheEvaluationIT.pertes(List.of(ESSAI, PREAVIS), apres, reference))
                .containsExactly("preavis : 4.2");
    }

    @Test
    void aucune_perte_quand_seuls_des_articles_sont_gagnes() {
        Mesure reference = mesure(List.of("KALIARTI1"), List.of());
        Mesure apres = mesure(List.of("KALIARTI1", "LEGIARTI1"), List.of("LEGIARTI2"));

        assertThat(RechercheEvaluationIT.pertes(List.of(ESSAI, PREAVIS), apres, reference)).isEmpty();
    }

    @Test
    void rapport_gains_pertes_et_ecart_a_la_reference() {
        Mesure reference = mesure(List.of("KALIARTI1"), List.of("KALIARTI2"));
        Mesure apres = mesure(List.of("KALIARTI1", "LEGIARTI1"), List.of());

        String md = RechercheEvaluationIT.markdown(List.of(ESSAI, PREAVIS), apres, reference);

        assertThat(md).contains("Articles attendus retrouvés : **2/4**", "Référence : 2/4 (inchangé)",
                "| essai | 2/2 | L1221-19 |  |", "| preavis | 0/2 |  | 4.2 |");
    }
}
