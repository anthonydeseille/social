package fr.deseille.social.questions;

import static org.assertj.core.api.Assertions.assertThat;

import fr.deseille.social.calcul.Indemnite.Motif;
import fr.deseille.social.calcul.Indemnite.Resultat;
import fr.deseille.social.calcul.Indemnite.Retenue;
import fr.deseille.social.profil.ProfilSalarie;
import fr.deseille.social.profil.ProfilSalarie.Statut;
import fr.deseille.social.profil.ProfilSalarie.TypeContrat;
import fr.deseille.social.profil.ProfilService.ContexteProfil;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

/** Calculs déclenchés par une question, avec les vrais barèmes (Syntec compris). */
@SpringJUnitConfig(CalculsQuestionsTest.Barèmes.class)
class CalculsQuestionsTest {

    @Configuration
    @ComponentScan("fr.deseille.social.calcul")
    @Import(CalculsQuestions.class)
    static class Barèmes {
    }

    /** Profil de la question d'évaluation indemnite-licenciement-cadre-7ans. */
    private static final ProfilSalarie CADRE_SYNTEC = new ProfilSalarie(1486, LocalDate.of(2019, 10, 1), null,
            Statut.CADRE, null, TypeContrat.CDI, false, null, LocalDate.of(2026, 11, 1));

    @Autowired
    private CalculsQuestions calculs;

    private static ContexteProfil contexte(ProfilSalarie profil) {
        return new ContexteProfil(profil, null, List.of(), true, List.of());
    }

    @Test
    void question_sans_indemnite_de_licenciement_sans_calcul() {
        assertThat(calculs.pour("Combien dure ma période d'essai ?", contexte(CADRE_SYNTEC))).isEmpty();
        assertThat(calculs.pour("Ai-je droit à une indemnité de rupture conventionnelle ?", contexte(CADRE_SYNTEC)))
                .as("indemnité sans licenciement").isEmpty();
        assertThat(calculs.pour("Mon licenciement est-il valable ?", contexte(CADRE_SYNTEC)))
                .as("licenciement sans indemnité").isEmpty();
    }

    @Test
    void indemnite_cadre_syntec_7_ans_calculee_par_le_code() {
        CalculQuestion calcul = calculs.pour("Je suis licencié pour un motif économique. Combien vais-je toucher "
                + "d'INDEMNITE ?", contexte(CADRE_SYNTEC)).orElseThrow();

        Resultat r = calcul.resultat();
        assertThat(r.droit()).isTrue();
        assertThat(r.retenue()).isEqualTo(Retenue.CONVENTIONNELLE);
        assertThat(r.moisRetenus()).isEqualByComparingTo(new BigDecimal("2.36"));
        assertThat(r.legale().mois()).isEqualByComparingTo(new BigDecimal("1.77"));
        assertThat(r.hypotheses().getFirst()).contains("date de référence du profil (2026-11-01)");
        assertThat(r.hypotheses()).as("motif lu dans la question").noneMatch(h -> h.contains("motif personnel"))
                .anyMatch(h -> h.contains("en mois de salaire"));
        assertThat(r.avertissements()).contains("Règles de calcul en cours de validation juridique.");

        assertThat(calcul.description()).contains("reprends ces chiffres tels quels", "2,36 mois de salaire",
                "1,77 mois de salaire", "indemnité conventionnelle");
    }

    @Test
    void motif_lu_dans_la_question_y_compris_une_faute_niee() {
        assertThat(CalculsQuestions.motif("licencie pour faute grave")).isEqualTo(Motif.FAUTE_GRAVE);
        assertThat(CalculsQuestions.motif("licenciement pour faute lourde")).isEqualTo(Motif.FAUTE_LOURDE);
        assertThat(CalculsQuestions.motif("licencie, sans faute grave")).isEqualTo(Motif.LICENCIEMENT_PERSONNEL);
        assertThat(CalculsQuestions.motif("licencie mais pas pour une faute grave"))
                .isEqualTo(Motif.LICENCIEMENT_PERSONNEL);
        assertThat(CalculsQuestions.motif("licenciement economique")).isEqualTo(Motif.LICENCIEMENT_ECONOMIQUE);
        assertThat(CalculsQuestions.motif("je suis licencie")).isEqualTo(Motif.LICENCIEMENT_PERSONNEL);
    }

    @Test
    void faute_grave_pas_d_indemnite() {
        CalculQuestion calcul = calculs.pour("Licencié pour faute grave, ai-je droit à une indemnité ?",
                contexte(CADRE_SYNTEC)).orElseThrow();

        assertThat(calcul.resultat().droit()).isFalse();
        assertThat(calcul.description()).contains("Pas d'indemnité : Pas d'indemnité de licenciement en cas de faute grave");
    }

    @Test
    void motif_non_precise_suppose_personnel() {
        CalculQuestion calcul = calculs.pour("Quelle indemnité de licenciement ?", contexte(CADRE_SYNTEC)).orElseThrow();

        assertThat(calcul.resultat().hypotheses()).anyMatch(h -> h.startsWith("Licenciement supposé pour motif personnel"));
    }

    @Test
    void sans_date_d_entree_calcul_impossible_explique_au_modele() {
        ProfilSalarie sansEntree = new ProfilSalarie(1486, null, null, Statut.CADRE, null, TypeContrat.CDI, false, null,
                LocalDate.of(2026, 11, 1));

        CalculQuestion calcul = calculs.pour("Quelle indemnité de licenciement ?", contexte(sansEntree)).orElseThrow();

        assertThat(calcul.resultat()).isNull();
        assertThat(calcul.description()).contains("impossible", "date d'entrée", "sans faire le calcul toi-même");
    }
}
