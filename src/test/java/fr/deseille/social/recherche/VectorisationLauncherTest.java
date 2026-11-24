package fr.deseille.social.recherche;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

class VectorisationLauncherTest {

    @Test
    void sans_modele_de_vecteurs_le_demarrage_echoue_avec_la_propriete_a_ajouter() {
        var sansModele = new StaticListableBeanFactory().getBeanProvider(EmbeddingModel.class);
        var launcher = new VectorisationLauncher(
                new RechercheSemantique(null, sansModele, false, "bge-m3", 500, Path.of("inutilise")), null);

        assertThatThrownBy(() -> launcher.run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("--droits.semantique.fournisseur=ollama");
    }
}
