package fr.deseille.social.questions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
import fr.deseille.social.questions.QuestionService.ReponseQuestion;
import fr.deseille.social.recherche.RechercheService;
import fr.deseille.social.recherche.RechercheService.Resultat;
import java.time.LocalDate;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
    private final Assistant assistant = mock(Assistant.class);
    private final QuestionService service =
            new QuestionService(profilService, rechercheService, consultationService, assistant);

    @BeforeEach
    void preparer() {
        when(profilService.contexte(PROFIL)).thenReturn(CONTEXTE);
        when(consultationService.article(anyString(), eq(DATE))).thenAnswer(i -> article(i.getArgument(0)));
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
        when(assistant.rediger(eq("question"), eq(CONTEXTE), any()))
                .thenReturn(new Reponse(List.of(new Passage("réponse", List.of())), false, false));

        ReponseQuestion reponse = service.repondre("question", PROFIL);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ArticleDetail>> articles = ArgumentCaptor.forClass(List.class);
        verify(assistant).rediger(eq("question"), eq(CONTEXTE), articles.capture());
        assertThat(articles.getValue()).extracting(ArticleDetail::sourceId).containsExactly("A", "B", "C");
        assertThat(reponse.requetes()).containsExactly("période essai", "essai cadre");
        assertThat(reponse.sources()).extracting(QuestionService.Source::articleSourceId).containsExactly("A", "B", "C");
        assertThat(reponse.passages()).extracting(Passage::texte).containsExactly("réponse");
        assertThat(reponse.avertissements()).containsExactly("avertissement du profil");
    }

    @Test
    void au_plus_huit_articles_transmis() {
        when(assistant.reformuler(anyString(), any())).thenReturn(List.of("q"));
        when(rechercheService.rechercher("q", 1486, true, 10, DATE))
                .thenReturn(IntStream.range(0, 10).mapToObj(i -> resultat("A" + i, 10 - i)).toList());
        when(assistant.rediger(anyString(), any(), any())).thenReturn(new Reponse(List.of(), false, false));

        assertThat(service.repondre("question", PROFIL).sources()).hasSize(QuestionService.ARTICLES_MAX);
    }

    @Test
    void sans_article_trouve_pas_de_redaction() {
        when(assistant.reformuler(anyString(), any())).thenReturn(List.of("introuvable"));
        when(rechercheService.rechercher("introuvable", 1486, true, 10, DATE)).thenReturn(List.of());

        ReponseQuestion reponse = service.repondre("question", PROFIL);

        verify(assistant, never()).rediger(anyString(), any(), any());
        assertThat(reponse.passages()).isEmpty();
        assertThat(reponse.avertissements()).last().asString().contains("Aucun article pertinent");
    }

    @Test
    void reponse_tronquee_signalee() {
        when(assistant.reformuler(anyString(), any())).thenReturn(List.of("q"));
        when(rechercheService.rechercher("q", 1486, true, 10, DATE)).thenReturn(List.of(resultat("A", 1)));
        when(assistant.rediger(anyString(), any(), any()))
                .thenReturn(new Reponse(List.of(new Passage("début", List.of())), false, true));

        assertThat(service.repondre("question", PROFIL).avertissements()).last().asString().contains("incomplète");
    }

    @Test
    void refus_du_modele_signale() {
        when(assistant.reformuler(anyString(), any())).thenReturn(List.of("q"));
        when(rechercheService.rechercher("q", 1486, true, 10, DATE)).thenReturn(List.of(resultat("A", 1)));
        when(assistant.rediger(anyString(), any(), any())).thenReturn(new Reponse(List.of(), true, false));

        ReponseQuestion reponse = service.repondre("question", PROFIL);

        assertThat(reponse.refus()).isTrue();
        assertThat(reponse.avertissements()).last().asString().contains("n'a pas pu être traitée");
    }
}
