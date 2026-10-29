package fr.deseille.social.corpus;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class ApplicabiliteTest {

    private static final LocalDate AUJOURD_HUI = LocalDate.of(2026, 10, 29);

    private static Article article(EtatJuridique etat, LocalDate debut, LocalDate fin) {
        Article a = new Article("LEGIARTI000000000001", null);
        a.setEtat(etat);
        a.setDateDebut(debut);
        a.setDateFin(fin);
        return a;
    }

    @Test
    void article_en_vigueur_dans_ses_dates() {
        assertThat(article(EtatJuridique.VIGUEUR, LocalDate.of(2008, 5, 1), LocalDate.of(2999, 1, 1))
                .estApplicableLe(AUJOURD_HUI)).isTrue();
    }

    @Test
    void article_sans_dates_decide_par_son_etat() {
        assertThat(article(EtatJuridique.VIGUEUR_ETEN, null, null).estApplicableLe(AUJOURD_HUI)).isTrue();
        assertThat(article(EtatJuridique.ABROGE, null, null).estApplicableLe(AUJOURD_HUI)).isFalse();
        assertThat(article(null, null, null).estApplicableLe(AUJOURD_HUI)).isFalse();
    }

    @Test
    void abrogation_differee_applicable_jusqu_a_sa_date_de_fin_exclue() {
        Article a = article(EtatJuridique.ABROGE_DIFF, LocalDate.of(2020, 1, 1), AUJOURD_HUI);
        assertThat(a.estApplicableLe(AUJOURD_HUI.minusDays(1))).isTrue();
        assertThat(a.estApplicableLe(AUJOURD_HUI)).isFalse();
    }

    @Test
    void vigueur_differee_applicable_a_partir_de_sa_date_de_debut() {
        Article a = article(EtatJuridique.VIGUEUR_DIFF, AUJOURD_HUI, LocalDate.of(2999, 1, 1));
        assertThat(a.estApplicableLe(AUJOURD_HUI.minusDays(1))).isFalse();
        assertThat(a.estApplicableLe(AUJOURD_HUI)).isTrue();
        assertThat(article(EtatJuridique.VIGUEUR_DIFF, null, null).estApplicableLe(AUJOURD_HUI))
                .as("sans date de début connue").isFalse();
    }

    @Test
    void etat_en_vigueur_mais_date_de_fin_depassee() {
        assertThat(article(EtatJuridique.VIGUEUR, LocalDate.of(2020, 1, 1), LocalDate.of(2025, 1, 1))
                .estApplicableLe(AUJOURD_HUI)).isFalse();
    }

    @Test
    void article_disparu_du_dump_non_applicable() {
        Article a = article(EtatJuridique.VIGUEUR, null, null);
        a.setDisparuLe(Instant.parse("2026-10-01T00:00:00Z"));
        assertThat(a.estApplicableLe(AUJOURD_HUI)).isFalse();
    }

    @Test
    void texte_sans_etat_considere_applicable_sinon_etat_et_dates() {
        Texte convention = new Texte("KALICONT000005635173", TypeTexte.CONVENTION_COLLECTIVE, "Syntec");
        assertThat(convention.estApplicableLe(AUJOURD_HUI)).as("état inconnu (dumps KALI)").isTrue();

        convention.setEtat(EtatJuridique.DENONCE);
        assertThat(convention.estApplicableLe(AUJOURD_HUI)).isFalse();

        Texte code = new Texte("LEGITEXT000006072050", TypeTexte.CODE, "Code du travail");
        code.setEtat(EtatJuridique.VIGUEUR);
        code.setDateFin(LocalDate.of(2999, 1, 1));
        assertThat(code.estApplicableLe(AUJOURD_HUI)).isTrue();
    }
}
