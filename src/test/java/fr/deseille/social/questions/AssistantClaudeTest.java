package fr.deseille.social.questions;

import static org.assertj.core.api.Assertions.assertThat;

import com.anthropic.core.JsonValue;
import com.anthropic.core.ObjectMappers;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
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
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Vérifie, sans appel réseau, la requête de rédaction envoyée à Claude (sérialisée par le SDK) et
 * l'interprétation d'une réponse au format de l'API (désérialisée par le SDK).
 */
class AssistantClaudeTest {

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

    private final AssistantClaude assistant = new AssistantClaude(new ClaudeProperties("claude-opus-5-5"));

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

        assertThat(AssistantClaude.versReponse(message, List.of(L1221_19))).isEqualTo(new Reponse(List.of(), true));
    }

    @Test
    void consignes_de_redaction_imposent_sources_et_disposition_plus_favorable() {
        assertThat(AssistantClaude.CONSIGNES_REDACTION)
                .contains("uniquement sur les articles fournis", "plus favorable au salarié", "inspection du travail");
    }
}
