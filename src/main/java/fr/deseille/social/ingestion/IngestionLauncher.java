package fr.deseille.social.ingestion;

import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Lance le job d'ingestion au démarrage uniquement si {@code droits.ingestion.run=true}
 * (le job n'est pas auto-exécuté par Spring Boot). Pratique en développement :
 * {@code ./mvnw spring-boot:run -Dspring-boot.run.arguments=--droits.ingestion.run=true}.
 */
@Component
@ConditionalOnProperty(name = "droits.ingestion.run", havingValue = "true")
public class IngestionLauncher implements ApplicationRunner {

    private final JobOperator jobOperator;
    private final Job ingestionJob;

    public IngestionLauncher(JobOperator jobOperator, Job ingestionJob) {
        this.jobOperator = jobOperator;
        this.ingestionJob = ingestionJob;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        JobExecution execution = jobOperator.start(ingestionJob, new JobParametersBuilder()
                .addLong("horodatage", System.currentTimeMillis())
                .toJobParameters());
        if (execution.getStatus() != BatchStatus.COMPLETED) {
            // Fait échouer le démarrage : le processus se termine avec un code de sortie non nul.
            throw new IllegalStateException("Ingestion terminée en " + execution.getStatus()
                    + " : " + execution.getAllFailureExceptions());
        }
    }
}
