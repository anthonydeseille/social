package fr.deseille.social.ingestion;

import static fr.deseille.social.ingestion.FixturesIngestion.copier;
import static org.assertj.core.api.Assertions.assertThat;

import fr.deseille.social.TestcontainersConfiguration;
import fr.deseille.social.corpus.ArticleRepository;
import fr.deseille.social.corpus.JournalChangement;
import fr.deseille.social.corpus.JournalChangementRepository;
import fr.deseille.social.corpus.TypeChangement;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.test.JobOperatorTestUtils;
import org.springframework.batch.test.context.SpringBatchTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Vérifie que la classification ne dépend pas du découpage en chunks (taille 2). Dans
 * {@code legi-chunks.json}, les chunks d'articles sont : [A1, A1 en double], [B, B2 même cid que B],
 * [A2 même cid que A1].
 */
@SpringBootTest(properties = "droits.ingestion.taille-chunk=2")
@SpringBatchTest
@Import(TestcontainersConfiguration.class)
class IngestionChunkIT {

    @Autowired
    private JobOperatorTestUtils jobOperatorTestUtils;
    @Autowired
    private ArticleRepository articleRepository;
    @Autowired
    private JournalChangementRepository journalRepository;

    @DynamicPropertySource
    static void sourcesFichierLocal(DynamicPropertyRegistry registry) throws IOException {
        Path repertoire = Files.createTempDirectory("ingestion-chunk-it");
        copier("/fixtures/legi-chunks.json", repertoire.resolve("legi.json"));
        copier("/fixtures/kali-mini.json", repertoire.resolve("kali.json"));
        FixturesIngestion.declarerSources(registry, repertoire, "legi.json", "kali.json");
    }

    @Test
    void classification_independante_du_decoupage_et_doublons_ignores() throws Exception {
        assertThat(jobOperatorTestUtils.startJob().getStatus()).isEqualTo(BatchStatus.COMPLETED);

        // 4 articles du Code (le doublon de A1 est ignoré) + 2 de la convention
        assertThat(articleRepository.count()).isEqualTo(6);
        Map<String, TypeChangement> journal = journalRepository.findAll().stream()
                .collect(Collectors.toMap(JournalChangement::getArticleSourceId,
                        JournalChangement::getTypeChangement));
        assertThat(journal).hasSize(6).containsAllEntriesOf(Map.of(
                "LEGIARTI000000000091", TypeChangement.CREATION,
                "LEGIARTI000000000093", TypeChangement.CREATION,
                "LEGIARTI000000000094", TypeChangement.NOUVELLE_VERSION,   // même chunk que B
                "LEGIARTI000000000092", TypeChangement.NOUVELLE_VERSION)); // autre chunk que A1

        // Second run : idempotent, aucune fausse disparition ni modification
        assertThat(jobOperatorTestUtils.startJob().getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(journalRepository.count()).isEqualTo(6);
        assertThat(articleRepository.findAll()).allMatch(a -> a.getDisparuLe() == null);
    }
}
