package fr.deseille.social.ingestion;

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
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.test.JobLauncherTestUtils;
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
 * servies en {@code file:} depuis un répertoire temporaire : comptages, hiérarchie, dates,
 * idempotence d'un second run, puis journal des changements sur une variante de la convention.
 */
@SpringBootTest
@SpringBatchTest
@Import(TestcontainersConfiguration.class)
class IngestionJobIT {

    private static Path repertoire;

    @Autowired
    private JobLauncherTestUtils jobLauncherTestUtils;
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

        registry.add("droits.ingestion.base-url", () -> repertoire.toUri().toString());
        registry.add("droits.ingestion.cache-dir", () -> repertoire.resolve("cache").toString());
        ajouterSource(registry, 0, "code", "CODE", "legi.json");
        ajouterSource(registry, 1, "syntec", "CONVENTION_COLLECTIVE", "kali.json");
    }

    private static void ajouterSource(DynamicPropertyRegistry registry, int i, String cle,
                                      String type, String fichier) {
        registry.add("droits.ingestion.sources[" + i + "].cle", () -> cle);
        registry.add("droits.ingestion.sources[" + i + "].type", () -> type);
        registry.add("droits.ingestion.sources[" + i + "].repo", () -> "");
        registry.add("droits.ingestion.sources[" + i + "].ref", () -> "");
        registry.add("droits.ingestion.sources[" + i + "].fichier", () -> fichier);
    }

    @Test
    void ingestion_complete_idempotente_et_journal() throws Exception {
        // --- Premier run : création complète ---
        JobExecution run1 = jobLauncherTestUtils.launchJob();
        assertThat(run1.getStatus()).isEqualTo(BatchStatus.COMPLETED);

        assertThat(texteRepository.count()).isEqualTo(2);
        assertThat(sectionRepository.count()).isEqualTo(3);
        assertThat(articleRepository.count()).isEqualTo(5);
        assertThat(journalRepository.count()).isEqualTo(5);
        assertThat(journalRepository.findAll())
                .allMatch(j -> j.getTypeChangement() == TypeChangement.CREATION);

        // Dates : Code (epoch millis) parsé, convention sans date
        Article codeArt = articleRepository.findBySourceId("LEGIARTI000000000001").orElseThrow();
        assertThat(codeArt.getDateDebut()).isEqualTo(LocalDate.of(2020, 1, 1));
        assertThat(codeArt.getEtat()).isEqualTo(EtatJuridique.VIGUEUR);
        assertThat(codeArt.getContenuTexte()).isEqualTo("Contenu de l'article un.");

        Article convArt = articleRepository.findBySourceId("KALIARTI000000000001").orElseThrow();
        assertThat(convArt.getDateDebut()).isNull();
        assertThat(convArt.getContenuHtml()).contains("article A");
        assertThat(convArt.getContenuTexte()).isNull();

        Texte convention = texteRepository.findBySourceId("KALICONT000000000001").orElseThrow();
        assertThat(convention.getType()).isEqualTo(TypeTexte.CONVENTION_COLLECTIVE);
        assertThat(convention.getIdcc()).isEqualTo(9999);

        // Hiérarchie (navigation paresseuse → dans une transaction)
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.executeWithoutResult(status -> {
            Section imbriquee = sectionRepository.findBySourceId("LEGISCTA000000000002").orElseThrow();
            assertThat(imbriquee.getParent()).isNotNull();
            assertThat(imbriquee.getParent().getSourceId()).isEqualTo("LEGISCTA000000000001");
            Article art2 = articleRepository.findBySourceId("LEGIARTI000000000002").orElseThrow();
            assertThat(art2.getSection().getSourceId()).isEqualTo("LEGISCTA000000000002");
        });

        // --- Deuxième run identique : idempotent, aucun ajout ---
        assertThat(jobLauncherTestUtils.launchJob().getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(articleRepository.count()).isEqualTo(5);
        assertThat(journalRepository.count()).isEqualTo(5);

        // --- Troisième run : variante de la convention → journal des changements ---
        copier("/fixtures/kali-mini-v2.json", repertoire.resolve("kali.json"));
        assertThat(jobLauncherTestUtils.launchJob().getStatus()).isEqualTo(BatchStatus.COMPLETED);

        assertThat(articleRepository.count()).isEqualTo(6); // nouvelle version ajoutée
        assertThat(journalRepository.count()).isEqualTo(8); // +3 entrées

        List<JournalChangement> journal = journalRepository.findAll();
        assertThat(journal).anyMatch(j -> j.getArticleSourceId().equals("KALIARTI000000000001")
                && j.getTypeChangement() == TypeChangement.MODIFICATION);
        assertThat(journal).anyMatch(j -> j.getArticleSourceId().equals("KALIARTI000000000002")
                && j.getTypeChangement() == TypeChangement.CHANGEMENT_ETAT);
        assertThat(journal).anyMatch(j -> j.getArticleSourceId().equals("KALIARTI000000000003")
                && j.getTypeChangement() == TypeChangement.NOUVELLE_VERSION);

        // Modification appliquée en place sur la même version
        assertThat(articleRepository.findBySourceId("KALIARTI000000000001").orElseThrow()
                .getContenuHtml()).contains("MODIFIE");
        assertThat(articleRepository.findBySourceId("KALIARTI000000000002").orElseThrow()
                .getEtat()).isEqualTo(EtatJuridique.VIGUEUR_ETEN);
    }

    private static void copier(String ressource, Path cible) throws IOException {
        try (InputStream in = IngestionJobIT.class.getResourceAsStream(ressource)) {
            assertThat(in).as("fixture %s", ressource).isNotNull();
            Files.copy(in, cible, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
