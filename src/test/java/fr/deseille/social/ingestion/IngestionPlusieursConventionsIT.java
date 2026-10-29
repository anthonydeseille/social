package fr.deseille.social.ingestion;

import static fr.deseille.social.ingestion.FixturesIngestion.copier;
import static org.assertj.core.api.Assertions.assertThat;

import fr.deseille.social.TestcontainersConfiguration;
import fr.deseille.social.corpus.TexteRepository;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.batch.core.step.StepExecution;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Le job construit un step par source configurée, dans l'ordre : ici le Code et deux conventions. */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class IngestionPlusieursConventionsIT {

    @Autowired
    private JobOperator jobOperator;
    @Autowired
    private Job ingestionJob;
    @Autowired
    private TexteRepository texteRepository;

    @DynamicPropertySource
    static void sources(DynamicPropertyRegistry registry) throws IOException {
        Path repertoire = Files.createTempDirectory("plusieurs-conventions-it");
        copier("/fixtures/legi-mini.json", repertoire.resolve("legi.json"));
        copier("/fixtures/kali-mini.json", repertoire.resolve("kali.json"));
        copier("/fixtures/kali-recherche.json", repertoire.resolve("kali-autre.json"));
        FixturesIngestion.declarerSources(registry, repertoire, "legi.json", "kali.json");
        registry.add("droits.ingestion.sources[2].cle", () -> "autre-convention");
        registry.add("droits.ingestion.sources[2].type", () -> "CONVENTION_COLLECTIVE");
        registry.add("droits.ingestion.sources[2].repo", () -> "");
        registry.add("droits.ingestion.sources[2].ref", () -> "");
        registry.add("droits.ingestion.sources[2].fichier", () -> "kali-autre.json");
    }

    @Test
    void un_step_par_source_dans_l_ordre_de_la_configuration() throws Exception {
        var execution = jobOperator.start(ingestionJob,
                new JobParametersBuilder().addLong("horodatage", System.nanoTime()).toJobParameters());

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(execution.getStepExecutions().stream().sorted(Comparator.comparing(StepExecution::getId)))
                .extracting(StepExecution::getStepName)
                .containsExactly("ingestion-code", "ingestion-syntec", "ingestion-autre-convention");
        assertThat(texteRepository.findBySourceId("KALICONT000000000001")).isPresent();
        assertThat(texteRepository.findBySourceId("KALICONT000000000007"))
                .hasValueSatisfying(t -> assertThat(t.getIdcc()).isEqualTo(7777));
    }
}
