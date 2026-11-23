package fr.deseille.social.recherche;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class RechercheSemantiqueTest {

    private final RechercheSemantique semantique = new RechercheSemantique(null, null, true, "bge-m3", 120,
            Path.of("inutilise"));

    @Test
    void texte_vectorise_deux_derniers_titres_numero_puis_debut_de_l_article() {
        assertThat(semantique.texte("L1234-9", "Partie législative > Licenciement > Indemnité de licenciement",
                "Le salarié   titulaire\n d'un contrat"))
                .isEqualTo("Licenciement. Indemnité de licenciement. Article L1234-9 : Le salarié titulaire d'un contrat");
    }

    @Test
    void texte_tronque_a_la_longueur_choisie_et_sans_titre_ni_numero() {
        assertThat(semantique.texte(null, null, "a ".repeat(100))).hasSize(120).startsWith("a a");
    }
}
