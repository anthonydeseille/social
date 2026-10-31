package fr.deseille.social.profil;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import fr.deseille.social.corpus.EtatJuridique;
import fr.deseille.social.corpus.Texte;
import fr.deseille.social.corpus.TexteRepository;
import fr.deseille.social.corpus.TypeTexte;
import fr.deseille.social.profil.ProfilSalarie.Situation;
import fr.deseille.social.profil.ProfilService.ContexteProfil;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class ProfilServiceTest {

    private static final LocalDate AUJOURD_HUI = LocalDate.of(2026, 10, 29);

    private final TexteRepository texteRepository = mock(TexteRepository.class);
    private final ProfilService service = new ProfilService(texteRepository,
            Clock.fixed(AUJOURD_HUI.atStartOfDay(ZoneId.of("Europe/Paris")).toInstant(), ZoneId.of("Europe/Paris")));

    @BeforeEach
    void corpus() {
        when(texteRepository.findByType(TypeTexte.CODE))
                .thenReturn(List.of(new Texte("LEGITEXT000006072050", TypeTexte.CODE, "Code du travail")));
        Texte syntec = new Texte("KALICONT000005635173", TypeTexte.CONVENTION_COLLECTIVE, "Syntec");
        syntec.setIdcc(1486);
        when(texteRepository.findByIdcc(1486)).thenReturn(List.of(syntec));
        Texte denoncee = new Texte("KALICONT000000000002", TypeTexte.CONVENTION_COLLECTIVE, "Convention dénoncée");
        denoncee.setIdcc(2000);
        denoncee.setEtat(EtatJuridique.DENONCE);
        when(texteRepository.findByIdcc(2000)).thenReturn(List.of(denoncee));
    }

    private static ProfilSalarie profil(Integer idcc, LocalDate dateEntree, LocalDate dateReference) {
        return new ProfilSalarie(idcc, dateEntree, null, null, null, null, false, null, dateReference);
    }

    /** Évaluation du 30 octobre 2026 : toutes les questions, datées du 1er novembre, l'affichaient. */
    @Test
    void pas_d_avertissement_code_absent_apres_la_fin_de_sa_version_consolidee() {
        Texte code = new Texte("LEGITEXT000006072050", TypeTexte.CODE, "Code du travail");
        code.setEtat(EtatJuridique.VIGUEUR);
        code.setDateDebut(LocalDate.of(2026, 10, 8));
        code.setDateFin(LocalDate.of(2026, 11, 1));
        when(texteRepository.findByType(TypeTexte.CODE)).thenReturn(List.of(code));

        ContexteProfil contexte = service.contexte(profil(1486, null, LocalDate.of(2026, 11, 1)));

        assertThat(contexte.avertissements()).noneMatch(a -> a.contains("Code du travail"));
        assertThat(contexte.textesApplicables()).extracting(t -> t.sourceId()).contains("LEGITEXT000006072050");
    }

    @Test
    void anciennete_en_annees_et_mois_revolus_a_la_date_du_jour() {
        ContexteProfil contexte = service.contexte(profil(1486, LocalDate.of(2020, 3, 15), null));

        assertThat(contexte.profil().dateReference()).isEqualTo(AUJOURD_HUI);
        assertThat(contexte.anciennete()).isEqualTo(new ProfilService.Anciennete(6, 7, 79));
    }

    @Test
    void anciennete_a_une_date_de_reference_et_mois_non_revolu() {
        ContexteProfil contexte = service.contexte(
                profil(1486, LocalDate.of(2025, 10, 30), LocalDate.of(2026, 10, 29)));

        assertThat(contexte.anciennete()).isEqualTo(new ProfilService.Anciennete(0, 11, 11));
    }

    @Test
    void date_entree_posterieure_a_la_reference_refusee() {
        assertThatThrownBy(() -> service.contexte(
                profil(null, LocalDate.of(2027, 1, 1), LocalDate.of(2026, 10, 29))))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("postérieure");
    }

    @Test
    void convention_integree_ajoutee_aux_textes_applicables() {
        ContexteProfil contexte = service.contexte(profil(1486, null, null));

        assertThat(contexte.conventionDisponible()).isTrue();
        assertThat(contexte.textesApplicables()).extracting("sourceId")
                .containsExactly("LEGITEXT000006072050", "KALICONT000005635173");
        assertThat(contexte.avertissements()).isEmpty();
        assertThat(contexte.anciennete()).isNull();
    }

    @Test
    void convention_absente_ou_non_renseignee_signalee() {
        ContexteProfil inconnue = service.contexte(profil(9999, null, null));
        assertThat(inconnue.conventionDisponible()).isFalse();
        assertThat(inconnue.textesApplicables()).extracting("sourceId").containsExactly("LEGITEXT000006072050");
        assertThat(inconnue.avertissements()).singleElement().asString().contains("IDCC 9999");

        ContexteProfil sansIdcc = service.contexte(profil(null, null, null));
        assertThat(sansIdcc.avertissements()).singleElement().asString().contains("fiche de paie");
    }

    @Test
    void convention_integree_mais_non_applicable_exclue_et_signalee() {
        ContexteProfil contexte = service.contexte(profil(2000, null, null));

        assertThat(contexte.conventionDisponible()).isFalse();
        assertThat(contexte.textesApplicables()).extracting("sourceId").containsExactly("LEGITEXT000006072050");
        assertThat(contexte.avertissements()).singleElement().asString()
                .contains("IDCC 2000").contains("n'est pas applicable").contains("DENONCE");
    }

    @Test
    void situations_normalisees() {
        ProfilSalarie avec = new ProfilSalarie(null, null, 30, null, null, null, false, Set.of(Situation.GROSSESSE), null);
        assertThat(service.contexte(avec).profil().situations()).containsExactly(Situation.GROSSESSE);
        assertThat(service.contexte(profil(null, null, null)).profil().situations()).isEmpty();
    }
}
