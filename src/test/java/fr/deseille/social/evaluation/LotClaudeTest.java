package fr.deseille.social.evaluation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.anthropic.client.AnthropicClient;
import com.anthropic.core.ObjectMappers;
import com.anthropic.core.http.StreamResponse;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.batches.BatchCreateParams;
import com.anthropic.models.messages.batches.MessageBatch;
import com.anthropic.models.messages.batches.MessageBatchIndividualResponse;
import com.anthropic.services.blocking.MessageService;
import com.anthropic.services.blocking.messages.BatchService;
import com.fasterxml.jackson.databind.JsonNode;
import fr.deseille.social.consultation.VuesConsultation.ArticleDetail;
import fr.deseille.social.consultation.VuesConsultation.TexteResume;
import fr.deseille.social.corpus.EtatJuridique;
import fr.deseille.social.corpus.TypeTexte;
import fr.deseille.social.evaluation.Juge.Verdict;
import fr.deseille.social.profil.ProfilSalarie;
import fr.deseille.social.profil.ProfilService.ContexteProfil;
import fr.deseille.social.questions.AssistantClaude;
import fr.deseille.social.questions.ClaudeProperties;
import fr.deseille.social.questions.MetriquesQuestions;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/** Évaluation par lots sans appel réseau : conversion des requêtes, déroulé d'un lot, lecture des réponses. */
class LotClaudeTest {

    private static final TexteResume CODE = new TexteResume("LEGITEXT000006072050", TypeTexte.CODE, null, "Code du travail", null);
    private static final ArticleDetail ARTICLE = new ArticleDetail("LEGIARTI000019071113", "C1", "L1221-19",
            EtatJuridique.VIGUEUR, true, null, null, "Le contrat peut comporter une période d'essai.", null, null,
            null, CODE, List.of());
    private static final ContexteProfil CONTEXTE = new ContexteProfil(
            new ProfilSalarie(null, null, null, null, null, null, false, null, LocalDate.of(2026, 11, 1)),
            null, List.of(CODE), false, List.of());
    private static final AssistantClaude ASSISTANT = new AssistantClaude(
            new ClaudeProperties("claude-opus-5-5", 200, Duration.ofMinutes(3), 2), new MetriquesQuestions(new SimpleMeterRegistry()));

    private static JsonNode json(Object valeur) throws Exception {
        return ObjectMappers.jsonMapper().readTree(ObjectMappers.jsonMapper().writeValueAsString(valeur));
    }

    @Test
    void requete_de_redaction_convertie_avec_documents_citations_et_effort_sans_repli() throws Exception {
        BatchCreateParams.Request.Params params = LotClaude.versParams(
                ASSISTANT.parametresRedaction("Ma période d'essai ?", CONTEXTE, List.of(ARTICLE)));
        JsonNode corps = json(params);

        assertThat(corps.path("model").asText()).isEqualTo("claude-opus-5-5");
        assertThat(corps.path("output_config").path("effort").asText()).isEqualTo("high");
        assertThat(corps.path("system").asText()).contains("uniquement sur les articles fournis");
        JsonNode contenu = corps.path("messages").get(0).path("content");
        assertThat(contenu.get(0).path("type").asText()).isEqualTo("document");
        assertThat(contenu.get(0).path("citations").path("enabled").asBoolean()).isTrue();
        assertThat(contenu.get(1).path("text").asText()).contains("Question du salarié : Ma période d'essai ?");
        assertThat(corps.has("fallbacks")).as("repli propre aux appels directs").isFalse();
    }

    @Test
    void requete_de_reformulation_convertie_avec_sa_sortie_structuree() throws Exception {
        JsonNode corps = json(LotClaude.versParams(ASSISTANT.parametresReformulation("Ma période d'essai ?", CONTEXTE).rawParams()));

        assertThat(corps.path("output_config").path("format").path("type").asText()).isEqualTo("json_schema");
        assertThat(corps.path("output_config").path("format").path("schema").path("properties").has("requetes")).isTrue();
    }

    private static <T> T lire(String json, Class<T> type) throws Exception {
        return ObjectMappers.jsonMapper().readValue(json, type);
    }

    private static MessageBatch lot(String statut) throws Exception {
        return lire("""
                {"id": "msgbatch_1", "type": "message_batch", "processing_status": "%s",
                 "request_counts": {"processing": 0, "succeeded": 1, "errored": 1, "canceled": 0, "expired": 0},
                 "created_at": "2026-10-29T10:00:00Z", "expires_at": "2026-10-30T10:00:00Z",
                 "ended_at": null, "archived_at": null, "cancel_initiated_at": null, "results_url": null}
                """.formatted(statut), MessageBatch.class);
    }

    @SuppressWarnings("unchecked")
    private static BatchService batches(MessageBatch... etats) throws Exception {
        BatchService batches = mock(BatchService.class);
        when(batches.create(any(BatchCreateParams.class))).thenReturn(etats[0]);
        if (etats.length > 1) {
            when(batches.retrieve(anyString())).thenReturn(etats[1], java.util.Arrays.copyOfRange(etats, 2, etats.length));
        }
        StreamResponse<MessageBatchIndividualResponse> flux = mock(StreamResponse.class);
        when(flux.stream()).thenReturn(Stream.of(
                lire("""
                        {"custom_id": "q1", "result": {"type": "succeeded", "message": {
                          "id": "msg_1", "type": "message", "role": "assistant", "model": "claude-opus-5-5",
                          "stop_reason": "end_turn", "stop_sequence": null,
                          "usage": {"input_tokens": 10, "output_tokens": 5},
                          "content": [{"type": "text", "text": "ok", "citations": null}]}}}
                        """, MessageBatchIndividualResponse.class),
                lire("""
                        {"custom_id": "q2", "result": {"type": "errored", "error": {"type": "error",
                          "error": {"type": "overloaded_error", "message": "Overloaded"}}}}
                        """, MessageBatchIndividualResponse.class)));
        when(batches.resultsStreaming(anyString())).thenReturn(flux);
        return batches;
    }

    private static AnthropicClient client(BatchService batches) {
        AnthropicClient client = mock(AnthropicClient.class);
        MessageService messages = mock(MessageService.class);
        when(client.messages()).thenReturn(messages);
        when(messages.batches()).thenReturn(batches);
        return client;
    }

    @Test
    void lot_suivi_jusqu_a_la_fin_puis_resultats_par_identifiant() throws Exception {
        AnthropicClient client = client(batches(lot("in_progress"), lot("in_progress"), lot("ended")));
        LotClaude lots = new LotClaude(() -> client, Duration.ofMinutes(1), Duration.ofMillis(1));

        Map<String, LotClaude.Resultat> resultats = lots.executer("test", Map.of(
                "q1", ASSISTANT.parametresRedaction("Q1 ?", CONTEXTE, List.of(ARTICLE)),
                "q2", ASSISTANT.parametresRedaction("Q2 ?", CONTEXTE, List.of(ARTICLE))));

        assertThat(resultats.get("q1").message().usage().inputTokens()).isEqualTo(10);
        assertThat(resultats.get("q2").message()).isNull();
        assertThat(resultats.get("q2").erreur()).contains("erreur de l'API");
    }

    @Test
    void lot_non_termine_dans_le_delai_signale() throws Exception {
        AnthropicClient client = client(batches(lot("in_progress"), lot("in_progress")));
        LotClaude lots = new LotClaude(() -> client, Duration.ZERO, Duration.ofMillis(1));

        assertThatThrownBy(() -> lots.executer("lent", Map.of("q1",
                ASSISTANT.parametresRedaction("Q1 ?", CONTEXTE, List.of(ARTICLE)))))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("msgbatch_1");
    }

    @Test
    void reponses_d_un_lot_lues_comme_sorties_structurees() throws Exception {
        Message reformulation = lire("""
                {"id": "msg_2", "type": "message", "role": "assistant", "model": "claude-opus-5-5",
                 "stop_reason": "end_turn", "stop_sequence": null, "usage": {"input_tokens": 1, "output_tokens": 1},
                 "content": [{"type": "text", "text": "{\\"requetes\\": [\\"période essai\\", \\" \\", \\"période essai\\"]}", "citations": null}]}
                """, Message.class);
        assertThat(AssistantClaude.requetesDepuis(reformulation, "brute")).containsExactly("période essai");

        Message illisible = lire("""
                {"id": "msg_3", "type": "message", "role": "assistant", "model": "claude-opus-5-5",
                 "stop_reason": "end_turn", "stop_sequence": null, "usage": {"input_tokens": 1, "output_tokens": 1},
                 "content": [{"type": "text", "text": "pas du JSON", "citations": null}]}
                """, Message.class);
        assertThat(AssistantClaude.requetesDepuis(illisible, "brute")).containsExactly("brute");

        Message verdict = lire("""
                {"id": "msg_4", "type": "message", "role": "assistant", "model": "claude-opus-5-5",
                 "stop_reason": "end_turn", "stop_sequence": null, "usage": {"input_tokens": 1, "output_tokens": 1},
                 "content": [{"type": "text", "text": "{\\"points\\": [{\\"point\\": \\"4 mois\\", \\"present\\": true, \\"justification\\": \\"ok\\"}], \\"erreurs\\": [], \\"commentaire\\": \\"\\"}", "citations": null}]}
                """, Message.class);
        Verdict lu = JugeClaude.verdictDepuis(verdict);
        assertThat(lu.scoreQualite()).isEqualTo(1.0);
        assertThatThrownBy(() -> JugeClaude.verdictDepuis(illisible)).hasMessageContaining("illisible");
    }

    @Test
    void tarif_des_lots_a_moitie_prix() {
        assertThat(Tarif.pour("claude-opus-5-5").lots().cout(1_000_000, 1_000_000)).isEqualTo(12.0);
    }
}
