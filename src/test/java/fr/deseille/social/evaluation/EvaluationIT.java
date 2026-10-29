package fr.deseille.social.evaluation;

import static org.assertj.core.api.Assertions.assertThat;

import fr.deseille.social.TestcontainersConfiguration;
import fr.deseille.social.consultation.ConsultationService;
import fr.deseille.social.consultation.VuesConsultation.ArticleDetail;
import fr.deseille.social.evaluation.JeuEvaluation.Question;
import fr.deseille.social.evaluation.RapportEvaluation.Resultat;
import fr.deseille.social.profil.ProfilService;
import fr.deseille.social.profil.ProfilService.ContexteProfil;
import fr.deseille.social.questions.Assistant;
import fr.deseille.social.questions.AssistantClaude;
import fr.deseille.social.questions.ClaudeProperties;
import fr.deseille.social.questions.QuestionService;
import fr.deseille.social.questions.QuestionService.ReponseQuestion;
import fr.deseille.social.recherche.RechercheService;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * Lanceur du jeu d'évaluation sur les <b>vraies sources</b> (ingérées depuis GitHub, révisions
 * épinglées). Désactivé par défaut : il appelle Claude et coûte (environ 5 à 8 centimes par question).
 * <pre>
 * ./mvnw verify -Dit.test=EvaluationIT -Dtest=Aucun -Dsurefire.failIfNoSpecifiedTests=false -Devaluation=true
 * </pre>
 * Chaque réponse est notée par un juge (Claude, sortie structurée) au regard des points clés et des
 * erreurs à éviter, sauf {@code -Devaluation.juge=false}.
 * Options : {@code -Devaluation.simulee=true} (faux assistant, sans juge, gratuit, pour vérifier le circuit),
 * {@code -Devaluation.juge.modele=…} (modèle du juge, celui de l'application par défaut),
 * {@code -Devaluation.toutes=true} (y compris les questions non validées),
 * {@code -Devaluation.questions=id1,id2} (sélection), {@code -Devaluation.fichier=chemin}
 * (autre jeu que {@code src/test/resources/evaluation/questions.yaml}).
 * Rapport : {@code target/evaluation/rapport-<horodatage>.md}.
 */
@SpringBootTest(properties = "spring.main.web-application-type=none")
@Import(TestcontainersConfiguration.class)
@EnabledIfSystemProperty(named = "evaluation", matches = "true")
class EvaluationIT {

    @Autowired
    private JobOperator jobOperator;
    @Autowired
    private Job ingestionJob;
    @Autowired
    private ProfilService profilService;
    @Autowired
    private RechercheService rechercheService;
    @Autowired
    private ConsultationService consultationService;
    @Autowired
    private AssistantClaude assistantClaude;
    @Autowired
    private ClaudeProperties claudeProperties;
    @Autowired
    private fr.deseille.social.corpus.ArticleRepository articleRepository;

    @Test
    void evaluer() throws Exception {
        boolean simulee = Boolean.getBoolean("evaluation.simulee");
        List<Question> questions = selection(jeu().questions());
        assertThat(questions).as("aucune question à évaluer (validées seulement, sauf -Devaluation.toutes=true)").isNotEmpty();

        var ingestion = jobOperator.start(ingestionJob,
                new JobParametersBuilder().addLong("horodatage", System.nanoTime()).toJobParameters());
        assertThat(ingestion.getStatus()).as("ingestion des vraies sources").isEqualTo(BatchStatus.COMPLETED);
        verifierArticlesAttendus(questions);

        Assistant assistant = simulee ? new AssistantSimule() : assistantClaude;
        QuestionService service = new QuestionService(profilService, rechercheService, consultationService, assistant);
        JugeClaude juge = simulee || !Boolean.parseBoolean(System.getProperty("evaluation.juge", "true")) ? null
                : new JugeClaude(System.getProperty("evaluation.juge.modele", claudeProperties.modele()), claudeProperties);

        Tarif tarif = Tarif.pour(claudeProperties.modele(), "evaluation");
        Tarif tarifJuge = juge == null ? tarif
                : Tarif.pour(System.getProperty("evaluation.juge.modele", claudeProperties.modele()), "evaluation.juge");
        Path rapport = Path.of("target", "evaluation",
                "rapport-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) + ".md");
        Files.createDirectories(rapport.getParent());
        System.out.println("Rapport d'évaluation (mis à jour après chaque question) : " + rapport.toAbsolutePath());

        List<Resultat> resultats = new ArrayList<>();
        for (Question q : questions) {
            var avant = assistantClaude.consommation();
            long debut = System.currentTimeMillis();
            ReponseQuestion reponse = null;
            String erreur = null;
            try {
                reponse = service.repondre(q.question(), q.profil());
            } catch (RuntimeException e) {
                erreur = decrire(e);
            }
            var apres = assistantClaude.consommation();
            Resultat resultat = new Resultat(q, reponse, erreur, apres.jetonsEntree() - avant.jetonsEntree(),
                    apres.jetonsSortie() - avant.jetonsSortie(), System.currentTimeMillis() - debut);
            if (juge != null && reponse != null && !reponse.refus() && !reponse.passages().isEmpty()) {
                try {
                    resultat = resultat.avecVerdict(juge.juger(q, reponse), null);
                } catch (RuntimeException e) {
                    resultat = resultat.avecVerdict(null, decrire(e));
                }
            }
            resultats.add(resultat);
            // Réécrit après chaque question : une interruption ne perd pas ce qui a déjà été payé
            Files.writeString(rapport, RapportEvaluation.markdown(resultats, tarif, simulee, tarifJuge,
                    juge == null ? 0 : juge.jetonsEntree(), juge == null ? 0 : juge.jetonsSortie(), questions.size()),
                    StandardCharsets.UTF_8);
        }
    }

    private static String decrire(Throwable e) {
        return e.getClass().getSimpleName() + (e.getMessage() == null ? "" : " : " + e.getMessage());
    }

    /**
     * Tous les articles attendus doivent exister dans le corpus ingéré : sinon (révision épinglée
     * changée, identifiant erroné), arrêt avant le premier appel payant, avec la liste des absents.
     */
    private void verifierArticlesAttendus(List<Question> questions) {
        java.util.Set<String> presents = articleRepository.findBySourceIdIn(questions.stream()
                        .flatMap(q -> q.articlesAttendus().stream()).map(JeuEvaluation.ArticleAttendu::sourceId).toList())
                .stream().map(fr.deseille.social.corpus.Article::getSourceId).collect(java.util.stream.Collectors.toSet());
        List<String> absents = questions.stream()
                .flatMap(q -> q.articlesAttendus().stream()
                        .filter(a -> !presents.contains(a.sourceId()))
                        .map(a -> q.id() + " : " + a.num() + " (" + a.sourceId() + ")"))
                .toList();
        assertThat(absents).as("articles attendus absents du corpus ingéré").isEmpty();
    }

    private static JeuEvaluation jeu() throws Exception {
        String fichier = System.getProperty("evaluation.fichier");
        return fichier == null || fichier.isBlank()
                ? JeuEvaluation.lireRessource("/evaluation/questions.yaml")
                : JeuEvaluation.lire(Path.of(fichier));
    }

    private static List<Question> selection(List<Question> questions) {
        String ids = System.getProperty("evaluation.questions", "");
        boolean toutes = Boolean.getBoolean("evaluation.toutes");
        List<String> choisies = ids.isBlank() ? List.of() : Arrays.stream(ids.split(",")).map(String::strip).toList();
        return questions.stream()
                .filter(q -> toutes || q.valide())
                .filter(q -> choisies.isEmpty() || choisies.contains(q.id()))
                .toList();
    }

    /**
     * Faux assistant, sans appel payant : vérifie le circuit et donne une base de comparaison pour la
     * recherche (mots significatifs de la question combinés par « or », sans reformulation). Cite
     * tous les articles transmis.
     */
    static final class AssistantSimule implements Assistant {

        @Override
        public List<String> reformuler(String question, ContexteProfil contexte) {
            String mots = Arrays.stream(question.toLowerCase(java.util.Locale.ROOT).split("[^\\p{L}0-9]+"))
                    .filter(m -> m.length() >= 5)
                    .distinct()
                    .collect(java.util.stream.Collectors.joining(" or "));
            return List.of(mots.isBlank() ? question : mots);
        }

        @Override
        public Reponse rediger(String question, ContexteProfil contexte, List<ArticleDetail> articles) {
            return new Reponse(articles.stream()
                    .map(a -> new Passage("Simulé. ", List.of(new Citation(a.sourceId(), a.num(), debut(a))))).toList(),
                    false, false);
        }

        /** Début du texte de l'article : un extrait que la vérification des citations retrouve. */
        private static String debut(ArticleDetail a) {
            String texte = fr.deseille.social.questions.AssistantClaude.contenu(a);
            return texte.substring(0, Math.min(60, texte.length()));
        }
    }
}
