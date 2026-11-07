package fr.deseille.social.questions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class ConsignesTest {

    @Test
    void version_empreinte_sha256_courte_du_texte() {
        // SHA-256("abc") = ba7816bf8f01cfea…
        assertThat(Consignes.version("abc")).isEqualTo("ba7816bf");
        assertThat(Consignes.version("abc ")).isNotEqualTo(Consignes.version("abc"));
    }

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
        assertThat(Consignes.version(AssistantOllama.SYSTEME_REFORMULATION))
                .isNotEqualTo(AssistantClaude.CONSIGNES.reformulation());
        assertThat(AssistantClaude.CONSIGNES.reformulation()).isEqualTo(Consignes.version(Consignes.lire("reformulation")));
    }
}
