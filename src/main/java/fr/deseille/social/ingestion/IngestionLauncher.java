package fr.deseille.social.ingestion;

import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.batch.core.launch.JobLauncher;
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

    private final JobLauncher jobLauncher;
    private final Job ingestionJob;

    public IngestionLauncher(JobLauncher jobLauncher, Job ingestionJob) {
        this.jobLauncher = jobLauncher;
        this.ingestionJob = ingestionJob;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        jobLauncher.run(ingestionJob, new JobParametersBuilder()
                .addLong("horodatage", System.currentTimeMillis())
                .toJobParameters());
    }
}
