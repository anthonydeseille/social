package fr.deseille.social.evaluation;

import static org.assertj.core.api.Assertions.assertThat;

import com.anthropic.core.ObjectMappers;
import com.fasterxml.jackson.databind.JsonNode;
import fr.deseille.social.evaluation.JeuEvaluation.Question;
import fr.deseille.social.evaluation.Juge.ErreurJugee;
import fr.deseille.social.evaluation.Juge.PointJuge;
import fr.deseille.social.evaluation.Juge.Verdict;
import fr.deseille.social.evaluation.RapportEvaluation.Resultat;
import fr.deseille.social.profil.ProfilService.ContexteProfil;
import fr.deseille.social.questions.Assistant.Citation;
import fr.deseille.social.questions.Assistant.Passage;
import fr.deseille.social.questions.QuestionService.ReponseQuestion;
import fr.deseille.social.questions.QuestionService.Source;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Le juge sans appel réseau : requête structurée, verdict désérialisé par le SDK, scores et rapport. */
class JugeClaudeTest {

    private static Question question() throws Exception {
        return JeuEvaluation.lireRessource("/evaluation/questions-test.yaml").questions().getFirst();
    }

    private static ReponseQuestion reponse(Question q) {
        return new ReponseQuestion(q.question(), new ContexteProfil(q.profil(), null, List.of(), true, List.of()),
                List.of("période essai"),
                List.of(new Passage("Votre période d'essai dure 4 mois", List.of(new Citation("KALIARTI000047513825", "3.4", "4 mois"))),
                        new Passage(", renouvelable une fois.", List.of())),
                List.of(new Source("KALIARTI000047513825", "3.4", "KALICONT", "Syntec")), false, List.of());
    }

    @Test
    void requete_structuree_avec_reference_points_numerotes_et_reponse_citee() throws Exception {
        Question q = question();
        var params = new JugeClaude("claude-opus-5-5", () -> null).parametres(q, reponse(q));
        JsonNode corps = ObjectMappers.jsonMapper().readTree(
                ObjectMappers.jsonMapper().writeValueAsString(params.rawParams()._body()));

        JsonNode schema = corps.path("output_config").path("format").path("schema");
        assertThat(schema.path("properties").has("points")).isTrue();
        assertThat(schema.path("properties").has("erreurs")).isTrue();
        assertThat(schema.path("properties").has("commentaire")).isTrue();
        assertThat(corps.path("system").toString()).contains("chiffres", "exacts");

        String message = corps.path("messages").get(0).path("content").toString();
        assertThat(message).contains("Réponse de référence : 4 mois.", "1. 4 mois", "1. 8 mois d'emblée",
                "Votre période d'essai dure 4 mois [3.4], renouvelable une fois.");
    }

    @Test
    void verdict_desserialise_par_le_sdk_et_scores() throws Exception {
        Verdict verdict = ObjectMappers.jsonMapper().readValue("""
                {"points": [{"point": "4 mois", "present": true, "justification": "Durée exacte."},
                            {"point": "accord écrit", "present": false, "justification": "Non mentionné."}],
                 "erreurs": [{"erreur": "8 mois d'emblée", "commise": true, "justification": "Annonce 8 mois."}],
                 "commentaire": ""}
                """, Verdict.class);

        assertThat(verdict.scoreQualite()).isEqualTo(0.5);
        assertThat(verdict.erreursCommises()).extracting(ErreurJugee::erreur).containsExactly("8 mois d'emblée");
    }

    @Test
    void rapport_avec_verdict_du_juge() throws Exception {
        Question q = question();
        Verdict verdict = new Verdict(List.of(new PointJuge("4 mois", true, "Durée exacte.")),
                List.of(new ErreurJugee("8 mois d'emblée", true, "Annonce 8 mois.")), "Omet l'accord écrit.");
        Resultat r = new Resultat(q, reponse(q), null, 5_000, 2_000, 1_000).avecVerdict(verdict, null);

        String md = RapportEvaluation.markdown(List.of(r), "claude-opus-5-5", false, 1_000, 500);
        assertThat(md).contains("Qualité (points clés présents, selon le juge) : 100 %",
                "Erreurs à éviter commises : 1", "Coût du juge : 0,01 $",
                "| q-validee | 1/2 | 1/2 | 100 % | 1 |", "- ✅ 4 mois : Durée exacte.",
                "- ⚠️ 8 mois d'emblée : Annonce 8 mois.", "Commentaire du juge** : Omet l'accord écrit.");
    }

    @Test
    void rapport_sans_juge_garde_les_cases_a_cocher() throws Exception {
        Question q = question();
        Resultat r = new Resultat(q, reponse(q), null, 0, 0, 0).avecVerdict(null, "AnthropicIoException : délai");

        String md = RapportEvaluation.markdown(List.of(r), "m", false);
        assertThat(md).contains("Qualité : juge non lancé", "Juge en erreur** : AnthropicIoException : délai", "- [ ] 4 mois");
    }
}
