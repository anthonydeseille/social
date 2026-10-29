package fr.deseille.social.calcul;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import fr.deseille.social.calcul.Indemnite.Demande;
import fr.deseille.social.calcul.Indemnite.Motif;
import fr.deseille.social.calcul.Indemnite.Resultat;
import fr.deseille.social.calcul.Indemnite.Retenue;
import fr.deseille.social.calcul.Indemnite.Source;
import fr.deseille.social.profil.ProfilSalarie;
import fr.deseille.social.profil.ProfilSalarie.Statut;
import fr.deseille.social.profil.ProfilSalarie.TypeContrat;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

/**
 * Cas tirés des textes (L1234-9, R1234-1, R1234-2, Syntec 4.5) et du jeu d'évaluation. Les montants
 * attendus sont calculés à la main dans chaque test.
 */
class CalculIndemniteServiceTest {

    private static final LocalDate NOTIFICATION = LocalDate.of(2026, 11, 2);

    private final CalculIndemniteService service = new CalculIndemniteService(List.of(new BaremeSyntec()));

    private static ProfilSalarie profil(Integer idcc, Statut statut, TypeContrat contrat, LocalDate entree) {
        return new ProfilSalarie(idcc, entree, null, statut, null, contrat, false, null, null);
    }

    private Resultat calculer(ProfilSalarie profil, Motif motif, BigDecimal salaire) {
        return service.calculer(new Demande(profil, NOTIFICATION, null, motif, salaire));
    }

    private static BigDecimal mois(String valeur) {
        return new BigDecimal(valeur);
    }

    @Test
    void cadre_syntec_7_ans_conventionnelle_plus_favorable() {
        // 7 ans et 1 mois = 85 mois = 7,0833 années
        Resultat r = calculer(profil(1486, Statut.CADRE, TypeContrat.CDI, LocalDate.of(2019, 10, 1)),
                Motif.LICENCIEMENT_ECONOMIQUE, new BigDecimal("4500"));

        assertThat(r.droit()).isTrue();
        assertThat(r.anneesAnciennete()).isEqualTo(7);
        assertThat(r.moisAnciennete()).isEqualTo(1);
        assertThat(r.legale().mois()).isEqualByComparingTo(mois("1.77"));          // 7,0833 × 1/4
        assertThat(r.conventionnelle().mois()).isEqualByComparingTo(mois("2.36")); // 7,0833 × 1/3
        assertThat(r.retenue()).isEqualTo(Retenue.CONVENTIONNELLE);
        assertThat(r.moisRetenus()).isEqualByComparingTo(mois("2.36"));
        assertThat(r.montantBrut()).isEqualByComparingTo(new BigDecimal("10625.00"));    // 2,3611… × 4500, valeur exacte
        assertThat(r.conventionnelle().sources()).contains(new Source("4.5", BaremeSyntec.SYNTEC));
        assertThat(r.legale().sources()).extracting(Source::num).containsExactly("L1234-9", "R1234-1", "R1234-2", "R1234-4");
    }

    @Test
    void etam_syntec_12_ans_identique_au_legal_en_lecture_par_tranche() {
        // 12 ans et 1 mois = 12,0833 années : 10 × 1/4 + 2,0833 × 1/3 = 2,5 + 0,6944
        Resultat r = calculer(profil(1486, Statut.NON_CADRE, TypeContrat.CDI, LocalDate.of(2014, 10, 1)),
                Motif.LICENCIEMENT_PERSONNEL, null);

        assertThat(r.legale().mois()).isEqualByComparingTo(mois("3.19"));
        assertThat(r.conventionnelle().mois()).isEqualByComparingTo(mois("3.19"));
        assertThat(r.retenue()).isEqualTo(Retenue.IDENTIQUES);
        assertThat(r.montantBrut()).isNull();
        assertThat(r.legale().detail()).anyMatch(l -> l.startsWith("10 premières années × 1/4"));
    }

    @Test
    void montant_en_euros_calcule_sur_la_valeur_exacte_et_non_arrondie() {
        // 12 ans et 1 mois : 3,19444… mois exactement ; × 5000 = 15 972,22 € (et non 3,19 × 5000 = 15 950 €)
        Resultat r = calculer(profil(1486, Statut.NON_CADRE, TypeContrat.CDI, LocalDate.of(2014, 10, 1)),
                Motif.LICENCIEMENT_PERSONNEL, new BigDecimal("5000"));

        assertThat(r.moisRetenus()).isEqualByComparingTo(mois("3.19"));
        assertThat(r.montantBrut()).isEqualByComparingTo(new BigDecimal("15972.22"));
    }

    @Test
    void motif_economique_ou_personnel_meme_montant() {
        ProfilSalarie p = profil(1486, Statut.CADRE, TypeContrat.CDI, LocalDate.of(2019, 10, 1));

        assertThat(calculer(p, Motif.LICENCIEMENT_ECONOMIQUE, null).moisRetenus())
                .isEqualByComparingTo(calculer(p, Motif.LICENCIEMENT_PERSONNEL, null).moisRetenus());
    }

    /**
     * À VALIDER (docs/regles-indemnite-licenciement.md, question 1) : en lecture « toutes les années »,
     * le barème cadres saute de 0,48 à 0,67 mois au passage des 2 ans ; en lecture par tranche, il
     * passerait de 0,48 à 0,50. Ce test fige le comportement actuel pour qu'il ne passe pas pour voulu.
     */
    @Test
    void saut_du_bareme_cadres_a_2_ans_en_lecture_toutes_les_annees() {
        Resultat avant = calculer(profil(1486, Statut.CADRE, TypeContrat.CDI, NOTIFICATION.minusMonths(23)),
                Motif.LICENCIEMENT_PERSONNEL, null);
        Resultat apres = calculer(profil(1486, Statut.CADRE, TypeContrat.CDI, NOTIFICATION.minusMonths(24)),
                Motif.LICENCIEMENT_PERSONNEL, null);

        assertThat(avant.conventionnelle().mois()).isEqualByComparingTo(mois("0.48"));
        assertThat(apres.conventionnelle().mois()).isEqualByComparingTo(mois("0.67"));
    }

    @Test
    void deux_baremes_pour_le_meme_idcc_refuses_au_demarrage() {
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> new CalculIndemniteService(List.of(new BaremeSyntec(), new BaremeSyntec())))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("IDCC 1486");
    }

    @Test
    void exactement_10_ans_premier_taux_seulement() {
        // R1234-2 : 1/4 pour les années jusqu'à dix ans
        Resultat r = calculer(profil(null, null, TypeContrat.CDI, NOTIFICATION.minusYears(10)), Motif.LICENCIEMENT_PERSONNEL, null);

        assertThat(r.legale().mois()).isEqualByComparingTo(mois("2.50"));
    }

    @Test
    void cadre_en_deca_de_2_ans_un_quart_et_a_2_ans_un_tiers_pour_toutes_les_annees() {
        Resultat unAn = calculer(profil(1486, Statut.CADRE, TypeContrat.CDI, NOTIFICATION.minusMonths(18)),
                Motif.LICENCIEMENT_PERSONNEL, null);
        assertThat(unAn.conventionnelle().mois()).isEqualByComparingTo(mois("0.38")); // 1,5 × 1/4
        assertThat(unAn.retenue()).isEqualTo(Retenue.IDENTIQUES);

        Resultat deuxAns = calculer(profil(1486, Statut.CADRE, TypeContrat.CDI, NOTIFICATION.minusYears(2)),
                Motif.LICENCIEMENT_PERSONNEL, null);
        assertThat(deuxAns.conventionnelle().mois()).isEqualByComparingTo(mois("0.67")); // 2 × 1/3
        assertThat(deuxAns.legale().mois()).isEqualByComparingTo(mois("0.50"));
    }

    @Test
    void annee_incomplete_au_prorata_des_mois_complets() {
        // 8 mois et 20 jours : 8 mois complets = 0,6667 année × 1/4
        Resultat r = calculer(profil(null, null, TypeContrat.CDI, NOTIFICATION.minusMonths(8).minusDays(20)),
                Motif.LICENCIEMENT_PERSONNEL, null);

        assertThat(r.droit()).isTrue();
        assertThat(r.legale().mois()).isEqualByComparingTo(mois("0.17"));
    }

    @Test
    void preavis_compte_dans_l_anciennete_de_calcul_mais_pas_dans_la_condition_de_8_mois() {
        ProfilSalarie p = profil(null, null, TypeContrat.CDI, NOTIFICATION.minusMonths(7));

        Resultat sansDroit = service.calculer(new Demande(p, NOTIFICATION, NOTIFICATION.plusMonths(2),
                Motif.LICENCIEMENT_PERSONNEL, null));
        assertThat(sansDroit.droit()).isFalse();
        assertThat(sansDroit.absenceDroit()).contains("8 mois", "7 mois complets");

        ProfilSalarie neufMois = profil(null, null, TypeContrat.CDI, NOTIFICATION.minusMonths(9));
        Resultat avecPreavis = service.calculer(new Demande(neufMois, NOTIFICATION, NOTIFICATION.plusMonths(3),
                Motif.LICENCIEMENT_PERSONNEL, null));
        assertThat(avecPreavis.legale().mois()).isEqualByComparingTo(mois("0.25")); // 12 mois avec le préavis
    }

    @Test
    void pas_de_droit_faute_grave_cdd_ou_moins_de_8_mois() {
        ProfilSalarie ancien = profil(1486, Statut.CADRE, TypeContrat.CDI, LocalDate.of(2015, 1, 5));
        assertThat(calculer(ancien, Motif.FAUTE_GRAVE, null).absenceDroit()).contains("faute grave");
        assertThat(calculer(ancien, Motif.FAUTE_LOURDE, null).droit()).isFalse();

        Resultat cdd = calculer(profil(null, null, TypeContrat.CDD, LocalDate.of(2025, 1, 6)), Motif.LICENCIEMENT_PERSONNEL, null);
        assertThat(cdd.absenceDroit()).contains("durée indéterminée");

        Resultat recent = calculer(profil(null, null, TypeContrat.CDI, NOTIFICATION.minusMonths(6)), Motif.LICENCIEMENT_PERSONNEL, null);
        assertThat(recent.droit()).isFalse();
        assertThat(recent.moisRetenus()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(recent.legale()).isNull();
    }

    @Test
    void convention_absente_non_integree_ou_statut_manquant_seulement_le_legal() {
        LocalDate entree = LocalDate.of(2020, 1, 6);
        assertThat(calculer(profil(null, Statut.CADRE, TypeContrat.CDI, entree), Motif.LICENCIEMENT_PERSONNEL, null)
                .avertissements()).singleElement().asString().contains("non renseignée");
        Resultat autre = calculer(profil(16, Statut.CADRE, TypeContrat.CDI, entree), Motif.LICENCIEMENT_PERSONNEL, null);
        assertThat(autre.conventionnelle()).isNull();
        assertThat(autre.retenue()).isEqualTo(Retenue.LEGALE);
        assertThat(autre.avertissements()).singleElement().asString().contains("IDCC 16");
        assertThat(calculer(profil(1486, null, TypeContrat.CDI, entree), Motif.LICENCIEMENT_PERSONNEL, null)
                .avertissements()).singleElement().asString().contains("Statut");
    }

    @Test
    void hypotheses_toujours_explicitees() {
        Resultat r = calculer(profil(1486, Statut.CADRE, null, LocalDate.of(2019, 10, 1)), Motif.LICENCIEMENT_PERSONNEL,
                new BigDecimal("3000"));

        assertThat(r.hypotheses()).anyMatch(h -> h.contains("préavis compris"))
                .anyMatch(h -> h.contains("suspension"))
                .anyMatch(h -> h.contains("supposé à durée indéterminée"))
                .anyMatch(h -> h.contains("validation juridique"))
                .anyMatch(h -> h.contains("R1234-4"));
    }

    @Test
    void dates_incoherentes_ou_date_d_entree_absente_refusees() {
        assertThatThrownBy(() -> calculer(profil(null, null, TypeContrat.CDI, null), Motif.LICENCIEMENT_PERSONNEL, null))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("date d'entrée");
        assertThatThrownBy(() -> calculer(profil(null, null, TypeContrat.CDI, NOTIFICATION.plusDays(1)),
                Motif.LICENCIEMENT_PERSONNEL, null)).isInstanceOf(ResponseStatusException.class);
        ProfilSalarie p = profil(null, null, TypeContrat.CDI, LocalDate.of(2020, 1, 6));
        assertThatThrownBy(() -> service.calculer(new Demande(p, NOTIFICATION, NOTIFICATION.minusDays(1),
                Motif.LICENCIEMENT_PERSONNEL, null))).isInstanceOf(ResponseStatusException.class);
    }
}
