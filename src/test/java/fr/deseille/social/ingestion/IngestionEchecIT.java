package fr.deseille.social.ingestion;

import static fr.deseille.social.ingestion.FixturesIngestion.copier;
import static fr.deseille.social.ingestion.FixturesIngestion.step;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

import fr.deseille.social.TestcontainersConfiguration;
import fr.deseille.social.corpus.ArticleRepository;
import fr.deseille.social.corpus.JournalChangementRepository;
import fr.deseille.social.corpus.TypeChangement;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.step.StepExecution;
import org.springframework.batch.test.JobOperatorTestUtils;
import org.springframework.batch.test.context.SpringBatchTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/**
 * Cas d'échec du job, avec des chunks de 2 pour que des écritures soient validées avant l'erreur.
 * Les tests sont indépendants de leur ordre d'exécution.
 */
@SpringBootTest(properties = "droits.ingestion.taille-chunk=2")
@SpringBatchTest
@Import(TestcontainersConfiguration.class)
class IngestionEchecIT {

    private static Path repertoire;

    @Autowired
    private JobOperatorTestUtils jobOperatorTestUtils;
    @Autowired
    private ArticleRepository articleRepository;
    @Autowired
    private JournalChangementRepository journalRepository;
    @MockitoSpyBean
    private DetecteurDisparition detecteurDisparition;

    @DynamicPropertySource
    static void sourcesFichierLocal(DynamicPropertyRegistry registry) throws IOException {
        repertoire = Files.createTempDirectory("ingestion-echec-it");
        copier("/fixtures/legi-mini.json", repertoire.resolve("legi.json"));
        copier("/fixtures/kali-mini-v2.json", repertoire.resolve("kali.json"));
        FixturesIngestion.declarerSources(registry, repertoire, "legi.json", "kali.json");
    }

    /**
     * {@code kali-tronque.json} s'arrête avant l'article 3 : les chunks [texte, section] et
     * [article 1, article 2] sont validés, puis la lecture échoue. Sans la garde sur le statut
     * du step, l'article 3, non lu, serait marqué disparu.
     */
    @Test
    void echec_en_cours_de_step_sans_fausse_disparition() throws Exception {
        copier("/fixtures/kali-mini-v2.json", repertoire.resolve("kali.json"));
        assertThat(jobOperatorTestUtils.startJob().getStatus()).isEqualTo(BatchStatus.COMPLETED);
        long entreesAvant = journalRepository.count();

        copier("/fixtures/kali-tronque.json", repertoire.resolve("kali.json"));
        var execution = jobOperatorTestUtils.startJob();

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.FAILED);
        StepExecution syntec = step(execution, "ingestion-syntec");
        assertThat(syntec.getWriteCount()).as("des chunks ont été validés avant l'erreur").isEqualTo(4);
        assertThat(articleRepository.findBySourceId("KALIARTI000000000003").orElseThrow().getDisparuLe()).isNull();
        assertThat(journalRepository.findAll()).noneMatch(j -> j.getTypeChangement() == TypeChangement.DISPARITION);
        assertThat(journalRepository.count()).isEqualTo(entreesAvant);
    }

    /** Une erreur pendant la détection des disparitions fait échouer le step, puis le job. */
    @Test
    void echec_de_la_detection_fait_echouer_le_step() throws Exception {
        copier("/fixtures/kali-mini-v2.json", repertoire.resolve("kali.json"));
        doThrow(new IllegalStateException("panne simulée"))
                .when(detecteurDisparition).marquerDisparitions(any(), any(), any(), any());

        var execution = jobOperatorTestUtils.startJob();

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.FAILED);
        StepExecution code = step(execution, "ingestion-code");
        assertThat(code.getStatus()).isEqualTo(BatchStatus.FAILED);
        assertThat(code.getExitStatus().getExitCode()).isEqualTo("FAILED");
        assertThat(code.getExitStatus().getExitDescription()).contains("panne simulée");
        assertThat(execution.getStepExecutions()).extracting(StepExecution::getStepName)
                .as("le job s'arrête au premier step en échec")
                .containsExactly("ingestion-code");
    }
}
