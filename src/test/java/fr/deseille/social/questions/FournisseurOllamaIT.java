package fr.deseille.social.questions;

import static org.assertj.core.api.Assertions.assertThat;

import fr.deseille.social.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;

/**
 * Câblage du fournisseur local : avec {@code droits.assistant.fournisseur=ollama}, l'assistant est
 * celui d'Ollama, Claude n'est pas instancié, et les options de {@code application.yaml} sont
 * appliquées. Le démarrage ne contacte pas Ollama (aucun serveur dans ce test).
 */
@SpringBootTest(properties = {"droits.assistant.fournisseur=ollama", "spring.main.web-application-type=none"})
@Import(TestcontainersConfiguration.class)
class FournisseurOllamaIT {

    @Autowired
    private Assistant assistant;
    @Autowired
    private ApplicationContext contexte;

    @Test
    void assistant_ollama_seul_avec_les_options_de_la_configuration() {
        assertThat(assistant).isInstanceOf(AssistantOllama.class);
        assertThat(contexte.getBeanNamesForType(AssistantClaude.class)).isEmpty();
        assertThat(assistant.modele()).isEqualTo("qwen2.5:7b");
        assertThat(contexte.getBean(OllamaChatModel.class).getOptions().getNumCtx())
                .isEqualTo(12288);
        // Propriétés communes toujours lues : quota, et Claude pour le juge de l'évaluation
        assertThat(contexte.getBean(QuestionsProperties.class).quotaJournalier()).isEqualTo(200);
        assertThat(contexte.getBean(ClaudeProperties.class).modele()).isEqualTo("claude-opus-5-5");
    }
}
