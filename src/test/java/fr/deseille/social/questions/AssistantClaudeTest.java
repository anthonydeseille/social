package fr.deseille.social.questions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.anthropic.client.AnthropicClient;
import com.anthropic.core.JsonValue;
import com.anthropic.core.ObjectMappers;
import com.anthropic.errors.AnthropicException;
import com.anthropic.errors.AnthropicIoException;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.StructuredMessageCreateParams;
import com.anthropic.services.blocking.MessageService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import com.fasterxml.jackson.databind.JsonNode;
import fr.deseille.social.consultation.VuesConsultation.ArticleDetail;
import fr.deseille.social.consultation.VuesConsultation.SectionResume;
import fr.deseille.social.consultation.VuesConsultation.TexteResume;
import fr.deseille.social.corpus.EtatJuridique;
import fr.deseille.social.corpus.TypeTexte;
import fr.deseille.social.profil.ProfilSalarie;
import fr.deseille.social.profil.ProfilService.Anciennete;
import fr.deseille.social.profil.ProfilService.ContexteProfil;
import fr.deseille.social.questions.Assistant.Citation;
import fr.deseille.social.questions.Assistant.Reponse;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Vérifie, sans appel réseau, la requête de rédaction envoyée à Claude (sérialisée par le SDK) et
 * l'interprétation d'une réponse au format de l'API (désérialisée par le SDK).
 */
class AssistantClaudeTest {

    private static final ClaudeProperties PROPRIETES =
            new ClaudeProperties("claude-opus-5-5", Duration.ofMinutes(3), 2);

    private static final TexteResume CODE =
            new TexteResume("LEGITEXT000006072050", TypeTexte.CODE, null, "Code du travail", EtatJuridique.VIGUEUR);
    private static final TexteResume SYNTEC =
            new TexteResume("KALICONT000005635173", TypeTexte.CONVENTION_COLLECTIVE, 1486, "Syntec", null);

    private static final ArticleDetail L1221_19 = new ArticleDetail("LEGIARTI000019071113", "C1", "L1221-19",
            EtatJuridique.VIGUEUR, true, null, null, "Le contrat peut comporter une période d'essai.", null, null,
            null, CODE, List.of(new SectionResume("S1", "Partie législative", EtatJuridique.VIGUEUR),
            new SectionResume("S2", "Période d'essai", EtatJuridique.VIGUEUR)));
    private static final ArticleDetail SYNTEC_3_4 = new ArticleDetail("KALIARTI000047513825", "C2", "3.4",
            EtatJuridique.VIGUEUR_ETEN, true, null, null, null, "<p>La période d'essai est de <strong>4 mois</strong>.</p>",
            null, null, SYNTEC, List.of());

    private static final ContexteProfil CONTEXTE = new ContexteProfil(
            new ProfilSalarie(1486, LocalDate.of(2026, 10, 1), null, ProfilSalarie.Statut.CADRE, "IC position 2.1",
                    ProfilSalarie.TypeContrat.CDI, false, Set.of(ProfilSalarie.Situation.GROSSESSE), LocalDate.of(2026, 11, 1)),
            new Anciennete(0, 1, 1), List.of(CODE, SYNTEC), true, List.of());

    private final AssistantClaude assistant = new AssistantClaude(PROPRIETES, new MetriquesQuestions(new SimpleMeterRegistry()));

    @Test
    void requete_de_redaction_un_document_cite_par_article_puis_la_question() throws Exception {
        MessageCreateParams params = assistant.parametresRedaction("Combien dure ma période d'essai ?", CONTEXTE,
                List.of(L1221_19, SYNTEC_3_4));
        JsonNode corps = ObjectMappers.jsonMapper().readTree(ObjectMappers.jsonMapper().writeValueAsString(params._body()));

        assertThat(corps.path("model").asText()).isEqualTo("claude-opus-5-5");
        assertThat(corps.path("output_config").path("effort").asText()).isEqualTo("high");
        JsonNode contenu = corps.path("messages").get(0).path("content");
        assertThat(contenu).hasSize(3);

        JsonNode code = contenu.get(0);
        assertThat(code.path("type").asText()).isEqualTo("document");
        assertThat(code.path("source").path("type").asText()).isEqualTo("text");
        assertThat(code.path("source").path("media_type").asText()).isEqualTo("text/plain");
        assertThat(code.path("citations").path("enabled").asBoolean()).isTrue();
        assertThat(code.path("title").asText()).isEqualTo("L1221-19 (Code du travail)");
        assertThat(code.path("context").asText()).isEqualTo("Partie législative > Période d'essai");

        assertThat(contenu.get(1).path("source").path("data").asText())
                .as("HTML de la convention sans balises").isEqualTo("La période d'essai est de 4 mois .");

        String question = contenu.get(2).path("text").asText();
        assertThat(question).contains("Syntec (IDCC 1486)", "cadre", "IC position 2.1", "grossesse",
                "0 an(s) et 1 mois", "Question du salarié : Combien dure ma période d'essai ?");

        assertThat(params._additionalHeaders().values("anthropic-beta")).contains("server-side-fallback-2026-07-01");
        assertThat(params._additionalBodyProperties()).containsEntry("fallbacks", JsonValue.from("default"));
    }

    @Test
    void requete_de_reformulation_en_sortie_structuree() throws Exception {
        var params = assistant.parametresReformulation("Combien dure ma période d'essai ?", CONTEXTE);
        JsonNode corps = ObjectMappers.jsonMapper().readTree(
                ObjectMappers.jsonMapper().writeValueAsString(params.rawParams()._body()));

        JsonNode format = corps.path("output_config").path("format");
        assertThat(format.path("type").asText()).isEqualTo("json_schema");
        assertThat(format.path("schema").path("properties").has("requetes")).isTrue();
        assertThat(corps.path("messages").get(0).path("content").toString()).contains("Question du salarié");
        assertThat(params.rawParams()._additionalBodyProperties()).containsEntry("fallbacks", JsonValue.from("default"));

        // Ce que le SDK désérialisera depuis la réponse du modèle
        assertThat(ObjectMappers.jsonMapper().readValue("{\"requetes\": [\"période essai\", \"essai cadre\"]}",
                AssistantClaude.Requetes.class).requetes()).containsExactly("période essai", "essai cadre");
    }

    @Test
    void reponse_passages_et_citations_rapportees_aux_articles() throws Exception {
        Message message = ObjectMappers.jsonMapper().readValue("""
                {"id": "msg_1", "type": "message", "role": "assistant", "model": "claude-opus-5-5",
                 "stop_reason": "end_turn", "stop_sequence": null,
                 "usage": {"input_tokens": 1200, "output_tokens": 300},
                 "content": [
                   {"type": "thinking", "thinking": "", "signature": "sig"},
                   {"type": "text", "text": "Votre période d'essai peut durer 4 mois. ", "citations": null},
                   {"type": "text", "text": "La convention Syntec la fixe à 4 mois pour les cadres.",
                    "citations": [{"type": "char_location", "cited_text": "La période d'essai est de 4 mois .",
                                   "document_index": 1, "document_title": "3.4 (Syntec)",
                                   "start_char_index": 0, "end_char_index": 34, "file_id": null},
                                  {"type": "char_location", "cited_text": "hors bornes",
                                   "document_index": 7, "document_title": "?",
                                   "start_char_index": 0, "end_char_index": 5, "file_id": null}]}
                 ]}
                """, Message.class);

        Reponse reponse = AssistantClaude.versReponse(message, List.of(L1221_19, SYNTEC_3_4));

        assertThat(reponse.refus()).isFalse();
        assertThat(reponse.tronquee()).isFalse();
        assertThat(reponse.passages()).hasSize(2);
        assertThat(reponse.passages().get(0).citations()).isEmpty();
        assertThat(reponse.passages().get(1).citations())
                .as("citation hors bornes ignorée")
                .containsExactly(new Citation("KALIARTI000047513825", "3.4", "La période d'essai est de 4 mois ."));
    }

    @Test
    void refus_signale_sans_passage() throws Exception {
        Message message = ObjectMappers.jsonMapper().readValue("""
                {"id": "msg_2", "type": "message", "role": "assistant", "model": "claude-opus-5-5",
                 "stop_reason": "refusal", "stop_sequence": null,
                 "usage": {"input_tokens": 10, "output_tokens": 0}, "content": []}
                """, Message.class);

        assertThat(AssistantClaude.versReponse(message, List.of(L1221_19))).isEqualTo(new Reponse(List.of(), true, false));
    }

    @Test
    void reponse_interrompue_par_la_limite_de_jetons_signalee() throws Exception {
        Message message = ObjectMappers.jsonMapper().readValue("""
                {"id": "msg_3", "type": "message", "role": "assistant", "model": "claude-opus-5-5",
                 "stop_reason": "max_tokens", "stop_sequence": null,
                 "usage": {"input_tokens": 10, "output_tokens": 16000},
                 "content": [{"type": "text", "text": "Votre indemnité se calcule ainsi : 7 ×", "citations": null}]}
                """, Message.class);

        Reponse reponse = AssistantClaude.versReponse(message, List.of(L1221_19));
        assertThat(reponse.tronquee()).isTrue();
        assertThat(reponse.passages()).hasSize(1);
    }

    @Test
    void contenu_sans_balises_ni_entites_et_avec_le_nota() {
        ArticleDetail article = new ArticleDetail("KALIARTI1", null, "4.5", EtatJuridique.VIGUEUR_ETEN, true, null,
                null, null, "<p>Indemnit&eacute; de 1/3&nbsp;de mois &amp; plus.</p>", "<p>Applicable aux IC.</p>",
                null, SYNTEC, List.of());

        assertThat(AssistantClaude.contenu(article))
                .isEqualTo("Indemnité de 1/3 de mois & plus.\n\nNota : Applicable aux IC.");
    }

    @Test
    void erreur_du_service_reformulation_sur_la_question_brute_redaction_en_503() {
        AnthropicClient client = mock(AnthropicClient.class);
        MessageService messages = mock(MessageService.class);
        when(client.messages()).thenReturn(messages);
        when(messages.create(any(StructuredMessageCreateParams.class))).thenThrow(new AnthropicIoException("délai dépassé"));
        when(messages.create(any(MessageCreateParams.class))).thenThrow(new AnthropicIoException("délai dépassé"));
        AssistantClaude enPanne = new AssistantClaude(PROPRIETES, () -> client, new MetriquesQuestions(new SimpleMeterRegistry()));

        assertThat(enPanne.reformuler("Ma période d'essai ?", CONTEXTE)).containsExactly("Ma période d'essai ?");
        assertThatThrownBy(() -> enPanne.rediger("Ma période d'essai ?", CONTEXTE, List.of(L1221_19)))
                .isInstanceOf(AssistantIndisponibleException.class);
    }

    @Test
    void redaction_compte_les_jetons_et_les_appels() throws Exception {
        Message message = ObjectMappers.jsonMapper().readValue("""
                {"id": "msg_4", "type": "message", "role": "assistant", "model": "claude-opus-5-5",
                 "stop_reason": "end_turn", "stop_sequence": null,
                 "usage": {"input_tokens": 4200, "output_tokens": 1800},
                 "content": [{"type": "text", "text": "Réponse.", "citations": null}]}
                """, Message.class);
        AnthropicClient client = mock(AnthropicClient.class);
        MessageService messages = mock(MessageService.class);
        when(client.messages()).thenReturn(messages);
        when(messages.create(any(MessageCreateParams.class))).thenReturn(message);
        SimpleMeterRegistry registre = new SimpleMeterRegistry();

        MetriquesQuestions metriques = new MetriquesQuestions(registre);
        DepensesJournalieres depenses = DepensesJournalieres.sansBudget();
        AssistantClaude assistant = new AssistantClaude(PROPRIETES, () -> client, metriques, depenses);
        assistant.rediger("question", CONTEXTE, List.of(L1221_19));

        // Opus 5.5 : 4 200 × 4 $ + 1 800 × 20 $ par million = 0,0528 $
        assertThat(registre.counter("droits.assistant.cout_usd", "fournisseur", "claude", "etape", "redaction").count())
                .isCloseTo(0.0528, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(assistant.consommation().coutUsd()).isCloseTo(0.0528, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(depenses.depenseDuJour()).isCloseTo(0.0528, org.assertj.core.data.Offset.offset(1e-9));

        assertThat(registre.counter("droits.assistant.jetons", "fournisseur", "claude", "etape", "redaction", "sens", "entree").count()).isEqualTo(4200.0);
        assertThat(registre.counter("droits.assistant.jetons", "fournisseur", "claude", "etape", "redaction", "sens", "sortie").count()).isEqualTo(1800.0);
        assertThat(registre.counter("droits.assistant.appels", "fournisseur", "claude", "etape", "redaction", "issue", "succes").count()).isEqualTo(1.0);
    }

    @Test
    void modele_de_reformulation_distinct_si_configure() {
        AssistantClaude parDefaut = new AssistantClaude(PROPRIETES, new MetriquesQuestions(new SimpleMeterRegistry()));
        AssistantClaude leger = new AssistantClaude(new ClaudeProperties("claude-opus-5-5", Duration.ofMinutes(3), 2,
                "claude-haiku-5-5"), new MetriquesQuestions(new SimpleMeterRegistry()));

        assertThat(parDefaut.parametresReformulation("q", CONTEXTE).rawParams().model().asString()).isEqualTo("claude-opus-5-5");
        assertThat(parDefaut.modeleReformulation()).isEqualTo("claude-opus-5-5");
        assertThat(leger.parametresReformulation("q", CONTEXTE).rawParams().model().asString()).isEqualTo("claude-haiku-5-5");
        assertThat(leger.parametresRedaction("q", CONTEXTE, List.of(L1221_19)).model().asString()).isEqualTo("claude-opus-5-5");
        assertThat(leger.modeleReformulation()).isEqualTo("claude-haiku-5-5");
    }

    @Test
    void cle_absente_traitee_comme_une_indisponibilite() {
        AssistantClaude sansCle = new AssistantClaude(PROPRIETES, () -> {
            throw new AnthropicException("aucune clé d'API");
        }, new MetriquesQuestions(new SimpleMeterRegistry()));

        assertThat(sansCle.reformuler("question", CONTEXTE)).containsExactly("question");
        assertThatThrownBy(() -> sansCle.rediger("question", CONTEXTE, List.of(L1221_19)))
                .isInstanceOf(AssistantIndisponibleException.class);
    }

    @Test
    void consignes_de_redaction_imposent_sources_et_disposition_plus_favorable() {
        assertThat(AssistantClaude.CONSIGNES_REDACTION)
                .contains("uniquement sur les articles fournis", "plus favorable au salarié", "inspection du travail");
    }
}
