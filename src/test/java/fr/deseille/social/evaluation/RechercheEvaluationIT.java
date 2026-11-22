package fr.deseille.social.evaluation;

import static org.assertj.core.api.Assertions.assertThat;

import fr.deseille.social.TestcontainersConfiguration;
import fr.deseille.social.consultation.ConsultationService;
import fr.deseille.social.consultation.VuesConsultation.ArticleDetail;
import fr.deseille.social.corpus.ArticleRepository;
import fr.deseille.social.evaluation.JeuEvaluation.ArticleAttendu;
import fr.deseille.social.evaluation.JeuEvaluation.Question;
import fr.deseille.social.profil.ProfilService;
import fr.deseille.social.profil.ProfilService.ContexteProfil;
import fr.deseille.social.questions.Assistant;
import fr.deseille.social.questions.CalculQuestion;
import fr.deseille.social.questions.AssistantClaude;
import fr.deseille.social.questions.MetriquesQuestions;
import fr.deseille.social.questions.QuestionService;
import fr.deseille.social.recherche.RechercheService;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import tools.jackson.databind.json.JsonMapper;

/**
 * Évaluation de la recherche seule, <b>sans appel au modèle</b> : les requêtes de reformulation
 * figées ({@code requetes.yaml}) sont rejouées sur les vraies sources, et l'on mesure pour chaque
 * question la part des articles attendus parmi les articles retenus pour la rédaction (8, comme pour
 * Claude). Gratuite et déterministe : elle tourne dans chaque PR (job « Évaluation de la recherche »).
 * <pre>
 * ./mvnw verify -Dit.test=RechercheEvaluationIT -Dtest=Aucun -Dsurefire.failIfNoSpecifiedTests=false -Devaluation.recherche=true
 * </pre>
 * Le résultat est comparé à la référence versionnée ({@code recherche-reference.json}) : le test
 * échoue si le nombre d'articles attendus retrouvés baisse, ou si une question perd un article qu'elle
 * retrouvait. Après une amélioration, ou pour accepter un compromis (pertes compensées par des gains),
 * réécrire la référence avec {@code -Devaluation.recherche.reference=ecrire} (refusé si le total
 * baisse ; {@code forcer} pour l'imposer). Rapport :
 * {@code target/evaluation/recherche-<horodatage>.md} (et {@code .json}).
 */
@SpringBootTest(properties = "spring.main.web-application-type=none")
@Import(TestcontainersConfiguration.class)
@EnabledIfSystemProperty(named = "evaluation.recherche", matches = "true")
class RechercheEvaluationIT {

    static final String REFERENCE = "src/test/resources/evaluation/recherche-reference.json";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** Seul {@link Assistant#articlesMax()} sert : aucune étape du modèle n'est appelée. */
    private static final Assistant SANS_MODELE = new Assistant() {
        @Override
        public List<String> reformuler(String question, ContexteProfil contexte) {
            throw new UnsupportedOperationException("requêtes figées");
        }

        @Override
        public Reponse rediger(String question, ContexteProfil contexte, List<ArticleDetail> articles,
                               CalculQuestion calcul) {
            throw new UnsupportedOperationException("pas de rédaction");
        }
    };

    /** Mesure d'une question : articles attendus retrouvés, et numéros des articles retenus. */
    record MesureQuestion(List<String> trouves, int attendus, List<String> retenus) {
    }

    /** Mesure d'ensemble, telle qu'écrite dans {@code recherche-reference.json}. */
    record Mesure(String origineRequetes, int articlesTrouves, int articlesAttendus,
                  Map<String, MesureQuestion> questions) {
    }

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
    private MetriquesQuestions metriques;
    @Autowired
    private ArticleRepository articleRepository;

    @Test
    void mesurer() throws Exception {
        List<Question> questions = JeuEvaluation.lireRessource("/evaluation/questions.yaml").questions();
        RequetesFigees requetes = RequetesFigees.lireRessource("/evaluation/requetes.yaml");
        assertThat(questions).extracting(Question::id)
                .as("questions sans requêtes figées : relancer une évaluation complète et recopier "
                        + "target/evaluation/requetes-*.yaml dans src/test/resources/evaluation/requetes.yaml")
                .allMatch(requetes.parQuestion()::containsKey);

        var ingestion = jobOperator.start(ingestionJob,
                new JobParametersBuilder().addLong("horodatage", System.nanoTime()).toJobParameters());
        assertThat(ingestion.getStatus()).as("ingestion des vraies sources").isEqualTo(BatchStatus.COMPLETED);
        assertThat(ArticlesAttendus.anomalies(questions, articleRepository))
                .as("articles attendus introuvables, mal numérotés ou non applicables").isEmpty();

        QuestionService service = new QuestionService(profilService, rechercheService, consultationService, SANS_MODELE,
                metriques, null);
        Map<String, MesureQuestion> parQuestion = new LinkedHashMap<>();
        for (Question q : questions) {
            ContexteProfil contexte = profilService.contexte(q.profil());
            List<ArticleDetail> retenus = service.articles(requetes.parQuestion().get(q.id()), q.profil().idcc(),
                    contexte.profil().dateReference());
            Set<String> ids = retenus.stream().map(ArticleDetail::sourceId).collect(Collectors.toSet());
            parQuestion.put(q.id(), new MesureQuestion(
                    q.articlesAttendus().stream().map(ArticleAttendu::sourceId).filter(ids::contains).toList(),
                    q.articlesAttendus().size(),
                    retenus.stream().map(a -> a.num() == null ? "(sans numéro)" : a.num()).toList()));
        }
        Mesure mesure = new Mesure(requetes.origine(),
                parQuestion.values().stream().mapToInt(m -> m.trouves().size()).sum(),
                parQuestion.values().stream().mapToInt(MesureQuestion::attendus).sum(), parQuestion);

        Path referenceFichier = Path.of(REFERENCE);
        Mesure reference = Files.exists(referenceFichier)
                ? JSON.readValue(Files.readString(referenceFichier, StandardCharsets.UTF_8), Mesure.class) : null;
        List<String> pertes = reference == null ? List.of() : pertes(questions, mesure, reference);

        String horodatage = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        Path rapport = Path.of("target", "evaluation", "recherche-" + horodatage + ".md");
        Files.createDirectories(rapport.getParent());
        Files.writeString(rapport, markdown(questions, mesure, reference, requetes.consignes(),
                AssistantClaude.CONSIGNES.reformulation()), StandardCharsets.UTF_8);
        Files.writeString(rapport.resolveSibling("recherche-" + horodatage + ".json"),
                JSON.writerWithDefaultPrettyPrinter().writeValueAsString(mesure), StandardCharsets.UTF_8);
        System.out.println("Rapport de la recherche : " + rapport.toAbsolutePath());

        List<String> inconnues = requetes.parQuestion().keySet().stream()
                .filter(id -> questions.stream().noneMatch(q -> q.id().equals(id))).toList();
        if (!inconnues.isEmpty()) {
            System.out.println("Requêtes figées pour des questions absentes du jeu (à retirer) : " + inconnues);
        }

        // Accepter un compromis (des pertes compensées par des gains) est un choix explicite, visible
        // dans le diff de la référence : « ecrire » le permet si le total ne baisse pas, « forcer » toujours
        String ecriture = System.getProperty("evaluation.recherche.reference", "");
        boolean totalEnBaisse = reference != null && mesure.articlesTrouves() < reference.articlesTrouves();
        if ("forcer".equals(ecriture) || "ecrire".equals(ecriture) && !totalEnBaisse) {
            Files.writeString(referenceFichier, JSON.writerWithDefaultPrettyPrinter().writeValueAsString(mesure) + "\n",
                    StandardCharsets.UTF_8);
            System.out.println("Référence de la recherche réécrite : " + referenceFichier.toAbsolutePath()
                    + (pertes.isEmpty() ? "" : " (pertes acceptées : " + pertes + ")"));
            return;
        }
        assertThat(reference).as("référence absente : la créer avec -Devaluation.recherche.reference=ecrire").isNotNull();
        assertThat(totalEnBaisse)
                .as("articles attendus retrouvés en baisse : %d contre %d (détail : %s)", mesure.articlesTrouves(),
                        reference.articlesTrouves(), rapport)
                .isFalse();
        assertThat(pertes)
                .as("articles attendus perdus par rapport à la référence (détail : %s) ; si le compromis est voulu, "
                        + "réécrire la référence avec -Devaluation.recherche.reference=ecrire", rapport)
                .isEmpty();
    }

    /** Articles attendus retrouvés dans la référence et plus maintenant, au format « question : numéro ». */
    static List<String> pertes(List<Question> questions, Mesure mesure, Mesure reference) {
        List<String> pertes = new ArrayList<>();
        for (Question q : questions) {
            MesureQuestion avant = reference.questions().get(q.id());
            MesureQuestion apres = mesure.questions().get(q.id());
            if (avant == null || apres == null) {
                continue;
            }
            avant.trouves().stream().filter(id -> !apres.trouves().contains(id))
                    .forEach(id -> pertes.add(q.id() + " : " + q.articlesAttendus().stream()
                            .filter(a -> a.sourceId().equals(id)).map(ArticleAttendu::num).findFirst().orElse(id)));
        }
        return pertes;
    }

    /** Rapport : total, comparaison à la référence, puis détail par question (gains et pertes). */
    static String markdown(List<Question> questions, Mesure mesure, Mesure reference, String consignesFigees,
                           String consignesActuelles) {
        StringBuilder md = new StringBuilder("# Évaluation de la recherche\n\n");
        md.append("- Requêtes figées : ").append(mesure.origineRequetes()).append('\n');
        // Les requêtes ne sont pas régénérées dans le CI (appel payant) : une consigne retouchée les périme
        md.append("- Consignes de reformulation des requêtes figées : ").append(consignesFigees)
                .append(consignesFigees.equals(consignesActuelles) ? " (actuelles)"
                        : " ; actuelles : " + consignesActuelles + " (**requêtes périmées** : la mesure ne reflète pas "
                        + "les consignes actuelles ; relancer une évaluation complète et recopier ses requêtes)")
                .append('\n');
        md.append(String.format("- Articles attendus retrouvés : **%d/%d** (%.0f %%)%n", mesure.articlesTrouves(),
                mesure.articlesAttendus(), 100.0 * mesure.articlesTrouves() / Math.max(1, mesure.articlesAttendus())));
        if (reference != null) {
            int ecart = mesure.articlesTrouves() - reference.articlesTrouves();
            md.append("- Référence : ").append(reference.articlesTrouves()).append('/').append(reference.articlesAttendus())
                    .append(ecart == 0 ? " (inchangé)" : ecart > 0 ? " (**+" + ecart + "** : mettre la référence à jour)"
                            : " (**" + ecart + "** : régression)").append('\n');
        }
        md.append("\n| Question | Retrouvés | Gagnés | Perdus | Articles retenus |\n|---|---|---|---|---|\n");
        Map<String, String> numeros = new LinkedHashMap<>();
        questions.forEach(q -> q.articlesAttendus().forEach(a -> numeros.put(a.sourceId(), a.num())));
        for (Question q : questions) {
            MesureQuestion m = mesure.questions().get(q.id());
            List<String> avant = reference == null || reference.questions().get(q.id()) == null ? m.trouves()
                    : reference.questions().get(q.id()).trouves();
            List<String> gagnes = new ArrayList<>(m.trouves());
            gagnes.removeAll(avant);
            List<String> perdus = new ArrayList<>(avant);
            perdus.removeAll(m.trouves());
            md.append("| ").append(q.id()).append(" | ").append(m.trouves().size()).append('/').append(m.attendus())
                    .append(" | ").append(liste(gagnes, numeros)).append(" | ").append(liste(perdus, numeros))
                    .append(" | ").append(String.join(", ", m.retenus()).replace("|", "\\|")).append(" |\n");
        }
        return md.toString();
    }

    private static String liste(List<String> sourceIds, Map<String, String> numeros) {
        return sourceIds.isEmpty() ? "" : sourceIds.stream().map(id -> numeros.getOrDefault(id, id))
                .collect(Collectors.joining(", ")).replace("|", "\\|");
    }
}
