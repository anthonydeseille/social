package fr.deseille.social.questions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import fr.deseille.social.calcul.CalculIndemniteService;
import fr.deseille.social.consultation.ConsultationService;
import fr.deseille.social.consultation.VuesConsultation.ArticleDetail;
import fr.deseille.social.consultation.VuesConsultation.TexteResume;
import fr.deseille.social.corpus.EtatJuridique;
import fr.deseille.social.corpus.TypeTexte;
import fr.deseille.social.profil.ProfilSalarie;
import fr.deseille.social.profil.ProfilService;
import fr.deseille.social.profil.ProfilService.ContexteProfil;
import fr.deseille.social.questions.Assistant.Passage;
import fr.deseille.social.questions.Assistant.Reponse;
import fr.deseille.social.questions.Assistant.VersionsConsignes;
import fr.deseille.social.questions.QuestionService.ReponseQuestion;
import fr.deseille.social.recherche.RechercheService;
import fr.deseille.social.recherche.RechercheService.Resultat;
import java.time.LocalDate;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.mockito.ArgumentCaptor;

class QuestionServiceTest {

    private static final TexteResume CODE = new TexteResume("LEGITEXT1", TypeTexte.CODE, null, "Code", null);
    /** Date de référence du profil, distincte d'aujourd'hui : c'est elle qui doit servir partout. */
    private static final LocalDate DATE = LocalDate.of(2023, 12, 31);
    private static final ProfilSalarie PROFIL =
            new ProfilSalarie(1486, null, null, null, null, null, false, null, DATE);
    private static final ContexteProfil CONTEXTE =
            new ContexteProfil(PROFIL, null, List.of(CODE), true, List.of("avertissement du profil"));

    private final ProfilService profilService = mock(ProfilService.class);
    private final RechercheService rechercheService = mock(RechercheService.class);
    private final ConsultationService consultationService = mock(ConsultationService.class);
    private static final VersionsConsignes CONSIGNES = new VersionsConsignes("aaaa1111", "bbbb2222");

    private final Assistant assistant = assistant();
    private final SimpleMeterRegistry registre = new SimpleMeterRegistry();
    private final QuestionService service = new QuestionService(profilService, rechercheService, consultationService,
            assistant, new MetriquesQuestions(registre), new CalculsQuestions(new CalculIndemniteService(List.of())));

    @BeforeEach
    void preparer() {
        // Méthode par défaut de l'interface, que le simulacre ne délègue pas
        when(assistant.articlesMax()).thenReturn(8);
        when(profilService.contexte(PROFIL)).thenReturn(CONTEXTE);
        when(consultationService.article(anyString(), eq(DATE))).thenAnswer(i -> article(i.getArgument(0)));
    }

    /** Simulacre prêt avant la construction du service, qui publie les versions des consignes. */
    private static Assistant assistant() {
        Assistant assistant = mock(Assistant.class);
        when(assistant.modele()).thenReturn("modele-test");
        when(assistant.consignes()).thenReturn(CONSIGNES);
        return assistant;
    }

    private static Resultat resultat(String id, double pertinence) {
        return new Resultat(id, "N" + id, EtatJuridique.VIGUEUR, true, "LEGITEXT1", TypeTexte.CODE, "Code", "…", pertinence);
    }

    private static ArticleDetail article(String id) {
        return new ArticleDetail(id, null, "N" + id, EtatJuridique.VIGUEUR, true, null, null, "texte", null, null,
                null, CODE, List.of());
    }

    @Test
    void fusionne_les_requetes_garde_la_meilleure_pertinence_et_redige() {
        when(assistant.reformuler(eq("question"), any())).thenReturn(List.of("période essai", "essai cadre"));
        when(rechercheService.rechercher("période essai", 1486, true, 10, DATE))
                .thenReturn(List.of(resultat("A", 0.2), resultat("B", 0.9)));
        when(rechercheService.rechercher("essai cadre", 1486, true, 10, DATE))
                .thenReturn(List.of(resultat("A", 1.5), resultat("C", 0.1)));
        when(assistant.rediger(eq("question"), eq(CONTEXTE), any(), any()))
                .thenReturn(new Reponse(List.of(new Passage("réponse", List.of())), false, false));

        ReponseQuestion reponse = service.repondre("question", PROFIL);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ArticleDetail>> articles = ArgumentCaptor.forClass(List.class);
        verify(assistant).rediger(eq("question"), eq(CONTEXTE), articles.capture(), any());
        assertThat(articles.getValue()).extracting(ArticleDetail::sourceId).containsExactly("A", "B", "C");
        assertThat(reponse.requetes()).containsExactly("période essai", "essai cadre");
        assertThat(reponse.sources()).extracting(QuestionService.Source::articleSourceId).containsExactly("A", "B", "C");
        assertThat(reponse.passages()).extracting(Passage::texte).containsExactly("réponse");
        assertThat(reponse.avertissements()).containsExactly("avertissement du profil");

        assertThat(registre.counter("droits.questions", "issue", "repondue").count()).isEqualTo(1.0);
        assertThat(registre.find("droits.questions.etape").timers()).extracting(t -> t.getId().getTag("etape"))
                .containsExactlyInAnyOrder("reformulation", "recherche", "redaction");
        assertThat(registre.counter("droits.citations", "verification", "verifiee").count()).isZero();
    }

    @Test
    void indemnite_de_licenciement_calculee_par_le_code_transmise_et_ses_chiffres_connus() {
        ProfilSalarie profil = new ProfilSalarie(null, LocalDate.of(2016, 12, 31), null, null, null, null, false, null, DATE);
        ContexteProfil contexte = new ContexteProfil(profil, null, List.of(CODE), false, List.of());
        when(profilService.contexte(profil)).thenReturn(contexte);
        when(assistant.reformuler(anyString(), any())).thenReturn(List.of("q"));
        when(rechercheService.rechercher("q", null, true, 10, DATE)).thenReturn(List.of(resultat("A", 1)));
        // 7 ans : 7 × 1/4 = 1,75 mois au minimum légal, chiffre absent de l'article « texte »
        when(assistant.rediger(anyString(), any(), any(), any()))
                .thenReturn(new Reponse(List.of(new Passage("Environ 1,75 mois de salaire, voire 9 mois.", List.of())),
                        false, false));

        ReponseQuestion reponse = service.repondre("Quelle indemnité de licenciement ?", profil);

        ArgumentCaptor<CalculQuestion> calcul = ArgumentCaptor.forClass(CalculQuestion.class);
        verify(assistant).rediger(anyString(), any(), any(), calcul.capture());
        assertThat(calcul.getValue().resultat().moisRetenus()).isEqualByComparingTo("1.75");
        assertThat(reponse.calcul()).isEqualTo(calcul.getValue());
        assertThat(reponse.chiffresNonRetrouves()).as("1,75 vient du calculateur, 9 de nulle part").containsExactly("9");
        assertThat(reponse.avertissements()).anyMatch(a -> a.contains("viennent du calculateur"));
        assertThat(registre.counter("droits.questions.calculs", "issue", "fait").count()).isEqualTo(1.0);
    }

    @Test
    void question_sans_calcul_rien_de_transmis() {
        when(assistant.reformuler(anyString(), any())).thenReturn(List.of("q"));
        when(rechercheService.rechercher("q", 1486, true, 10, DATE)).thenReturn(List.of(resultat("A", 1)));
        when(assistant.rediger(anyString(), any(), any(), any())).thenReturn(new Reponse(List.of(), false, false));

        ReponseQuestion reponse = service.repondre("Combien dure ma période d'essai ?", PROFIL);

        verify(assistant).rediger(anyString(), any(), any(), eq(null));
        assertThat(reponse.calcul()).isNull();
    }

    @Test
    void sans_article_le_calcul_garde_son_avertissement() {
        ProfilSalarie profil = new ProfilSalarie(null, LocalDate.of(2016, 12, 31), null, null, null, null, false, null, DATE);
        ContexteProfil contexte = new ContexteProfil(profil, null, List.of(CODE), false, List.of());
        when(profilService.contexte(profil)).thenReturn(contexte);
        when(assistant.reformuler(anyString(), any())).thenReturn(List.of("q"));

        ReponseQuestion reponse = service.repondre("Quelle indemnité de licenciement ?", profil);

        assertThat(reponse.calcul()).isNotNull();
        assertThat(reponse.avertissements()).anyMatch(a -> a.contains("viennent du calculateur"));
    }

    @Test
    void erreur_imprevue_ou_requete_invalide_comptees_puis_propagees() {
        when(assistant.reformuler(anyString(), any())).thenReturn(List.of("q"));
        when(rechercheService.rechercher("q", 1486, true, 10, DATE)).thenThrow(new IllegalStateException("base indisponible"));
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.repondre("question", PROFIL))
                .isInstanceOf(IllegalStateException.class);
        assertThat(registre.counter("droits.questions", "issue", "erreur").count()).isEqualTo(1.0);

        when(profilService.contexte(PROFIL)).thenThrow(new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.BAD_REQUEST, "dates incohérentes"));
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.repondre("question", PROFIL))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        assertThat(registre.counter("droits.questions", "issue", "invalide").count()).isEqualTo(1.0);
    }

    @Test
    void citations_reparties_entre_verifiees_et_non_verifiees() {
        when(assistant.reformuler(anyString(), any())).thenReturn(List.of("q"));
        when(rechercheService.rechercher("q", 1486, true, 10, DATE)).thenReturn(List.of(resultat("A", 1)));
        when(assistant.rediger(anyString(), any(), any(), any())).thenReturn(new Reponse(List.of(new Passage("…", List.of(
                new fr.deseille.social.questions.Assistant.Citation("A", "NA", "texte"),
                new fr.deseille.social.questions.Assistant.Citation("A", "NA", "extrait inventé")))), false, false));

        service.repondre("question", PROFIL);

        assertThat(registre.counter("droits.citations", "verification", "verifiee").count()).isEqualTo(1.0);
        assertThat(registre.counter("droits.citations", "verification", "non_verifiee").count()).isEqualTo(1.0);
    }

    @Test
    void modele_indisponible_compte_puis_propage() {
        when(assistant.reformuler(anyString(), any())).thenReturn(List.of("q"));
        when(rechercheService.rechercher("q", 1486, true, 10, DATE)).thenReturn(List.of(resultat("A", 1)));
        when(assistant.rediger(anyString(), any(), any(), any()))
                .thenThrow(new AssistantIndisponibleException(new IllegalStateException("panne")));

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.repondre("question", PROFIL))
                .isInstanceOf(AssistantIndisponibleException.class);
        assertThat(registre.counter("droits.questions", "issue", "indisponible").count()).isEqualTo(1.0);
    }

    @Test
    void au_plus_autant_d_articles_que_l_assistant_en_accepte_les_plus_pertinents() {
        when(assistant.reformuler(anyString(), any())).thenReturn(List.of("q"));
        when(rechercheService.rechercher("q", 1486, true, 10, DATE))
                .thenReturn(IntStream.range(0, 10).mapToObj(i -> resultat("A" + i, 10 - i)).toList());
        when(assistant.rediger(anyString(), any(), any(), any())).thenReturn(new Reponse(List.of(), false, false));

        assertThat(service.repondre("question", PROFIL).sources()).hasSize(8);
        when(assistant.articlesMax()).thenReturn(4);
        assertThat(service.repondre("question", PROFIL).sources()).extracting(QuestionService.Source::articleSourceId)
                .containsExactly("A0", "A1", "A2", "A3");
    }

    @Test
    void deux_places_sur_huit_reservees_aux_articles_pertinents_de_la_convention() {
        when(assistant.reformuler(anyString(), any())).thenReturn(List.of("q"));
        // Code : 10 articles de 2,0 à 1,1 ; le 8e retenu sans réserve vaut 1,3, d'où un seuil de 0,65
        when(rechercheService.rechercher("q", 1486, true, 10, DATE))
                .thenReturn(IntStream.range(0, 10).mapToObj(i -> resultat("C" + i, 2.0 - i * 0.1)).toList());
        when(rechercheService.rechercherConvention("q", 1486, 10, DATE))
                .thenReturn(List.of(resultat("K1", 1.0), resultat("K2", 0.7), resultat("K3", 0.69)));
        when(assistant.rediger(anyString(), any(), any(), any())).thenReturn(new Reponse(List.of(), false, false));

        assertThat(service.repondre("question", PROFIL).sources()).extracting(QuestionService.Source::articleSourceId)
                .as("K1 et K2 réservés ; K3 n'a pas de place ; les 6 meilleurs du Code, par pertinence")
                .containsExactly("C0", "C1", "C2", "C3", "C4", "C5", "K1", "K2");
    }

    @Test
    void article_de_la_convention_trop_peu_pertinent_n_evince_pas_le_code() {
        when(assistant.reformuler(anyString(), any())).thenReturn(List.of("q"));
        when(rechercheService.rechercher("q", 1486, true, 10, DATE))
                .thenReturn(IntStream.range(0, 10).mapToObj(i -> resultat("C" + i, 2.0 - i * 0.1)).toList());
        when(rechercheService.rechercherConvention("q", 1486, 10, DATE)).thenReturn(List.of(resultat("K1", 0.6)));
        when(assistant.rediger(anyString(), any(), any(), any())).thenReturn(new Reponse(List.of(), false, false));

        assertThat(service.repondre("question", PROFIL).sources()).extracting(QuestionService.Source::articleSourceId)
                .as("0,6 < 0,65 : pas de place réservée").containsExactly("C0", "C1", "C2", "C3", "C4", "C5", "C6", "C7");
    }

    @Test
    void une_place_sur_quatre_pour_un_modele_local_seuil_inclus() {
        when(assistant.articlesMax()).thenReturn(4);
        when(assistant.reformuler(anyString(), any())).thenReturn(List.of("q"));
        // Code : 2,0 à 1,1 ; le 4e retenu sans réserve vaut 1,7, d'où un seuil de 0,85
        when(rechercheService.rechercher("q", 1486, true, 10, DATE))
                .thenReturn(IntStream.range(0, 10).mapToObj(i -> resultat("C" + i, 2.0 - i * 0.1)).toList());
        when(rechercheService.rechercherConvention("q", 1486, 10, DATE))
                .thenReturn(List.of(resultat("K1", 0.85), resultat("K2", 0.85)));
        when(assistant.rediger(anyString(), any(), any(), any())).thenReturn(new Reponse(List.of(), false, false));

        assertThat(service.repondre("question", PROFIL).sources()).extracting(QuestionService.Source::articleSourceId)
                .as("une seule place réservée ; 0,85 atteint exactement le seuil").containsExactly("C0", "C1", "C2", "K1");
    }

    @Test
    void recherche_semantique_active_deux_articles_trouves_par_le_sens_remplacent_les_derniers() {
        when(assistant.reformuler(anyString(), any())).thenReturn(List.of("q"));
        when(rechercheService.rechercher("q", 1486, true, 10, DATE))
                .thenReturn(IntStream.range(0, 10).mapToObj(i -> resultat("C" + i, 2.0 - i * 0.1)).toList());
        when(rechercheService.rechercherConvention("q", 1486, 10, DATE)).thenReturn(List.of(resultat("K1", 1.0)));
        when(rechercheService.semantiqueActive()).thenReturn(true);
        when(rechercheService.rechercherSemantique("Ma question ?", 1486, 10, DATE)).thenReturn(List.of(
                resultat("C0", 0.9), resultat("S1", 0.8), resultat("S2", 0.6), resultat("S3", 0.55), resultat("S4", 0.4)));
        when(assistant.rediger(anyString(), any(), any(), any())).thenReturn(new Reponse(List.of(), false, false));

        assertThat(service.repondre("Ma question ?", PROFIL).sources()).extracting(QuestionService.Source::articleSourceId)
                .as("C0 déjà retenu ; S1 et S2 prennent les places de C5 et C6 ; K1, réservé, reste")
                .containsExactly("C0", "C1", "C2", "C3", "C4", "K1", "S1", "S2");
    }

    @Test
    void recherche_semantique_seuil_de_similarite_et_inactive_par_defaut() {
        when(assistant.reformuler(anyString(), any())).thenReturn(List.of("q"));
        when(rechercheService.rechercher("q", 1486, true, 10, DATE))
                .thenReturn(IntStream.range(0, 10).mapToObj(i -> resultat("C" + i, 2.0 - i * 0.1)).toList());
        when(rechercheService.rechercherSemantique("Ma question ?", 1486, 10, DATE))
                .thenReturn(List.of(resultat("S1", 0.4)));
        when(assistant.rediger(anyString(), any(), any(), any())).thenReturn(new Reponse(List.of(), false, false));

        assertThat(service.repondre("Ma question ?", PROFIL).sources()).as("inactive").hasSize(8)
                .noneMatch(s -> s.articleSourceId().startsWith("S"));
        verify(rechercheService, never()).rechercherSemantique(anyString(), any(), org.mockito.ArgumentMatchers.anyInt(), any());

        when(rechercheService.semantiqueActive()).thenReturn(true);
        assertThat(service.repondre("Ma question ?", PROFIL).sources()).as("0,4 sous le seuil de 0,5")
                .noneMatch(s -> s.articleSourceId().startsWith("S"));
    }

    @Test
    void sans_convention_pas_de_recherche_dans_la_convention() {
        ProfilSalarie profil = new ProfilSalarie(null, null, null, null, null, null, false, null, DATE);
        when(profilService.contexte(profil)).thenReturn(new ContexteProfil(profil, null, List.of(CODE), false, List.of()));
        when(assistant.reformuler(anyString(), any())).thenReturn(List.of("q"));
        when(rechercheService.rechercher("q", null, true, 10, DATE)).thenReturn(List.of(resultat("A", 1)));
        when(assistant.rediger(anyString(), any(), any(), any())).thenReturn(new Reponse(List.of(), false, false));

        service.repondre("question", profil);

        verify(rechercheService, never()).rechercherConvention(anyString(), org.mockito.ArgumentMatchers.anyInt(), any(Integer.class), any());
    }

    @Test
    void sans_article_trouve_pas_de_redaction() {
        when(assistant.reformuler(anyString(), any())).thenReturn(List.of("introuvable"));
        when(rechercheService.rechercher("introuvable", 1486, true, 10, DATE)).thenReturn(List.of());

        ReponseQuestion reponse = service.repondre("question", PROFIL);

        verify(assistant, never()).rediger(anyString(), any(), any(), any());
        assertThat(registre.counter("droits.questions", "issue", "aucun_article").count()).isEqualTo(1.0);
        assertThat(reponse.passages()).isEmpty();
        assertThat(reponse.avertissements()).last().asString().contains("Aucun article pertinent");
    }

    @Test
    void versions_des_consignes_dans_la_reponse_et_les_metriques() {
        when(assistant.reformuler(anyString(), any())).thenReturn(List.of("introuvable"));

        assertThat(service.repondre("question", PROFIL).consignes()).isEqualTo(CONSIGNES);
        assertThat(registre.get("droits.assistant.consignes").tag("modele", "modele-test")
                .tag("reformulation", "aaaa1111").tag("redaction", "bbbb2222").gauge().value()).isEqualTo(1.0);
    }

    @Test
    void reponse_tronquee_signalee() {
        when(assistant.reformuler(anyString(), any())).thenReturn(List.of("q"));
        when(rechercheService.rechercher("q", 1486, true, 10, DATE)).thenReturn(List.of(resultat("A", 1)));
        when(assistant.rediger(anyString(), any(), any(), any()))
                .thenReturn(new Reponse(List.of(new Passage("début", List.of())), false, true));

        assertThat(service.repondre("question", PROFIL).avertissements()).last().asString().contains("incomplète");
        assertThat(registre.counter("droits.questions", "issue", "tronquee").count()).isEqualTo(1.0);
    }

    @Test
    void chiffre_absent_des_sources_signale_sans_etre_retire() {
        when(assistant.reformuler(anyString(), any())).thenReturn(List.of("q"));
        when(rechercheService.rechercher("q", 1486, true, 10, DATE)).thenReturn(List.of(resultat("A", 1)));
        // L'article simulé contient « texte » : 4 200 n'a pas de source, 3 vient de la question
        when(assistant.rediger(anyString(), any(), any(), any()))
                .thenReturn(new Reponse(List.of(new Passage("Vous toucherez 4 200 € pour 3 ans.", List.of())), false, false));

        ReponseQuestion reponse = service.repondre("Que vais-je toucher après 3 ans ?", PROFIL);

        assertThat(reponse.chiffresNonRetrouves()).containsExactly("4 200");
        assertThat(reponse.passages().getFirst().texte()).contains("4 200");
        assertThat(reponse.avertissements()).anyMatch(a -> a.startsWith("1 chiffre(s) absent(s)") && a.contains("4 200"));
        assertThat(registre.counter("droits.chiffres_non_retrouves").count()).isEqualTo(1.0);
    }

    @Test
    void refus_du_modele_signale() {
        when(assistant.reformuler(anyString(), any())).thenReturn(List.of("q"));
        when(rechercheService.rechercher("q", 1486, true, 10, DATE)).thenReturn(List.of(resultat("A", 1)));
        when(assistant.rediger(anyString(), any(), any(), any())).thenReturn(new Reponse(List.of(), true, false));

        ReponseQuestion reponse = service.repondre("question", PROFIL);

        assertThat(reponse.refus()).isTrue();
        assertThat(registre.counter("droits.questions", "issue", "refus").count()).isEqualTo(1.0);
        assertThat(reponse.avertissements()).last().asString().contains("n'a pas pu être traitée");
    }
}
