package fr.deseille.social.evaluation;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.anthonydeseille.llmeval4j.history.EvalHistory;
import io.github.anthonydeseille.llmeval4j.regression.Change;
import io.github.anthonydeseille.llmeval4j.regression.Comparison;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ComparaisonEvaluationTest {

    private static final String HISTORIQUE = RapportEvaluation.ENTETE_HISTORIQUE + "\n"
            + "2026-10-30,aaa1111,référence,lots,claude-opus-5-5,r1,d1,j1,20,0,45,21,48,45,186,186,70,1,267900,53348,1.07,0.29,a.md\n"
            + "2026-10-31,bbb2222,\"PR #22, #24\",lots,claude-opus-5-5,r1,d1,j1,20,0,58,26,48,58,197,197,71,1,174631,50289,0.85,0.28,b.md\n"
            + "2026-11-01,ccc3333,essai local,direct,qwen2.5:7b,r2,d2,j1,20,0,40,19,48,30,50,60,40,4,0,0,0,0.30,c.md\n"
            + "2026-11-02,eee5555,\"validées seules, \"\"sous-ensemble\"\"\",direct,claude-opus-5-5,r1,d1,j1,5,5,90,9,10,90,40,40,95,0,0,0,0.30,0.07,e.md\n";

    private static Map<String, Object> synthese(Object recherche, Object cites, Object qualite, Object erreurs,
                                                long verifiees, long total) {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("commit", "ddd4444");
        s.put("mode", "lots");
        s.put("modele", "claude-opus-5-5");
        s.put("consignes_reformulation", "r1");
        s.put("consignes_redaction", "d1");
        s.put("consignes_juge", "j1");
        s.put("questions", 20);
        s.put("recherche_pct", recherche);
        s.put("articles_cites_pct", cites);
        s.put("citations_verifiees", verifiees);
        s.put("citations_total", total);
        s.put("qualite_pct", qualite);
        s.put("erreurs", erreurs);
        s.put("cout_questions_usd", 0.85);
        s.put("cout_juge_usd", 0.28);
        return s;
    }

    @Test
    void reference_derniere_ligne_du_meme_modele_avec_valeurs_entre_guillemets() {
        Comparison r = ComparaisonEvaluation.comparer(HISTORIQUE, synthese(58L, 58L, 71L, 1L, 197, 197));

        assertThat(r.reference()).as("même modèle et même nombre de questions : pas le sous-ensemble de 5")
                .containsEntry("commit", "bbb2222").containsEntry("changement", "PR #22, #24");
        assertThat(r.changes()).extracting(Change::metric).containsExactly("Recherche (articles attendus transmis)",
                "Articles attendus cités", "Qualité (juge)", "Erreurs à éviter commises", "Citations vérifiées");
        assertThat(r.regression()).isFalse();
    }

    @Test
    void baisse_dans_le_bruit_acceptee_baisse_nette_bloquante() {
        assertThat(ComparaisonEvaluation.comparer(HISTORIQUE, synthese(53L, 53L, 66L, 2L, 197, 197)).regression())
                .as("5 points et une erreur de plus : dans le bruit").isFalse();

        Comparison qualite = ComparaisonEvaluation.comparer(HISTORIQUE, synthese(58L, 58L, 65L, 1L, 197, 197));
        assertThat(qualite.regression()).isTrue();
        assertThat(qualite.changes()).filteredOn(Change::regression).extracting(Change::metric, Change::delta)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("Qualité (juge)", "-6 pts"));

        assertThat(ComparaisonEvaluation.comparer(HISTORIQUE, synthese(58L, 58L, 71L, 3L, 197, 197)).regression())
                .as("deux erreurs de plus").isTrue();
        assertThat(ComparaisonEvaluation.comparer(HISTORIQUE, synthese(58L, 58L, 71L, 1L, 190, 200)).regression())
                .as("citations vérifiées : 95 % contre 100 %").isTrue();
    }

    @Test
    void seuils_inclus() {
        // Exactement au seuil : 5 points, une erreur, 2 points de citations vérifiées (196/200 contre 197/197)
        Comparison r = ComparaisonEvaluation.comparer(HISTORIQUE, synthese(53L, 53L, 66L, 2L, 196, 200));

        assertThat(r.changes()).noneMatch(Change::regression);
        assertThat(ComparaisonEvaluation.comparer(HISTORIQUE, synthese(52L, 58L, 71L, 1L, 197, 197)).regression())
                .as("6 points de recherche").isTrue();
        assertThat(ComparaisonEvaluation.comparer(HISTORIQUE, synthese(58L, 58L, 71L, 1L, 195, 200)).regression())
                .as("2,5 points de citations vérifiées").isTrue();
    }

    @Test
    void guillemets_doubles_dans_une_valeur() {
        assertThat(EvalHistory.parse(HISTORIQUE).rows().getLast())
                .containsEntry("changement", "validées seules, \"sous-ensemble\"").containsEntry("questions", "5");
    }

    @Test
    void indicateur_absent_non_compare() {
        Comparison r = ComparaisonEvaluation.comparer(HISTORIQUE, synthese(58L, 58L, null, null, 197, 197));

        assertThat(r.changes()).extracting(Change::metric).doesNotContain("Qualité (juge)", "Erreurs à éviter commises");
    }

    @Test
    void sans_reference_du_meme_modele_ni_comparaison_ni_blocage() {
        Map<String, Object> s = synthese(10L, 10L, 10L, 9L, 1, 10);
        s.put("modele", "claude-sonnet-5-5");

        Comparison r = ComparaisonEvaluation.comparer(HISTORIQUE, s);

        assertThat(r.regression()).isFalse();
        assertThat(ComparaisonEvaluation.markdown(r, s)).contains("Aucune évaluation de l'historique avec ce modèle");

        Comparison parRepli = ComparaisonEvaluation.comparer(HISTORIQUE, s, "claude-opus-5-5");
        assertThat(parRepli.reference()).as("nouveau modèle comparé au modèle actuel").containsEntry("commit", "bbb2222");
        assertThat(parRepli.regression()).isTrue();
        assertThat(ComparaisonEvaluation.markdown(parRepli, s)).contains("Référence : le modèle actuel, claude-opus-5-5");

        s.put("modele", "claude-opus-5-5");
        s.put("questions", 12);
        assertThat(ComparaisonEvaluation.comparer(HISTORIQUE, s).reference()).as("autre nombre de questions").isNull();
    }

    @Test
    void commentaire_de_pr() {
        Map<String, Object> s = synthese(58L, 58L, 65L, 1L, 197, 197);

        String md = ComparaisonEvaluation.markdown(ComparaisonEvaluation.comparer(HISTORIQUE, s), s);

        assertThat(md).contains("Commit `ddd4444`", "Coût : 1,13 $", "du 2026-10-31 (`bbb2222`",
                "| Qualité (juge) | 71% | 65% | -6 pts | ❌ baisse nette |",
                "| Recherche (articles attendus transmis) | 58% | 58% | 0 pts | ✅ |",
                "**Baisse nette : la PR est bloquée.**");
    }

    @Test
    void historique_versionne_lisible() throws Exception {
        String csv = java.nio.file.Files.readString(java.nio.file.Path.of("docs/evaluations/historique.csv"));

        assertThat(EvalHistory.parse(csv).rows()).isNotEmpty()
                .allSatisfy(l -> assertThat(l.keySet()).containsExactly(RapportEvaluation.ENTETE_HISTORIQUE.split(",")))
                .allSatisfy(l -> assertThat(l.get("analyse")).endsWith(".md"));
    }
}
