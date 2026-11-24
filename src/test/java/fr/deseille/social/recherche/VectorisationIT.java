package fr.deseille.social.recherche;

import static org.assertj.core.api.Assertions.assertThat;

import fr.deseille.social.TestcontainersConfiguration;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Ingestion puis vectorisation au démarrage ({@code droits.ingestion.run} et
 * {@code droits.semantique.vectoriser}), avec un faux modèle de vecteurs : un axe par thème
 * (préavis, période d'essai), plus un axe commun pour qu'aucun vecteur ne soit nul.
 */
@SpringBootTest(properties = {"droits.ingestion.run=true", "droits.semantique.vectoriser=true"})
@Import({TestcontainersConfiguration.class, VectorisationIT.FauxModele.class})
class VectorisationIT {

    private static final String PERIODE_ESSAI = "LEGIARTI000000000071";
    private static final String PREAVIS = "LEGIARTI000000000072";
    private static final String GROSSESSE = "LEGIARTI000000000074";
    private static final String TELETRAVAIL = "LEGIARTI000000000076";
    private static final String CONVENTION = "KALIARTI000000000071";
    private static final AtomicInteger CALCULS = new AtomicInteger();

    @Autowired
    private JdbcClient jdbc;
    @Autowired
    private RechercheSemantique semantique;
    @Autowired
    private RechercheService rechercheService;

    @DynamicPropertySource
    static void sources(DynamicPropertyRegistry registry) throws IOException {
        Path repertoire = Files.createTempDirectory("vectorisation-it");
        copier("/fixtures/legi-recherche.json", repertoire.resolve("legi.json"));
        copier("/fixtures/kali-recherche.json", repertoire.resolve("kali.json"));
        registry.add("droits.ingestion.base-url", () -> repertoire.toUri().toString());
        registry.add("droits.ingestion.cache-dir", () -> repertoire.resolve("cache").toString());
        registry.add("droits.semantique.cache-dir", () -> repertoire.resolve("vecteurs").toString());
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

    @Test
    void au_demarrage_tous_les_articles_applicables_des_textes_en_base_sont_vectorises() {
        assertThat(jdbc.sql("select a.source_id from article_vecteur v join article a on a.id = v.article_id")
                .query(String.class).list())
                .as("Code et convention ; ni abrogés, ni à venir")
                .containsExactlyInAnyOrder(PERIODE_ESSAI, PREAVIS, GROSSESSE, TELETRAVAIL, CONVENTION);
    }

    @Test
    void relancee_la_vectorisation_ne_refait_rien_et_le_cache_evite_les_calculs() {
        int calculs = CALCULS.get();
        assertThat(semantique.vectoriser(rechercheService.textes(7777))).as("vecteurs à jour").isZero();

        jdbc.sql("delete from article_vecteur").update();
        assertThat(semantique.vectoriser(rechercheService.textes(7777))).isEqualTo(5);
        assertThat(CALCULS.get()).as("vecteurs relus dans le cache disque").isEqualTo(calculs);
    }

    @Test
    void recherche_par_le_sens_parmi_les_articles_applicables() {
        assertThat(semantique.rechercher("Quelle est la durée de la période d'essai ?", rechercheService.textes(null),
                LocalDate.now(), 2))
                .extracting(RechercheService.Resultat::articleSourceId)
                .as("le plus proche d'abord ; les articles abrogés sur l'essai ne sont pas vectorisés")
                .startsWith(PERIODE_ESSAI)
                .doesNotContain(CONVENTION);
    }

    private static void copier(String ressource, Path cible) throws IOException {
        try (InputStream in = VectorisationIT.class.getResourceAsStream(ressource)) {
            assertThat(in).as("fixture %s", ressource).isNotNull();
            Files.copy(in, cible);
        }
    }

    @TestConfiguration
    static class FauxModele {

        @Bean
        EmbeddingModel fauxModeleDeVecteurs() {
            return new EmbeddingModel() {
                @Override
                public EmbeddingResponse call(EmbeddingRequest requete) {
                    List<Embedding> resultats = new ArrayList<>();
                    for (String texte : requete.getInstructions()) {
                        CALCULS.incrementAndGet();
                        resultats.add(new Embedding(vecteur(texte), resultats.size()));
                    }
                    return new EmbeddingResponse(resultats);
                }

                @Override
                public float[] embed(Document document) {
                    return vecteur(document.getText());
                }
            };
        }

        private static float[] vecteur(String texte) {
            String t = texte.toLowerCase(Locale.FRENCH);
            float[] v = new float[RechercheSemantique.DIMENSION];
            v[0] = t.contains("préavis") ? 1 : 0;
            v[1] = t.contains("essai") ? 1 : 0;
            v[2] = 0.1f;
            return v;
        }
    }
}
