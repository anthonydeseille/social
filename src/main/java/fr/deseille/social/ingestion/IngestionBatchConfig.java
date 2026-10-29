package fr.deseille.social.ingestion;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.job.builder.SimpleJobBuilder;
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
 * Définit le job d'ingestion : un {@link Step} par source configurée (Code du travail, puis les
 * conventions collectives), chacun
 * orienté chunk reader → processor → writer. Le job est relançable ({@link RunIdIncrementer}) ;
 * l'idempotence est assurée au niveau des données par l'upsert du {@link CorpusWriter}.
 */
@Configuration
@EnableConfigurationProperties(IngestionProperties.class)
public class IngestionBatchConfig {

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

    /**
     * Un step par source déclarée ({@code droits.ingestion.sources}), dans l'ordre de la
     * configuration ; le job s'arrête au premier step en échec.
     */
    @Bean
    public Job ingestionJob() {
        List<SourceCorpus> sources = properties.sources();
        if (sources == null || sources.isEmpty()) {
            throw new IllegalStateException("Aucune source d'ingestion configurée (droits.ingestion.sources)");
        }
        Set<String> cles = new HashSet<>();
        Set<String> fichiers = new HashSet<>();
        for (SourceCorpus source : sources) {
            if (!cles.add(source.cle())) {
                throw new IllegalStateException("Clé de source en double : " + source.cle());
            }
            // La seconde source écraserait la première par upsert, sans erreur
            if (!fichiers.add(source.repo() + "/" + source.fichier())) {
                throw new IllegalStateException("Deux sources lisent le même fichier : " + source.fichier());
            }
        }
        SimpleJobBuilder job = new JobBuilder("ingestionJob", jobRepository)
                .incrementer(new RunIdIncrementer())
                .start(stepPourSource(sources.getFirst()));
        for (SourceCorpus source : sources.subList(1, sources.size())) {
            job.next(stepPourSource(source));
        }
        return job.build();
    }

    private Step stepPourSource(SourceCorpus source) {
        UnistCorpusReader reader = new UnistCorpusReader(downloader, objectMapper, source);
        return new StepBuilder("ingestion-" + source.cle(), jobRepository)
                .<NoeudCorpus, CommandeEcriture>chunk(properties.tailleChunk())
                .transactionManager(transactionManager)
                .reader(reader)
                .processor(processor)
                .writer(writer)
                .listener((StepExecutionListener) writer)
                .build();
    }
}
