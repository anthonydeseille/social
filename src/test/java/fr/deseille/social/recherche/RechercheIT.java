package fr.deseille.social.recherche;

import static org.assertj.core.api.Assertions.assertThat;

import fr.deseille.social.TestcontainersConfiguration;
import fr.deseille.social.corpus.Article;
import fr.deseille.social.corpus.ArticleRepository;
import fr.deseille.social.ingestion.TitresSection;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
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
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

/**
 * Recherche plein texte sur un corpus issu du job d'ingestion : {@code legi-recherche} (période
 * d'essai, préavis, grossesse, un article abrogé, une abrogation différée échue en 2024, des cas
 * charnières d'applicabilité sur le télétravail) et
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
    private static final String DISPARU = "LEGIARTI000000000076";
    private static final String VIGUEUR_DIFF = "LEGIARTI000000000077";
    private static final String VIGUEUR_DIFF_SANS_DATE = "LEGIARTI000000000078";

    @Autowired
    private MockMvcTester mvc;
    @Autowired
    private JdbcClient jdbcClient;
    @Autowired
    private ArticleRepository articleRepository;

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

    /** « protection » et « maternité » ne figurent que dans le titre de la section de L1225-4. */
    @Test
    void mots_du_titre_de_section_trouvent_l_article() {
        assertThat(jdbcClient.sql("select titres_section from article where source_id = ?").param(GROSSESSE)
                .query(String.class).single())
                .isEqualTo("Contrat de travail > Protection de la grossesse et de la maternité");
        assertThat(jdbcClient.sql("select titres_section from article where source_id = ?").param(CONVENTION)
                .query(String.class).single()).as("convention").isEqualTo("Période d'essai");
        assertThat(mvc.get().uri("/api/recherche?q={q}", "protection maternité")).hasStatusOk().bodyJson()
                .extractingPath("$[*].articleSourceId").asArray().containsExactly(GROSSESSE);
    }

    /** Titre de section modifié seul (articles inchangés) : titres recalculés, ligne par ligne. */
    @Test
    void titres_recalcules_seulement_quand_ils_changent(@Autowired TitresSection titresSection) {
        Long texte = jdbcClient.sql("select texte_id from article where source_id = ?").param(GROSSESSE)
                .query(Long.class).single();
        assertThat(titresSection.mettreAJour(texte)).as("rien n'a changé depuis l'ingestion").isZero();

        jdbcClient.sql("update section set titre = 'Égalité professionnelle' where source_id = 'LEGISCTA000000000072'").update();
        try {
            assertThat(titresSection.mettreAJour(texte)).isEqualTo(1);
            assertThat(mvc.get().uri("/api/recherche?q={q}", "égalité professionnelle")).hasStatusOk().bodyJson()
                    .extractingPath("$[*].articleSourceId").asArray().containsExactly(GROSSESSE);

            // Titre retiré : reste celui de la section parente ; plus aucun titre : null, pas l'ancienne valeur
            jdbcClient.sql("update section set titre = null where source_id = 'LEGISCTA000000000072'").update();
            titresSection.mettreAJour(texte);
            assertThat(titres(GROSSESSE)).isEqualTo("Contrat de travail");
            jdbcClient.sql("update section set titre = null where source_id = 'LEGISCTA000000000071'").update();
            titresSection.mettreAJour(texte);
            assertThat(titres(GROSSESSE)).isNull();
            assertThat(titres(PERIODE_ESSAI)).isNull();
        } finally {
            jdbcClient.sql("update section set titre = 'Contrat de travail' where source_id = 'LEGISCTA000000000071'").update();
            jdbcClient.sql("update section set titre = 'Protection de la grossesse et de la maternité' "
                    + "where source_id = 'LEGISCTA000000000072'").update();
            titresSection.mettreAJour(texte);
        }
    }

    private String titres(String articleSourceId) {
        return jdbcClient.sql("select titres_section from article where source_id = ?").param(articleSourceId)
                .query(String.class).optional().orElse(null);
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
    void parametres_invalides_refuses_et_bornes_acceptees() {
        assertThat(mvc.get().uri("/api/recherche?q=")).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(mvc.get().uri("/api/recherche?q=   ")).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(mvc.get().uri("/api/recherche")).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(mvc.get().uri("/api/recherche?q={q}", "a".repeat(201))).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(mvc.get().uri("/api/recherche?q=essai&limite=0")).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(mvc.get().uri("/api/recherche?q=essai&limite=51")).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(mvc.get().uri("/api/recherche?q=essai&limite=1")).hasStatusOk().bodyJson()
                .extractingPath("$").asArray().hasSize(1);
        assertThat(mvc.get().uri("/api/recherche?q=essai&limite=50")).hasStatusOk();
    }

    @Test
    void requete_sans_terme_positif_renvoie_une_liste_vide() {
        // « les » n'est pas un mot vide de PostgreSQL (il devient « le ») : mots vides garantis ici
        assertThat(mvc.get().uri("/api/recherche?q={q}", "du de des au aux")).hasStatusOk().bodyJson()
                .extractingPath("$").asArray().isEmpty();
        assertThat(mvc.get().uri("/api/recherche?q={q}&applicables=false", "-grossesse")).hasStatusOk().bodyJson()
                .extractingPath("$").asArray().isEmpty();
    }

    @Test
    void idcc_inconnu_seulement_le_code() {
        assertThat(mvc.get().uri("/api/recherche?q={q}&idcc=1234", "période d'essai")).hasStatusOk().bodyJson()
                .extractingPath("$[*].articleSourceId").asArray().containsExactly(PERIODE_ESSAI);
    }

    /**
     * Le filtre SQL d'applicabilité reproduit {@code Article.estApplicable} : les deux doivent
     * retenir les mêmes articles, notamment aux dates charnières (fin d'un ABROGE_DIFF au
     * 1er janvier 2024, début d'un VIGUEUR_DIFF au 1er janvier 2027, VIGUEUR_DIFF sans date,
     * article disparu).
     */
    @Test
    void filtre_sql_et_regle_java_concordent_aux_dates_charnieres() {
        jdbcClient.sql("update article set disparu_le = now() where source_id = :id").param("id", DISPARU).update();
        List<Article> articles = articleRepository.findAll();

        for (LocalDate date : List.of(LocalDate.of(2023, 12, 31), LocalDate.of(2024, 1, 1),
                LocalDate.of(2026, 12, 31), LocalDate.of(2027, 1, 1), LocalDate.now())) {
            Set<String> sql = Set.copyOf(jdbcClient
                    .sql("select a.source_id from article a where " + RechercheRepository.APPLICABLE)
                    .param("date", date).query(String.class).list());
            Set<String> java = articles.stream().filter(a -> a.estApplicableLe(date))
                    .map(Article::getSourceId).collect(Collectors.toSet());
            assertThat(sql).as("articles applicables au %s", date).isEqualTo(java);
        }

        // Les cas charnières sont bien exercés
        assertThat(applicablesSql(LocalDate.of(2023, 12, 31))).contains(ABROGE_DIFF_ECHU);
        assertThat(applicablesSql(LocalDate.of(2024, 1, 1))).doesNotContain(ABROGE_DIFF_ECHU);
        assertThat(applicablesSql(LocalDate.of(2026, 12, 31))).doesNotContain(VIGUEUR_DIFF);
        assertThat(applicablesSql(LocalDate.of(2027, 1, 1))).contains(VIGUEUR_DIFF);
        assertThat(applicablesSql(LocalDate.of(2027, 1, 1))).doesNotContain(VIGUEUR_DIFF_SANS_DATE, DISPARU);
    }

    private List<String> applicablesSql(LocalDate date) {
        return jdbcClient.sql("select a.source_id from article a where " + RechercheRepository.APPLICABLE)
                .param("date", date).query(String.class).list();
    }

    private static void copier(String ressource, Path cible) throws IOException {
        try (InputStream in = RechercheIT.class.getResourceAsStream(ressource)) {
            assertThat(in).as("fixture %s", ressource).isNotNull();
            Files.copy(in, cible);
        }
    }
}
