package fr.deseille.social.ingestion;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import fr.deseille.social.corpus.TypeTexte;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.json.JsonMapper;

class IngestionBatchConfigTest {

    private static IngestionBatchConfig config(List<SourceCorpus> sources) {
        IngestionProperties properties = new IngestionProperties("file:/tmp", "/tmp/cache", sources, 500);
        return new IngestionBatchConfig(mock(JobRepository.class), mock(PlatformTransactionManager.class),
                new CorpusDownloader(properties), JsonMapper.builder().build(), properties,
                mock(NoeudCorpusProcessor.class), mock(CorpusWriter.class));
    }

    private static SourceCorpus source(String cle) {
        return new SourceCorpus(cle, TypeTexte.CONVENTION_COLLECTIVE, "", "", cle + ".json");
    }

    @Test
    void configuration_sans_source_refusee() {
        assertThatThrownBy(() -> config(List.of()).ingestionJob())
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("Aucune source");
    }

    @Test
    void deux_sources_sur_le_meme_fichier_refusees() {
        SourceCorpus autre = new SourceCorpus("autre", TypeTexte.CONVENTION_COLLECTIVE, "", "", "syntec.json");
        assertThatThrownBy(() -> config(List.of(source("syntec"), autre)).ingestionJob())
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("même fichier : syntec.json");
    }

    @Test
    void cle_de_source_en_double_refusee() {
        assertThatThrownBy(() -> config(List.of(source("code"), source("syntec"), source("code"))).ingestionJob())
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("double : code");
    }
}
