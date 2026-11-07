package fr.deseille.social.evaluation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import fr.deseille.social.evaluation.JeuEvaluation.Question;
import fr.deseille.social.evaluation.RapportEvaluation.Resultat;
import fr.deseille.social.profil.ProfilSalarie;
import fr.deseille.social.profil.ProfilService.ContexteProfil;
import fr.deseille.social.questions.Assistant.Citation;
import fr.deseille.social.questions.Assistant.Passage;
import fr.deseille.social.questions.Assistant.VersionsConsignes;
import fr.deseille.social.questions.QuestionService.ReponseQuestion;
import fr.deseille.social.questions.QuestionService.Source;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class JeuEvaluationTest {

    @Test
    void lecture_du_format_avec_dates_et_profil() throws Exception {
        JeuEvaluation jeu = JeuEvaluation.lireRessource("/evaluation/questions-test.yaml");

        assertThat(jeu.questions()).extracting(Question::id).containsExactly("q-validee", "q-non-validee");
        Question q = jeu.questions().getFirst();
        assertThat(q.valide()).isTrue();
        assertThat(q.profil().idcc()).isEqualTo(1486);
        assertThat(q.profil().statut()).isEqualTo(ProfilSalarie.Statut.CADRE);
        assertThat(q.profil().dateEntree()).isEqualTo(LocalDate.of(2026, 10, 1));
        assertThat(q.profil().situations()).containsExactly(ProfilSalarie.Situation.GROSSESSE);
        assertThat(q.articlesAttendus()).extracting(JeuEvaluation.ArticleAttendu::num).containsExactly("3.4", "L1221-19");
        assertThat(jeu.questions().get(1).valide()).isFalse();
    }

    @Test
    void scores_de_recherche_et_de_citations_et_rapport() throws Exception {
        Question q = JeuEvaluation.lireRessource("/evaluation/questions-test.yaml").questions().getFirst();
        ContexteProfil contexte = new ContexteProfil(q.profil(), null, List.of(), true, List.of());
        // Les deux attendus sont transmis, un seul est cité
        ReponseQuestion reponse = new ReponseQuestion(q.question(), contexte, List.of("période essai"),
                List.of(new Passage("4 mois.", List.of(new Citation("KALIARTI000047513825", "3.4", "4 mois")))),
                List.of(new Source("KALIARTI000047513825", "3.4", "KALICONT", "Syntec"),
                        new Source("LEGIARTI000019071113", "L1221-19", "LEGITEXT", "Code")),
                false, List.of(), List.of(), VersionsConsignes.INCONNUES);
        Resultat r = new Resultat(q, reponse, null, 5_000, 2_000, 1_500);

        assertThat(r.scoreRecherche()).isEqualTo(1.0);
        assertThat(r.scoreCitations()).isEqualTo(0.5);
        assertThat(Tarif.pour("claude-opus-5-5").cout(5_000, 2_000)).isEqualTo(0.06);

        String md = RapportEvaluation.markdown(List.of(r), "claude-opus-5-5", false);
        assertThat(md).contains("| q-validee | 2/2 | 1/2 |", "Citations (articles attendus cités) : 50 %",
                "Attendus trouvés mais non cités** : L1221-19 (CODE)", "- [ ] 4 mois", "0,06 $");
    }

    @Test
    void synthese_et_ligne_d_historique_aux_colonnes_de_historique_csv() throws Exception {
        Question q = JeuEvaluation.lireRessource("/evaluation/questions-test.yaml").questions().getFirst();
        ContexteProfil contexte = new ContexteProfil(q.profil(), null, List.of(), true, List.of());
        ReponseQuestion reponse = new ReponseQuestion(q.question(), contexte, List.of("période essai"),
                List.of(new Passage("4 mois.", List.of(new Citation("KALIARTI000047513825", "3.4", "4 mois")))),
                List.of(new Source("KALIARTI000047513825", "3.4", "KALICONT", "Syntec")), false, List.of(), List.of(),
                new VersionsConsignes("aaaa1111", "bbbb2222"));
        Resultat r = new Resultat(q, reponse, null, 5_000, 2_000, 1_500);

        Map<String, Object> synthese = RapportEvaluation.synthese(List.of(r), "2026-10-31", "abc1234", "lots",
                Tarif.pour("claude-opus-5-5").lots(), Tarif.pour("claude-opus-5-5").lots(), 1_000, 500);
        String ligne = RapportEvaluation.ligneHistorique(synthese);

        assertThat(synthese.keySet()).containsExactly(RapportEvaluation.ENTETE_HISTORIQUE.split(","));
        assertThat(ligne).isEqualTo("2026-10-31,abc1234,,lots,claude-opus-5-5,aaaa1111,bbbb2222,,1,1,50,1,2,50,1,1,,,5000,2000,0.03,0.01,");
        // L'en-tête est celui du fichier versionné : une colonne ajoutée d'un côté doit l'être de l'autre
        assertThat(java.nio.file.Files.readAllLines(java.nio.file.Path.of("docs/evaluations/historique.csv")).getFirst())
                .isEqualTo(RapportEvaluation.ENTETE_HISTORIQUE);
    }

    @Test
    void requetes_figees_relues_telles_qu_ecrites() {
        RequetesFigees figees = new RequetesFigees("claude, essai", "aaaa1111", new java.util.LinkedHashMap<>(Map.of(
                "q1", List.of("période essai", "délai \"prévenance\"", "sur\ndeux lignes"))));

        RequetesFigees relues = RequetesFigees.lire(figees.yaml());

        assertThat(relues).isEqualTo(figees);
        assertThat(RequetesFigees.lire("origine: x\nrequetes:\n  q1: [a]\n").consignes())
                .as("fichier antérieur au versionnement des consignes").isEqualTo("inconnue");
        assertThatThrownBy(() -> RequetesFigees.lire("origine: x\nrequetes:\n  q1: []\n"))
                .hasMessageContaining("q1");
    }

    @Test
    void question_sans_article_attendu_exclue_des_moyennes() throws Exception {
        List<Question> questions = JeuEvaluation.lireRessource("/evaluation/questions-test.yaml").questions();
        Question avec = questions.getFirst();
        Question sans = new Question("sans-article", "t", true, "q ?", avec.profil(), "r", List.of(), List.of(), List.of());
        ContexteProfil contexte = new ContexteProfil(avec.profil(), null, List.of(), true, List.of());
        ReponseQuestion vide = new ReponseQuestion("q", contexte, List.of(), List.of(), List.of(), false, List.of(), List.of(),
                VersionsConsignes.INCONNUES);

        String md = RapportEvaluation.markdown(List.of(new Resultat(avec, vide, null, 0, 0, 0),
                new Resultat(sans, vide, null, 0, 0, 0)), "claude-opus-5-5", false);

        assertThat(md).contains("Recherche (articles attendus transmis) : 0 % (sur 1 question(s))",
                "| sans-article | n/a | n/a |");
    }

    @Test
    void tarif_inconnu_signale_tarif_force_utilise_rapport_partiel() throws Exception {
        Question q = JeuEvaluation.lireRessource("/evaluation/questions-test.yaml").questions().getFirst();
        Resultat r = new Resultat(q, null, "RateLimitException", 1_000, 1_000, 0);

        String inconnu = RapportEvaluation.markdown(List.of(r), Tarif.pour("modele-futur"), false,
                Tarif.pour("modele-futur"), 0, 0, 3);
        assertThat(inconnu).contains("# Rapport d'évaluation (partiel)", "Questions : 1/3", "tarif inconnu", "coût : n/a");

        System.setProperty("test.tarif.prix.entree", "1");
        System.setProperty("test.tarif.prix.sortie", "2");
        try {
            assertThat(Tarif.pour("modele-futur", "test.tarif").cout(1_000_000, 1_000_000)).isEqualTo(3.0);
        } finally {
            System.clearProperty("test.tarif.prix.entree");
            System.clearProperty("test.tarif.prix.sortie");
        }
    }

    @Test
    void fichier_invalide_message_qui_situe_l_erreur() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> JeuEvaluation.lire("""
                version: 1
                questions:
                  - {id: a, question: "Q ?", reponse_attendue: "R"}
                  - {question: "Sans id ?", reponse_attendue: "R"}
                """)).hasMessageContaining("question n° 2 : champ « id » manquant");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> JeuEvaluation.lire("""
                version: 1
                questions:
                  - {id: a, question: "Q ?", reponse_attendue: "R"}
                  - {id: a, question: "Q2 ?", reponse_attendue: "R"}
                """)).hasMessageContaining("identifiant « a » en double");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> JeuEvaluation.lire("""
                version: 1
                questions:
                  - {id: a, question: "Q ?", reponse_attendue: "R", articles_attendus: [{num: L1}]}
                """)).hasMessageContaining("question n° 1 (a), article attendu n° 1 : champ « sourceId » manquant");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> JeuEvaluation.lire("""
                version: 1
                questions:
                  - {id: a, question: "Q ?", reponse_attendue: "R", profil: {statut: STAGIAIRE}}
                """)).hasMessageContaining("question n° 1 (a) : profil invalide");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> JeuEvaluation.lire("questions: []"))
                .hasMessageContaining("version");
    }

    @Test
    void base_simulee_mots_significatifs_combines() {
        var simule = new EvaluationIT.AssistantSimule();
        assertThat(simule.reformuler("Combien dure ma période d'essai de cadre ?", null))
                .containsExactly("combien or période or essai or cadre");
    }
}
