package fr.deseille.social.ingestion;

import static fr.deseille.social.ingestion.FixturesIngestion.copier;
import static org.assertj.core.api.Assertions.assertThat;

import fr.deseille.social.TestcontainersConfiguration;
import fr.deseille.social.corpus.Article;
import fr.deseille.social.corpus.ArticleRepository;
import fr.deseille.social.corpus.EtatJuridique;
import fr.deseille.social.corpus.JournalChangement;
import fr.deseille.social.corpus.JournalChangementRepository;
import fr.deseille.social.corpus.Section;
import fr.deseille.social.corpus.SectionRepository;
import fr.deseille.social.corpus.Texte;
import fr.deseille.social.corpus.TexteRepository;
import fr.deseille.social.corpus.TypeChangement;
import fr.deseille.social.corpus.TypeTexte;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.test.JobOperatorTestUtils;
import org.springframework.batch.test.context.SpringBatchTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Teste le job d'ingestion de bout en bout sur des fixtures réduites (Code + convention),
 * en enchaînant des runs sur des variantes de la convention : création, idempotence, journal
 * des changements, disparition consignée une seule fois et réapparition. Les cas d'échec sont
 * dans {@link IngestionEchecIT}.
 */
@SpringBootTest
@SpringBatchTest
@Import(TestcontainersConfiguration.class)
class IngestionJobIT {

    private static Path repertoire;

    @Autowired
    private JobOperatorTestUtils jobOperatorTestUtils;
    @Autowired
    private TexteRepository texteRepository;
    @Autowired
    private SectionRepository sectionRepository;
    @Autowired
    private ArticleRepository articleRepository;
    @Autowired
    private JournalChangementRepository journalRepository;
    @Autowired
    private PlatformTransactionManager transactionManager;

    @DynamicPropertySource
    static void sourcesFichierLocal(DynamicPropertyRegistry registry) throws IOException {
        repertoire = Files.createTempDirectory("ingestion-it");
        copier("/fixtures/legi-mini.json", repertoire.resolve("legi.json"));
        copier("/fixtures/kali-mini.json", repertoire.resolve("kali.json"));
        FixturesIngestion.declarerSources(registry, repertoire, "legi.json", "kali.json");
    }

    @Test
    void ingestion_complete_idempotente_et_journal() throws Exception {
        // --- Run 1 : création complète ---
        assertThat(lancer()).isEqualTo(BatchStatus.COMPLETED);

        assertThat(texteRepository.count()).isEqualTo(2);
        assertThat(sectionRepository.count()).isEqualTo(3);
        assertThat(articleRepository.count()).isEqualTo(5);
        assertThat(journalRepository.count()).isEqualTo(5);
        assertThat(journalRepository.findAll())
                .allMatch(j -> j.getTypeChangement() == TypeChangement.CREATION);

        // Dates : Code (epoch millis) parsé, convention sans date
        Article codeArt = article("LEGIARTI000000000001");
        assertThat(codeArt.getDateDebut()).isEqualTo(LocalDate.of(2020, 1, 1));
        assertThat(codeArt.getEtat()).isEqualTo(EtatJuridique.VIGUEUR);
        assertThat(codeArt.getContenuTexte()).isEqualTo("Contenu de l'article un.");
        assertThat(codeArt.getMetaHash()).isNotNull();
        assertThat(codeArt.getDisparuLe()).isNull();

        Article convArt = article("KALIARTI000000000001");
        assertThat(convArt.getDateDebut()).isNull();
        assertThat(convArt.getContenuHtml()).contains("article A");
        assertThat(convArt.getContenuTexte()).isNull();

        Texte convention = texteRepository.findBySourceId("KALICONT000000000001").orElseThrow();
        assertThat(convention.getType()).isEqualTo(TypeTexte.CONVENTION_COLLECTIVE);
        assertThat(convention.getIdcc()).isEqualTo(9999);

        // Hiérarchie (navigation paresseuse → dans une transaction)
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            Section imbriquee = sectionRepository.findBySourceId("LEGISCTA000000000002").orElseThrow();
            assertThat(imbriquee.getParent()).isNotNull();
            assertThat(imbriquee.getParent().getSourceId()).isEqualTo("LEGISCTA000000000001");
            assertThat(article("LEGIARTI000000000002").getSection().getSourceId())
                    .isEqualTo("LEGISCTA000000000002");
        });

        // --- Run 2 identique : idempotent, aucun ajout ---
        assertThat(lancer()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(articleRepository.count()).isEqualTo(5);
        assertThat(journalRepository.count()).isEqualTo(5);

        // --- Run 3 : v2 de la convention → modification, changement d'état, nouvelle version ---
        assertThat(lancerAvecConvention("kali-mini-v2.json")).isEqualTo(BatchStatus.COMPLETED);
        assertThat(articleRepository.count()).isEqualTo(6);
        assertThat(journalRepository.count()).isEqualTo(8);
        assertThat(journalRepository.findAll())
                .anyMatch(entree("KALIARTI000000000001", TypeChangement.MODIFICATION))
                .anyMatch(entree("KALIARTI000000000002", TypeChangement.CHANGEMENT_ETAT))
                .anyMatch(entree("KALIARTI000000000003", TypeChangement.NOUVELLE_VERSION));
        assertThat(article("KALIARTI000000000001").getContenuHtml()).contains("MODIFIE");
        assertThat(article("KALIARTI000000000002").getEtat()).isEqualTo(EtatJuridique.VIGUEUR_ETEN);

        // --- Run 4 : v3 → article 2 disparu, article 1 déplacé (ordre seul) ---
        assertThat(lancerAvecConvention("kali-mini-v3.json")).isEqualTo(BatchStatus.COMPLETED);
        assertThat(journalRepository.count()).isEqualTo(10);
        assertThat(journalRepository.findAll())
                .anyMatch(entree("KALIARTI000000000002", TypeChangement.DISPARITION))
                .anyMatch(entree("KALIARTI000000000001", TypeChangement.CHANGEMENT_METADONNEES));
        assertThat(article("KALIARTI000000000002").getDisparuLe()).isNotNull();
        assertThat(article("KALIARTI000000000001").getIntOrdre()).isEqualTo(5);
        assertThat(articleRepository.count()).as("jamais de suppression").isEqualTo(6);

        // --- Run 5 : v3 à nouveau → la disparition n'est pas re-journalisée ---
        assertThat(lancer()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(journalRepository.count()).isEqualTo(10);

        // --- Run 6 : retour à la v2 → réapparition de l'article 2 ---
        assertThat(lancerAvecConvention("kali-mini-v2.json")).isEqualTo(BatchStatus.COMPLETED);
        assertThat(journalRepository.count()).isEqualTo(12);
        assertThat(journalRepository.findAll())
                .anyMatch(entree("KALIARTI000000000002", TypeChangement.REAPPARITION));
        assertThat(article("KALIARTI000000000002").getDisparuLe()).isNull();
    }

    // Renvoie un BatchStatus et non un JobExecution : voir FixturesIngestion.
    private BatchStatus lancer() throws Exception {
        return jobOperatorTestUtils.startJob().getStatus();
    }

    private BatchStatus lancerAvecConvention(String fixture) throws Exception {
        copier("/fixtures/" + fixture, repertoire.resolve("kali.json"));
        return lancer();
    }

    private Article article(String sourceId) {
        return articleRepository.findBySourceId(sourceId).orElseThrow();
    }

    private static java.util.function.Predicate<JournalChangement> entree(String sourceId, TypeChangement type) {
        return j -> j.getArticleSourceId().equals(sourceId) && j.getTypeChangement() == type;
    }
}
