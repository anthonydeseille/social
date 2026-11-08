package fr.deseille.social.evaluation;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class TarifTest {

    @Test
    void meme_modele_aux_deux_etapes_tarif_unique() {
        Tarif tarif = Tarif.parEtapes("claude-opus-5-5", "claude-opus-5-5");

        assertThat(tarif.connu()).isTrue();
        assertThat(tarif.cout(1_000_000, 0)).isEqualTo(4.0);
    }

    @Test
    void modeles_differents_cout_par_etape() {
        Tarif tarif = Tarif.parEtapes("claude-opus-5-5", "claude-haiku-5-5");

        assertThat(tarif.connu()).as("pas de tarif unique : coût de chaque question").isFalse();
        assertThat(tarif.modele()).isEqualTo("claude-opus-5-5 + reformulation claude-haiku-5-5");
        assertThat(tarif.libelle()).endsWith("(au prix de chaque étape)");
        assertThat(tarif.lots().libelle()).contains("+ reformulation claude-haiku-5-5");
    }
}
