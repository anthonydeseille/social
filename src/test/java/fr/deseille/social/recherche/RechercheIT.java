package fr.deseille.social.recherche;

import static org.assertj.core.api.Assertions.assertThat;

import fr.deseille.social.TestcontainersConfiguration;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

/**
 * Recherche plein texte sur un corpus issu du job d'ingestion : {@code legi-recherche} (période
 * d'essai, préavis, grossesse, un article abrogé, une abrogation différée échue en 2024) et
 * {@code kali-recherche} (IDCC 7777, période d'essai des cadres).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RechercheIT {

    private static final String PERIODE_ESSAI = "LEGIARTI000000000071";
    private static final String PREAVIS = "LEGIARTI000000000072";
    private static final String ABROGE = "LEGIARTI000000000073";
    private static final String GROSSESSE = "LEGIARTI000000000074";
    private static final String ABROGE_DIFF_ECHU = "LEGIARTI000000000075";
    private static final String CONVENTION = "KALIARTI000000000071";

    @Autowired
    private MockMvcTester mvc;

    @DynamicPropertySource
    static void sources(DynamicPropertyRegistry registry) throws IOException {
        Path repertoire = Files.createTempDirectory("recherche-it");
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
    void sans_accent_et_seulement_les_articles_applicables_du_code() {
        assertThat(mvc.get().uri("/api/recherche?q={q}", "periode d'essai")).hasStatusOk().bodyJson()
                .extractingPath("$[*].articleSourceId").asArray()
                .containsExactly(PERIODE_ESSAI);
    }

    @Test
    void avec_l_idcc_la_convention_est_incluse() {
        assertThat(mvc.get().uri("/api/recherche?q={q}&idcc=7777", "période d'essai")).hasStatusOk().bodyJson()
                .extractingPath("$[*].articleSourceId").asArray()
                .containsExactlyInAnyOrder(PERIODE_ESSAI, CONVENTION);
    }

    @Test
    void articles_non_applicables_sur_demande_avec_leur_indicateur() {
        var reponse = mvc.get().uri("/api/recherche?q={q}&applicables=false", "période d'essai");
        assertThat(reponse).hasStatusOk().bodyJson().extractingPath("$[*].articleSourceId").asArray()
                .containsExactlyInAnyOrder(PERIODE_ESSAI, ABROGE, ABROGE_DIFF_ECHU);
        assertThat(reponse).bodyJson().extractingPath("$[?(@.applicable == true)].articleSourceId").asArray()
                .containsExactly(PERIODE_ESSAI);
    }

    @Test
    void racinisation_et_recherche_par_numero() {
        assertThat(mvc.get().uri("/api/recherche?q={q}", "licenciements")).hasStatusOk().bodyJson()
                .extractingPath("$[*].articleSourceId").asArray().containsExactly(PREAVIS);
        assertThat(mvc.get().uri("/api/recherche?q={q}", "L1225-4")).hasStatusOk().bodyJson()
                .extractingPath("$[0].articleSourceId").isEqualTo(GROSSESSE);
    }

    @Test
    void syntaxe_web_exclusion() {
        assertThat(mvc.get().uri("/api/recherche?q={q}&idcc=7777", "contrat -grossesse")).hasStatusOk().bodyJson()
                .extractingPath("$[*].articleSourceId").asArray()
                .contains(PERIODE_ESSAI).doesNotContain(GROSSESSE);
    }

    @Test
    void extrait_surligne_et_texte_source() {
        var reponse = mvc.get().uri("/api/recherche?q={q}&idcc=7777", "cadres");
        assertThat(reponse).hasStatusOk().bodyJson().extractingPath("$[*].extrait").asArray()
                .allSatisfy(e -> assertThat((String) e).contains("<mark>"));
        assertThat(reponse).bodyJson().extractingPath("$[?(@.articleSourceId == '" + CONVENTION + "')].texteType")
                .asArray().containsExactly("CONVENTION_COLLECTIVE");
        assertThat(reponse).bodyJson().extractingPath("$[?(@.articleSourceId == '" + CONVENTION + "')].extrait")
                .asArray().singleElement().asString().doesNotContain("<strong>");
    }

    @Test
    void parametres_invalides_refuses() {
        assertThat(mvc.get().uri("/api/recherche?q=")).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(mvc.get().uri("/api/recherche")).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(mvc.get().uri("/api/recherche?q=essai&limite=0")).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(mvc.get().uri("/api/recherche?q=essai&limite=51")).hasStatus(HttpStatus.BAD_REQUEST);
    }

    private static void copier(String ressource, Path cible) throws IOException {
        try (InputStream in = RechercheIT.class.getResourceAsStream(ressource)) {
            assertThat(in).as("fixture %s", ressource).isNotNull();
            Files.copy(in, cible);
        }
    }
}
