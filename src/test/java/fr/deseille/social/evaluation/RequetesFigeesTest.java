package fr.deseille.social.evaluation;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RequetesFigeesTest {

    private final RequetesFigees figees = new RequetesFigees("essai", "79d5ae25",
            Map.of("a", List.of("q1"), "b", List.of("q2")));

    @Test
    void a_jour_pour_les_memes_consignes_et_toutes_les_questions() {
        assertThat(figees.perimeesPour("79d5ae25", List.of("a", "b"))).isNull();
    }

    @Test
    void perimees_si_les_consignes_ont_change_ou_si_une_question_manque() {
        assertThat(figees.perimeesPour("aaaaaaaa", List.of("a"))).isEqualTo("consignes 79d5ae25, en service aaaaaaaa");
        assertThat(figees.perimeesPour("79d5ae25", List.of("a", "c"))).isEqualTo("questions sans requêtes : [c]");
    }

    @Test
    void fichier_versionne_a_jour_pour_tout_le_jeu() throws Exception {
        RequetesFigees versionnees = RequetesFigees.lireRessource("/evaluation/requetes.yaml");
        List<String> ids = JeuEvaluation.lireRessource("/evaluation/questions.yaml").questions().stream()
                .map(JeuEvaluation.Question::id).toList();

        assertThat(versionnees.parQuestion().keySet()).as("une entrée par question du jeu").containsAll(ids);
    }
}
