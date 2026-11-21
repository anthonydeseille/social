package fr.deseille.social.questions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.anthonydeseille.llmeval4j.prompts.Prompts;
import org.junit.jupiter.api.Test;

class ConsignesTest {

    @Test
    void consigne_lue_sans_fin_de_ligne_finale() {
        assertThat(Consignes.lire("reformulation"))
                .startsWith("Tu prépares la recherche d'articles")
                .endsWith("(par exemple la durée et la rémunération).");
    }

    @Test
    void consigne_absente_empeche_le_demarrage() {
        assertThatThrownBy(() -> Consignes.lire("inexistante")).hasMessageContaining("/prompts/inexistante.md");
    }

    @Test
    void versions_des_deux_assistants_distinctes_pour_les_memes_consignes_de_base() {
        // Ollama ajoute exemples et format des citations : ce n'est pas le même texte envoyé
        assertThat(Prompts.version(AssistantOllama.SYSTEME_REFORMULATION))
                .isNotEqualTo(AssistantClaude.CONSIGNES.reformulation());
        assertThat(AssistantClaude.CONSIGNES.reformulation()).isEqualTo(Prompts.version(Consignes.lire("reformulation")));
    }
}
