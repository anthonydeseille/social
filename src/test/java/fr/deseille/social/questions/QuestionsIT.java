package fr.deseille.social.questions;

import static org.assertj.core.api.Assertions.assertThat;

import fr.deseille.social.TestcontainersConfiguration;
import fr.deseille.social.consultation.VuesConsultation.ArticleDetail;
import fr.deseille.social.profil.ProfilService.ContexteProfil;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

/**
 * {@code POST /api/questions} de bout en bout sur un vrai corpus (fixtures {@code legi-recherche} et
 * {@code kali-recherche}, IDCC 7777) avec un faux assistant : la recherche, la sélection des
 * articles et la réponse sont réelles, seul le modèle de langage est simulé (aucun appel payant).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, QuestionsIT.FauxAssistant.class})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class QuestionsIT {

    @Autowired
    private MockMvcTester mvc;

    /** Reformule toujours en « période essai » et cite le premier article reçu. */
    @TestConfiguration
    static class FauxAssistant {

        @Bean
        @Primary
        Assistant fauxAssistant() {
            return new Assistant() {
                @Override
                public List<String> reformuler(String question, ContexteProfil contexte) {
                    return List.of("période essai");
                }

                @Override
                public Reponse rediger(String question, ContexteProfil contexte, List<ArticleDetail> articles) {
                    if (question.contains("panne")) {
                        throw new AssistantIndisponibleException(new IllegalStateException("panne simulée"));
                    }
                    ArticleDetail premier = articles.getFirst();
                    // Extrait réel de l'article, sauf pour simuler une citation inventée
                    String extrait = question.contains("inventée") ? "texte qui n'existe pas"
                            : AssistantClaude.contenu(premier).substring(0, 20);
                    return new Reponse(List.of(new Passage("Réponse simulée.",
                            List.of(new Citation(premier.sourceId(), premier.num(), extrait)))), false, false);
                }
            };
        }
    }

    @DynamicPropertySource
    static void sources(DynamicPropertyRegistry registry) throws IOException {
        Path repertoire = Files.createTempDirectory("questions-it");
        copier("/fixtures/legi-recherche.json", repertoire.resolve("legi.json"));
        copier("/fixtures/kali-recherche.json", repertoire.resolve("kali.json"));
        registry.add("droits.ingestion.base-url", () -> repertoire.toUri().toString());
        registry.add("droits.ingestion.cache-dir", () -> repertoire.resolve("cache").toString());
        String[][] sources = {{"code", "CODE", "legi.json"}, {"syntec", "CONVENTION_COLLECTIVE", "kali.json"}};
        for (int i = 0; i < sources.length; i++) {
            String[] s = sources[i];
            registry.add("droits.ingestion.sources[" + i + "].cle", () -> s[0]);
            registry.add("droits.ingestion.sources[" + i + "].type", () -> s[1]);
            registry.add("droits.ingestion.sources[" + i + "].repo", () -> "");
            registry.add("droits.ingestion.sources[" + i + "].ref", () -> "");
            registry.add("droits.ingestion.sources[" + i + "].fichier", () -> s[2]);
        }
    }

    @BeforeAll
    void ingerer(@Autowired JobOperator jobOperator, @Autowired Job ingestionJob) throws Exception {
        var execution = jobOperator.start(ingestionJob,
                new JobParametersBuilder().addLong("horodatage", System.nanoTime()).toJobParameters());
        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
    }

    @Test
    void reponse_avec_sources_du_code_et_de_la_convention() {
        var reponse = mvc.post().uri("/api/questions").contentType(MediaType.APPLICATION_JSON).content("""
                {"question": "Combien de temps dure ma période d'essai ?",
                 "profil": {"idcc": 7777, "statut": "CADRE", "classification": "IC", "typeContrat": "CDI",
                            "dateEntree": "2026-10-01", "dateReference": "2026-11-01"}}
                """);

        assertThat(reponse).hasStatusOk().bodyJson().extractingPath("$.requetes").asArray()
                .containsExactly("période essai");
        assertThat(reponse).bodyJson().extractingPath("$.sources[*].articleSourceId").asArray()
                .containsExactlyInAnyOrder("LEGIARTI000000000071", "KALIARTI000000000071");
        assertThat(reponse).bodyJson().extractingPath("$.passages[0].citations[0].num").isNotNull();
        assertThat(reponse).bodyJson().extractingPath("$.contexte.anciennete.mois").isEqualTo(1);
        assertThat(reponse).bodyJson().extractingPath("$.refus").isEqualTo(false);
    }

    @Test
    void sans_convention_seulement_le_code_et_un_avertissement() {
        var reponse = mvc.post().uri("/api/questions").contentType(MediaType.APPLICATION_JSON).content("""
                {"question": "Combien de temps dure ma période d'essai ?", "profil": {}}
                """);

        assertThat(reponse).hasStatusOk().bodyJson().extractingPath("$.sources[*].articleSourceId").asArray()
                .containsExactly("LEGIARTI000000000071");
        assertThat(reponse).bodyJson().extractingPath("$.avertissements[0]").asString().contains("fiche de paie");
    }

    @Test
    void articles_retenus_selon_la_date_de_reference() {
        // L1221-98 (ABROGE_DIFF) cesse de s'appliquer le 1er janvier 2024 : retenu au 31 décembre 2023 seulement
        var avant = mvc.post().uri("/api/questions").contentType(MediaType.APPLICATION_JSON).content("""
                {"question": "Ma période d'essai ?", "profil": {"dateReference": "2023-12-31"}}
                """);
        assertThat(avant).hasStatusOk().bodyJson().extractingPath("$.sources[*].articleSourceId").asArray()
                .contains("LEGIARTI000000000075");

        var apres = mvc.post().uri("/api/questions").contentType(MediaType.APPLICATION_JSON).content("""
                {"question": "Ma période d'essai ?", "profil": {"dateReference": "2024-01-01"}}
                """);
        assertThat(apres).hasStatusOk().bodyJson().extractingPath("$.sources[*].articleSourceId").asArray()
                .doesNotContain("LEGIARTI000000000075");
    }

    @Test
    void citation_introuvable_signalee_sans_etre_retiree() {
        var correcte = mvc.post().uri("/api/questions").contentType(MediaType.APPLICATION_JSON).content("""
                {"question": "Ma période d'essai ?", "profil": {"idcc": 7777}}
                """);
        assertThat(correcte).hasStatusOk().bodyJson().extractingPath("$.citationsNonVerifiees").asArray().isEmpty();

        var inventee = mvc.post().uri("/api/questions").contentType(MediaType.APPLICATION_JSON).content("""
                {"question": "Ma période d'essai, citation inventée ?", "profil": {"idcc": 7777}}
                """);
        assertThat(inventee).hasStatusOk().bodyJson().extractingPath("$.citationsNonVerifiees[0].texteCite")
                .isEqualTo("texte qui n'existe pas");
        assertThat(inventee).bodyJson().extractingPath("$.passages[0].citations").asArray().hasSize(1);
        assertThat(inventee).bodyJson().extractingPath("$.avertissements").asArray()
                .anySatisfy(a -> assertThat((String) a).contains("introuvable"));
    }

    @Test
    void modele_indisponible_en_503() {
        var reponse = mvc.post().uri("/api/questions").contentType(MediaType.APPLICATION_JSON).content("""
                {"question": "Ma période d'essai en cas de panne ?", "profil": {}}
                """);
        assertThat(reponse).hasStatus(HttpStatus.SERVICE_UNAVAILABLE).hasContentType(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(reponse).bodyJson().extractingPath("$.detail").asString().contains("indisponible")
                .doesNotContain("panne simulée");
    }

    @Test
    void demande_invalide_refusee() {
        assertThat(mvc.post().uri("/api/questions").contentType(MediaType.APPLICATION_JSON)
                .content("{\"question\": \" \", \"profil\": {}}")).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(mvc.post().uri("/api/questions").contentType(MediaType.APPLICATION_JSON)
                .content("{\"question\": \"Mes congés ?\"}")).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(mvc.post().uri("/api/questions").contentType(MediaType.APPLICATION_JSON)
                .content("{\"question\": \"Mes congés ?\", \"profil\": {\"classification\": \"" + "x".repeat(81) + "\"}}"))
                .hasStatus(HttpStatus.BAD_REQUEST);
    }

    private static void copier(String ressource, Path cible) throws IOException {
        try (InputStream in = QuestionsIT.class.getResourceAsStream(ressource)) {
            assertThat(in).as("fixture %s", ressource).isNotNull();
            Files.copy(in, cible);
        }
    }
}
