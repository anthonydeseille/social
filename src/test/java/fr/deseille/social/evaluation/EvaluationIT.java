package fr.deseille.social.evaluation;

import static org.assertj.core.api.Assertions.assertThat;

import fr.deseille.social.TestcontainersConfiguration;
import fr.deseille.social.consultation.ConsultationService;
import fr.deseille.social.consultation.VuesConsultation.ArticleDetail;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.models.messages.MessageCreateParams;
import fr.deseille.social.corpus.ArticleRepository;
import fr.deseille.social.evaluation.JeuEvaluation.Question;
import fr.deseille.social.evaluation.RapportEvaluation.Resultat;
import fr.deseille.social.profil.ProfilService;
import fr.deseille.social.profil.ProfilService.ContexteProfil;
import fr.deseille.social.questions.Assistant;
import fr.deseille.social.questions.AssistantClaude;
import fr.deseille.social.questions.AssistantOllama;
import fr.deseille.social.questions.ClaudeProperties;
import fr.deseille.social.questions.MetriquesQuestions;
import fr.deseille.social.questions.QuestionService;
import fr.deseille.social.questions.QuestionService.ReponseQuestion;
import fr.deseille.social.recherche.RechercheService;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
 * {@code -Devaluation.lots=true} (API Batch : moitié prix, asynchrone, quelques minutes à quelques
 * heures ; {@code -Devaluation.lots.heures=2} pour l'attente maximale),
 * {@code -Devaluation.toutes=true} (y compris les questions non validées),
 * {@code -Devaluation.questions=id1,id2} (sélection), {@code -Devaluation.fichier=chemin}
 * (autre jeu que {@code src/test/resources/evaluation/questions.yaml}).
 * Fournisseur : celui de la configuration ; {@code -Ddroits.assistant.fournisseur=ollama} évalue le
 * modèle local (Ollama démarré, modèle téléchargé), le juge restant Claude, pour comparer les deux
 * sur le même jeu. Le mode lots ne concerne que Claude.
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
    private Assistant assistantConfigure;
    @Autowired
    private ClaudeProperties claudeProperties;
    @Autowired
    private MetriquesQuestions metriques;
    @Autowired
    private ArticleRepository articleRepository;

    @Test
    void evaluer() throws Exception {
        boolean simulee = Boolean.getBoolean("evaluation.simulee");
        List<Question> questions = selection(jeu().questions());
        assertThat(questions).as("aucune question à évaluer (validées seulement, sauf -Devaluation.toutes=true)").isNotEmpty();

        var ingestion = jobOperator.start(ingestionJob,
                new JobParametersBuilder().addLong("horodatage", System.nanoTime()).toJobParameters());
        assertThat(ingestion.getStatus()).as("ingestion des vraies sources").isEqualTo(BatchStatus.COMPLETED);
        verifierArticlesAttendus(questions);

        Assistant assistant = simulee ? new AssistantSimule() : assistantConfigure;
        QuestionService service = new QuestionService(profilService, rechercheService, consultationService, assistant,
                metriques);
        JugeClaude juge = simulee || !Boolean.parseBoolean(System.getProperty("evaluation.juge", "true")) ? null
                : new JugeClaude(System.getProperty("evaluation.juge.modele", claudeProperties.modele()), claudeProperties);

        Tarif tarif = assistant instanceof AssistantOllama ? Tarif.local(assistant.modele())
                : Tarif.pour(claudeProperties.modele(), "evaluation");
        Tarif tarifJuge = juge == null ? tarif
                : Tarif.pour(System.getProperty("evaluation.juge.modele", claudeProperties.modele()), "evaluation.juge");
        Path rapport = Path.of("target", "evaluation",
                "rapport-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) + ".md");
        Files.createDirectories(rapport.getParent());
        System.out.println("Rapport d'évaluation (mis à jour après chaque question) : " + rapport.toAbsolutePath());

        if (Boolean.getBoolean("evaluation.lots") && !simulee) {
            assertThat(assistant).as("le mode lots passe par l'API Batch d'Anthropic : fournisseur claude seulement")
                    .isInstanceOf(AssistantClaude.class);
            evaluerParLots(questions, service, (AssistantClaude) assistant, juge, tarif, tarifJuge, rapport);
            return;
        }

        List<Resultat> resultats = new ArrayList<>();
        for (Question q : questions) {
            var avant = assistant.consommation();
            long debut = System.currentTimeMillis();
            ReponseQuestion reponse = null;
            String erreur = null;
            try {
                reponse = service.repondre(q.question(), q.profil());
            } catch (RuntimeException e) {
                erreur = decrire(e);
            }
            var apres = assistant.consommation();
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

    /**
     * Évaluation par l'API Batch (moitié prix, asynchrone) : trois lots successifs, car chaque étape
     * dépend de la précédente — reformulations, puis recherche locale et rédactions, puis juge. Mêmes
     * étapes et mêmes contrôles que {@link QuestionService#repondre}, appels regroupés.
     */
    private void evaluerParLots(List<Question> questions, QuestionService service, AssistantClaude assistantClaude,
                                JugeClaude juge, Tarif tarif, Tarif tarifJuge, Path rapport) throws Exception {
        LotClaude lot = new LotClaude(() -> AnthropicOkHttpClient.builder().fromEnv()
                .timeout(claudeProperties.delai()).maxRetries(claudeProperties.tentatives()).build(),
                Duration.ofHours(Long.getLong("evaluation.lots.heures", 2)));
        Map<String, ContexteProfil> contextes = new LinkedHashMap<>();
        Map<String, long[]> jetons = new HashMap<>();
        for (Question q : questions) {
            contextes.put(q.id(), profilService.contexte(q.profil()));
            jetons.put(q.id(), new long[2]);
        }

        // 1. Reformulations
        Map<String, MessageCreateParams> reformulations = new LinkedHashMap<>();
        for (Question q : questions) {
            reformulations.put(q.id(), assistantClaude.parametresReformulation(q.question(), contextes.get(q.id())).rawParams());
        }
        Map<String, LotClaude.Resultat> r1 = lot.executer("reformulations", reformulations);
        Map<String, List<String>> requetes = new HashMap<>();
        for (Question q : questions) {
            LotClaude.Resultat r = r1.get(q.id());
            if (r == null || r.message() == null) {
                // Comme en mode normal, repli sur la question brute ; signalé pour lire les scores de recherche
                System.out.println("Reformulation en échec pour " + q.id() + " ("
                        + (r == null ? "résultat absent du lot" : r.erreur()) + ") : recherche sur la question brute");
            }
            requetes.put(q.id(), r == null || r.message() == null ? List.of(q.question())
                    : AssistantClaude.requetesDepuis(r.message(), q.question()));
            compter(jetons.get(q.id()), r);
        }

        // 2. Recherche locale, puis rédactions
        Map<String, List<ArticleDetail>> articles = new HashMap<>();
        Map<String, MessageCreateParams> redactions = new LinkedHashMap<>();
        Map<String, ReponseQuestion> reponses = new HashMap<>();
        Map<String, String> erreurs = new HashMap<>();
        for (Question q : questions) {
            ContexteProfil contexte = contextes.get(q.id());
            List<ArticleDetail> trouves = service.articles(requetes.get(q.id()), q.profil().idcc(),
                    contexte.profil().dateReference());
            articles.put(q.id(), trouves);
            if (trouves.isEmpty()) {
                reponses.put(q.id(), service.sansArticle(q.question(), contexte, requetes.get(q.id())));
            } else {
                redactions.put(q.id(), assistantClaude.parametresRedaction(q.question(), contexte, trouves));
            }
        }
        Map<String, LotClaude.Resultat> r2 = lot.executer("redactions", redactions);
        for (String id : redactions.keySet()) {
            Question q = questions.stream().filter(x -> x.id().equals(id)).findFirst().orElseThrow();
            LotClaude.Resultat r = r2.get(id);
            compter(jetons.get(id), r);
            if (r == null || r.message() == null) {
                erreurs.put(id, r == null ? "résultat absent du lot" : r.erreur());
            } else {
                reponses.put(id, service.assembler(q.question(), contextes.get(id), requetes.get(id), articles.get(id),
                        AssistantClaude.versReponse(r.message(), articles.get(id))));
            }
        }

        // 3. Juge
        Map<String, LotClaude.Resultat> r3 = Map.of();
        if (juge != null) {
            Map<String, MessageCreateParams> jugements = new LinkedHashMap<>();
            for (Question q : questions) {
                ReponseQuestion reponse = reponses.get(q.id());
                if (reponse != null && !reponse.refus() && !reponse.passages().isEmpty()) {
                    jugements.put(q.id(), juge.parametres(q, reponse).rawParams());
                }
            }
            r3 = lot.executer("juge", jugements);
        }

        long[] jetonsJuge = new long[2];
        List<Resultat> resultats = new ArrayList<>();
        for (Question q : questions) {
            long[] j = jetons.get(q.id());
            Resultat resultat = new Resultat(q, reponses.get(q.id()), erreurs.get(q.id()), j[0], j[1], -1);
            LotClaude.Resultat jugement = r3.get(q.id());
            if (jugement != null) {
                compter(jetonsJuge, jugement);
                try {
                    resultat = jugement.message() == null ? resultat.avecVerdict(null, jugement.erreur())
                            : resultat.avecVerdict(JugeClaude.verdictDepuis(jugement.message()), null);
                } catch (RuntimeException e) {
                    resultat = resultat.avecVerdict(null, decrire(e));
                }
            }
            resultats.add(resultat);
        }
        Files.writeString(rapport, RapportEvaluation.markdown(resultats, tarif.lots(), false, tarifJuge.lots(),
                jetonsJuge[0], jetonsJuge[1], questions.size()), StandardCharsets.UTF_8);
    }

    private static void compter(long[] jetons, LotClaude.Resultat resultat) {
        if (resultat != null && resultat.message() != null) {
            jetons[0] += resultat.message().usage().inputTokens();
            jetons[1] += resultat.message().usage().outputTokens();
        }
    }

    private static String decrire(Throwable e) {
        return e.getClass().getSimpleName() + (e.getMessage() == null ? "" : " : " + e.getMessage());
    }

    /** Arrêt avant le premier appel payant si un article attendu est introuvable, mal numéroté ou non applicable. */
    private void verifierArticlesAttendus(List<Question> questions) {
        assertThat(ArticlesAttendus.anomalies(questions, articleRepository))
                .as("articles attendus introuvables, mal numérotés ou non applicables").isEmpty();
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
            String mots = Arrays.stream(question.toLowerCase(Locale.ROOT).split("[^\\p{L}0-9]+"))
                    .filter(m -> m.length() >= 5)
                    .distinct()
                    .collect(Collectors.joining(" or "));
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
            String texte = AssistantClaude.contenu(a);
            return texte.substring(0, Math.min(60, texte.length()));
        }
    }
}
