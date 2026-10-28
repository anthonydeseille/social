package fr.deseille.social.ingestion;

import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.job.parameters.RunIdIncrementer;
import org.springframework.batch.core.listener.StepExecutionListener;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.Step;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.ObjectMapper;

/**
 * Définit le job d'ingestion : un {@link Step} par source (Code puis Syntec), chacun
 * orienté chunk reader → processor → writer. Le job est relançable ({@link RunIdIncrementer}) ;
 * l'idempotence est assurée au niveau des données par l'upsert du {@link CorpusWriter}.
 */
@Configuration
@EnableConfigurationProperties(IngestionProperties.class)
public class IngestionBatchConfig {

    private static final int TAILLE_CHUNK = 500;

    private final JobRepository jobRepository;
    private final PlatformTransactionManager transactionManager;
    private final CorpusDownloader downloader;
    private final ObjectMapper objectMapper;
    private final IngestionProperties properties;
    private final NoeudCorpusProcessor processor;
    private final CorpusWriter writer;

    public IngestionBatchConfig(JobRepository jobRepository,
                                PlatformTransactionManager transactionManager,
                                CorpusDownloader downloader,
                                ObjectMapper objectMapper,
                                IngestionProperties properties,
                                NoeudCorpusProcessor processor,
                                CorpusWriter writer) {
        this.jobRepository = jobRepository;
        this.transactionManager = transactionManager;
        this.downloader = downloader;
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.processor = processor;
        this.writer = writer;
    }

    @Bean
    public Job ingestionJob(Step ingestionCodeStep, Step ingestionSyntecStep) {
        return new JobBuilder("ingestionJob", jobRepository)
                .incrementer(new RunIdIncrementer())
                .start(ingestionCodeStep)
                .next(ingestionSyntecStep)
                .build();
    }

    @Bean
    public Step ingestionCodeStep() {
        return stepPourSource("code");
    }

    @Bean
    public Step ingestionSyntecStep() {
        return stepPourSource("syntec");
    }

    private Step stepPourSource(String cle) {
        SourceCorpus source = source(cle);
        UnistCorpusReader reader = new UnistCorpusReader(downloader, objectMapper, source);
        return new StepBuilder("ingestion-" + cle, jobRepository)
                .<NoeudCorpus, CommandeEcriture>chunk(TAILLE_CHUNK, transactionManager)
                .reader(reader)
                .processor(processor)
                .writer(writer)
                .listener((StepExecutionListener) writer)
                .build();
    }

    private SourceCorpus source(String cle) {
        return properties.sources().stream()
                .filter(s -> s.cle().equals(cle))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Source non configurée : " + cle));
    }
}
