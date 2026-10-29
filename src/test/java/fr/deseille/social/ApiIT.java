package fr.deseille.social;

import static org.assertj.core.api.Assertions.assertThat;

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
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

/**
 * Teste l'API HTTP (consultation et profil) sur un corpus réel issu du job d'ingestion lancé
 * une fois sur les fixtures {@code legi-mini} et {@code kali-mini} (IDCC 9999).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ApiIT {

    private static final String CODE = "LEGITEXT000000000001";

    @Autowired
    private MockMvcTester mvc;

    @DynamicPropertySource
    static void sources(DynamicPropertyRegistry registry) throws IOException {
        Path repertoire = Files.createTempDirectory("api-it");
        copier("/fixtures/legi-mini.json", repertoire.resolve("legi.json"));
        copier("/fixtures/kali-mini.json", repertoire.resolve("kali.json"));
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

    // --- Consultation ---

    @Test
    void liste_des_textes_code_puis_conventions() {
        assertThat(mvc.get().uri("/api/textes")).hasStatusOk().bodyJson()
                .extractingPath("$[*].sourceId").asArray().containsExactly(CODE, "KALICONT000000000001");
        assertThat(mvc.get().uri("/api/textes")).bodyJson()
                .extractingPath("$[1].idcc").isEqualTo(9999);
    }

    @Test
    void sommaire_racine_sections_et_articles_dans_l_ordre() {
        var reponse = mvc.get().uri("/api/textes/{id}/sommaire", CODE);
        assertThat(reponse).hasStatusOk().bodyJson().extractingPath("$.section").isNull();
        assertThat(reponse).bodyJson().extractingPath("$.elements[*].sourceId").asArray()
                .containsExactly("LEGISCTA000000000001", "LEGIARTI000000000003");
        assertThat(reponse).bodyJson().extractingPath("$.elements[*].nature").asArray()
                .containsExactly("SECTION", "ARTICLE");
    }

    @Test
    void sommaire_d_une_section_avec_fil_d_ariane() {
        var reponse = mvc.get().uri("/api/textes/{id}/sommaire?section={s}", CODE, "LEGISCTA000000000002");
        assertThat(reponse).hasStatusOk().bodyJson().extractingPath("$.filAriane[*].sourceId").asArray()
                .containsExactly("LEGISCTA000000000001", "LEGISCTA000000000002");
        assertThat(reponse).bodyJson().extractingPath("$.elements[*].libelle").asArray()
                .containsExactly("L1221-2");
    }

    @Test
    void section_d_un_autre_texte_introuvable() {
        assertThat(mvc.get().uri("/api/textes/{id}/sommaire?section={s}", CODE, "KALISCTA000000000001"))
                .hasStatus(HttpStatus.NOT_FOUND);
    }

    @Test
    void article_complet_avec_sa_place_dans_le_sommaire() {
        var reponse = mvc.get().uri("/api/articles/{id}", "LEGIARTI000000000002");
        assertThat(reponse).hasStatusOk().bodyJson().extractingPath("$.num").isEqualTo("L1221-2");
        assertThat(reponse).bodyJson().extractingPath("$.applicable").isEqualTo(true);
        assertThat(reponse).bodyJson().extractingPath("$.dateDebut").isEqualTo("2020-01-01");
        assertThat(reponse).bodyJson().extractingPath("$.texte.sourceId").isEqualTo(CODE);
        assertThat(reponse).bodyJson().extractingPath("$.filAriane[*].titre").asArray()
                .containsExactly("Livre I", "Titre I");
    }

    @Test
    void article_abroge_non_applicable() {
        assertThat(mvc.get().uri("/api/articles/{id}", "LEGIARTI000000000003")).hasStatusOk().bodyJson()
                .extractingPath("$.applicable").isEqualTo(false);
    }

    @Test
    void article_inconnu_en_probleme_json() {
        var reponse = mvc.get().uri("/api/articles/{id}", "LEGIARTI999999999999");
        assertThat(reponse).hasStatus(HttpStatus.NOT_FOUND).hasContentType(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(reponse).bodyJson().extractingPath("$.detail").asString().contains("LEGIARTI999999999999");
    }

    @Test
    void recherche_par_numero_et_historique() {
        assertThat(mvc.get().uri("/api/textes/{id}/articles?num={n}", CODE, "L1221-1")).hasStatusOk().bodyJson()
                .extractingPath("$[*].sourceId").asArray().containsExactly("LEGIARTI000000000001");

        var historique = mvc.get().uri("/api/articles/{id}/historique", "LEGIARTI000000000001");
        assertThat(historique).hasStatusOk().bodyJson().extractingPath("$.versions[*].sourceId").asArray()
                .containsExactly("LEGIARTI000000000001");
        assertThat(historique).bodyJson().extractingPath("$.evenements[*].type").asArray()
                .containsExactly("CREATION");
    }

    // --- Profil ---

    @Test
    void contexte_avec_convention_integree_et_anciennete() {
        var reponse = mvc.post().uri("/api/profil/contexte").contentType(MediaType.APPLICATION_JSON).content("""
                {"idcc": 9999, "dateEntree": "2020-03-15", "dateReference": "2026-10-29",
                 "statut": "CADRE", "typeContrat": "CDI", "situations": ["GROSSESSE"]}
                """);
        assertThat(reponse).hasStatusOk().bodyJson().extractingPath("$.anciennete.annees").isEqualTo(6);
        assertThat(reponse).bodyJson().extractingPath("$.anciennete.mois").isEqualTo(7);
        assertThat(reponse).bodyJson().extractingPath("$.conventionDisponible").isEqualTo(true);
        assertThat(reponse).bodyJson().extractingPath("$.textesApplicables[*].sourceId").asArray()
                .containsExactly(CODE, "KALICONT000000000001");
        assertThat(reponse).bodyJson().extractingPath("$.avertissements").asArray().isEmpty();
        assertThat(reponse).bodyJson().extractingPath("$.profil.situations").asArray().containsExactly("GROSSESSE");
    }

    @Test
    void contexte_profil_vide_avertit_sur_la_convention() {
        var reponse = mvc.post().uri("/api/profil/contexte").contentType(MediaType.APPLICATION_JSON).content("{}");
        assertThat(reponse).hasStatusOk().bodyJson().extractingPath("$.anciennete").isNull();
        assertThat(reponse).bodyJson().extractingPath("$.profil.dateReference").isNotNull();
        assertThat(reponse).bodyJson().extractingPath("$.avertissements[0]").asString().contains("fiche de paie");
    }

    @Test
    void profil_invalide_refuse() {
        assertThat(mvc.post().uri("/api/profil/contexte").contentType(MediaType.APPLICATION_JSON)
                .content("{\"age\": 12}")).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(mvc.post().uri("/api/profil/contexte").contentType(MediaType.APPLICATION_JSON)
                .content("{\"dateEntree\": \"2027-01-01\", \"dateReference\": \"2026-10-29\"}"))
                .hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(mvc.post().uri("/api/profil/contexte").contentType(MediaType.APPLICATION_JSON)
                .content("{\"statut\": \"STAGIAIRE\"}")).hasStatus(HttpStatus.BAD_REQUEST);
    }

    private static void copier(String ressource, Path cible) throws IOException {
        try (InputStream in = ApiIT.class.getResourceAsStream(ressource)) {
            assertThat(in).as("fixture %s", ressource).isNotNull();
            Files.copy(in, cible);
        }
    }
}
