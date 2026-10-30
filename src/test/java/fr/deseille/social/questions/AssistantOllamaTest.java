package fr.deseille.social.questions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import fr.deseille.social.consultation.VuesConsultation.ArticleDetail;
import fr.deseille.social.consultation.VuesConsultation.SectionResume;
import fr.deseille.social.consultation.VuesConsultation.TexteResume;
import fr.deseille.social.corpus.EtatJuridique;
import fr.deseille.social.corpus.TypeTexte;
import fr.deseille.social.profil.ProfilSalarie;
import fr.deseille.social.profil.ProfilService.Anciennete;
import fr.deseille.social.profil.ProfilService.ContexteProfil;
import fr.deseille.social.questions.Assistant.Citation;
import fr.deseille.social.questions.Assistant.Passage;
import fr.deseille.social.questions.Assistant.Reponse;
import com.sun.net.httpserver.HttpServer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.web.client.ResourceAccessException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Vérifie, sans serveur Ollama (modèle simulé), la requête envoyée au modèle local et
 * l'interprétation de ses réponses JSON : citations rapportées aux articles, réponse tronquée ou
 * illisible, panne.
 */
class AssistantOllamaTest {

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
                    ProfilSalarie.TypeContrat.CDI, false, Set.of(), LocalDate.of(2026, 11, 1)),
            new Anciennete(0, 1, 1), List.of(CODE, SYNTEC), true, List.of());

    private final OllamaChatModel modele = mock(OllamaChatModel.class);
    private final SimpleMeterRegistry registre = new SimpleMeterRegistry();
    private final AssistantOllama assistant = new AssistantOllama(modele, new MetriquesQuestions(registre));

    @BeforeEach
    void options_configurees() {
        when(modele.getOptions()).thenReturn(OllamaChatOptions.builder().model("qwen2.5:7b").numCtx(16384).build());
    }

    @Test
    void redaction_documents_numerotes_et_sortie_imposee_par_un_schema() {
        when(modele.call(any(Prompt.class))).thenReturn(reponse("{\"passages\": []}", "stop"));

        assistant.rediger("Combien dure ma période d'essai ?", CONTEXTE, List.of(L1221_19, SYNTEC_3_4));

        ArgumentCaptor<Prompt> prompt = ArgumentCaptor.forClass(Prompt.class);
        verify(modele).call(prompt.capture());
        assertThat(prompt.getValue().getSystemMessage().getText())
                .startsWith(AssistantClaude.CONSIGNES_REDACTION)
                .endsWith(AssistantOllama.CONSIGNES_CITATIONS);
        assertThat(prompt.getValue().getUserMessage().getText())
                .contains("Document 1 : article L1221-19 (Code du travail)\nEmplacement : Partie législative > Période d'essai\n"
                        + "Le contrat peut comporter une période d'essai.")
                // Convention : HTML débarrassé de ses balises, comme pour Claude
                .contains("Document 2 : article 3.4 (Syntec)\nEmplacement : Syntec\nLa période d'essai est de 4 mois")
                .endsWith("Question du salarié : Combien dure ma période d'essai ?");
        OllamaChatOptions options = (OllamaChatOptions) prompt.getValue().getOptions();
        assertThat(options.getFormat()).isEqualTo(AssistantOllama.SCHEMA_REDACTION);
        // Options de la configuration conservées : Spring AI ne les fusionne pas avec celles de l'appel
        assertThat(options.getModel()).isEqualTo("qwen2.5:7b");
        assertThat(options.getNumCtx()).isEqualTo(16384);
    }

    @Test
    void citations_rapportees_aux_articles_par_numero_de_document() {
        when(modele.call(any(Prompt.class))).thenReturn(reponse("""
                {"passages": [
                  {"texte": "Votre période d'essai est de 4 mois.",
                   "citations": [{"document": 2, "extrait": "La période d'essai est de 4 mois"}]},
                  {"texte": "Le Code la permet.", "citations": [{"document": 1, "extrait": "peut comporter une période d'essai"},
                                                               {"document": 7, "extrait": "inventé"}]},
                  {"texte": "  ", "citations": []}
                ]}""", "stop"));

        Reponse reponse = assistant.rediger("Période d'essai ?", CONTEXTE, List.of(L1221_19, SYNTEC_3_4));

        assertThat(reponse.refus()).isFalse();
        assertThat(reponse.tronquee()).isFalse();
        assertThat(reponse.passages()).containsExactly(
                new Passage("Votre période d'essai est de 4 mois. ", List.of(
                        new Citation("KALIARTI000047513825", "3.4", "La période d'essai est de 4 mois"))),
                // Document 7 inexistant : citation écartée ; passage vide : ignoré
                new Passage("Le Code la permet. ", List.of(
                        new Citation("LEGIARTI000019071113", "L1221-19", "peut comporter une période d'essai"))));
        assertThat(VerificationCitations.nonVerifiees(reponse.passages(), List.of(L1221_19, SYNTEC_3_4))).isEmpty();
        assertThat(registre.counter("droits.assistant.appels", "fournisseur", "ollama", "etape", "redaction",
                "issue", "succes").count()).isEqualTo(1.0);
        assertThat(registre.counter("droits.assistant.jetons", "fournisseur", "ollama", "etape", "redaction",
                "sens", "entree").count()).isEqualTo(900.0);
        assertThat(assistant.consommation()).isEqualTo(new Assistant.Consommation(1, 900, 300));
    }

    @Test
    void extrait_paraphrase_signale_par_la_verification_des_citations() {
        when(modele.call(any(Prompt.class))).thenReturn(reponse("""
                {"passages": [{"texte": "Quatre mois.", "citations": [{"document": 2, "extrait": "L'essai dure quatre mois."}]}]}""",
                "stop"));

        Reponse reponse = assistant.rediger("Période d'essai ?", CONTEXTE, List.of(L1221_19, SYNTEC_3_4));

        assertThat(VerificationCitations.nonVerifiees(reponse.passages(), List.of(L1221_19, SYNTEC_3_4)))
                .extracting(Citation::texteCite).containsExactly("L'essai dure quatre mois.");
    }

    @Test
    void json_coupe_par_la_limite_de_jetons_reponse_tronquee() {
        when(modele.call(any(Prompt.class))).thenReturn(reponse("{\"passages\": [{\"texte\": \"Votre pér", "length"));

        Reponse reponse = assistant.rediger("Période d'essai ?", CONTEXTE, List.of(L1221_19));

        assertThat(reponse.tronquee()).isTrue();
        assertThat(reponse.passages()).isEmpty();
        assertThat(registre.counter("droits.assistant.appels", "fournisseur", "ollama", "etape", "redaction",
                "issue", "tronquee").count()).isEqualTo(1.0);
    }

    @Test
    void json_complet_mais_limite_de_jetons_atteinte_passages_servis_et_signales_tronques() {
        when(modele.call(any(Prompt.class))).thenReturn(reponse("""
                {"passages": [{"texte": "Le Code la permet.", "citations": []}]}""", "length"));

        Reponse reponse = assistant.rediger("Période d'essai ?", CONTEXTE, List.of(L1221_19));

        assertThat(reponse.tronquee()).isTrue();
        assertThat(reponse.passages()).containsExactly(new Passage("Le Code la permet. ", List.of()));
        assertThat(registre.counter("droits.assistant.appels", "fournisseur", "ollama", "etape", "redaction",
                "issue", "tronquee").count()).isEqualTo(1.0);
    }

    @Test
    void extrait_absent_citation_gardee_et_signalee_non_verifiee() {
        // Hors schéma (« extrait » y est obligatoire) : tolérance si le modèle ne le respecte pas
        when(modele.call(any(Prompt.class))).thenReturn(reponse("""
                {"passages": [{"texte": "Oui.", "citations": [{"document": 1}]}]}""", "stop"));

        Reponse reponse = assistant.rediger("Période d'essai ?", CONTEXTE, List.of(L1221_19));

        assertThat(reponse.passages()).containsExactly(new Passage("Oui. ",
                List.of(new Citation("LEGIARTI000019071113", "L1221-19", null))));
        assertThat(VerificationCitations.nonVerifiees(reponse.passages(), List.of(L1221_19))).hasSize(1);
    }

    @Test
    void reponse_illisible_sans_troncature_service_indisponible() {
        when(modele.call(any(Prompt.class))).thenReturn(reponse("Votre période d'essai est de 4 mois.", "stop"));

        assertThatThrownBy(() -> assistant.rediger("Période d'essai ?", CONTEXTE, List.of(L1221_19)))
                .isInstanceOf(AssistantIndisponibleException.class);
        assertThat(registre.counter("droits.assistant.appels", "fournisseur", "ollama", "etape", "redaction",
                "issue", "illisible").count()).isEqualTo(1.0);
    }

    @Test
    void ollama_injoignable_redaction_indisponible() {
        when(modele.call(any(Prompt.class))).thenThrow(new ResourceAccessException("Connection refused"));

        assertThatThrownBy(() -> assistant.rediger("Période d'essai ?", CONTEXTE, List.of(L1221_19)))
                .isInstanceOf(AssistantIndisponibleException.class);
        assertThat(registre.counter("droits.assistant.appels", "fournisseur", "ollama", "etape", "redaction",
                "issue", "erreur").count()).isEqualTo(1.0);
    }

    @Test
    void reformulation_requetes_nettoyees() {
        when(modele.call(any(Prompt.class))).thenReturn(reponse("""
                {"requetes": ["période essai", " période essai ", "", "durée renouvellement essai"]}""", "stop"));

        assertThat(assistant.reformuler("Combien dure ma période d'essai ?", CONTEXTE))
                .containsExactly("période essai", "durée renouvellement essai");
    }

    @Test
    void quatre_articles_au_plus_pour_un_petit_modele() {
        assertThat(assistant.articlesMax()).isEqualTo(4);
        assertThat(new Assistant() {
            public List<String> reformuler(String question, ContexteProfil contexte) {
                return List.of();
            }

            public Reponse rediger(String question, ContexteProfil contexte, List<ArticleDetail> articles) {
                return null;
            }
        }.articlesMax()).isEqualTo(8);
    }

    /** Requêtes réellement produites par Qwen 2.5 7B et Mistral 7B lors du premier essai. */
    @Test
    void reformulation_requetes_des_petits_modeles_ramenees_a_des_mots_cles() {
        assertThat(AssistantOllama.simplifier(List.of(
                "durée essai cadre cdi idcc",
                "convention collective idcc 1486 cdi cadre essai durée",
                "convention collective idcc 1486 maternite licenciement non cadre cdi",
                "licenciement enceinte non cadre cdi convention collective idcc 1486",
                "Période d'essai")))
                .containsExactly("durée essai cadre", "cadre essai durée", "maternite licenciement cadre",
                        "licenciement enceinte cadre", "période essai");
    }

    @Test
    void reformulation_exemples_dans_les_consignes_et_requetes_simplifiees() {
        when(modele.call(any(Prompt.class))).thenReturn(reponse("""
                {"requetes": ["convention collective idcc 1486 cdi cadre essai durée", "durée essai cadre cdi idcc", "1486"]}""",
                "stop"));

        assertThat(assistant.reformuler("Combien dure ma période d'essai ?", CONTEXTE))
                .containsExactly("cadre essai durée", "durée essai cadre");
        ArgumentCaptor<Prompt> prompt = ArgumentCaptor.forClass(Prompt.class);
        verify(modele).call(prompt.capture());
        assertThat(prompt.getValue().getSystemMessage().getText())
                .startsWith(AssistantClaude.CONSIGNES_REFORMULATION)
                .endsWith(AssistantOllama.EXEMPLES_REFORMULATION);
    }

    @Test
    void reformulation_illisible_ou_en_panne_recherche_sur_la_question_brute() {
        when(modele.call(any(Prompt.class)))
                .thenReturn(reponse("période essai", "stop"))
                .thenThrow(new ResourceAccessException("Connection refused"));

        assertThat(assistant.reformuler("Ma période d'essai ?", CONTEXTE)).containsExactly("Ma période d'essai ?");
        assertThat(assistant.reformuler("Ma période d'essai ?", CONTEXTE)).containsExactly("Ma période d'essai ?");
        assertThat(registre.counter("droits.assistant.appels", "fournisseur", "ollama", "etape", "reformulation",
                "issue", "illisible").count()).isEqualTo(1.0);
        assertThat(registre.counter("droits.assistant.appels", "fournisseur", "ollama", "etape", "reformulation",
                "issue", "erreur").count()).isEqualTo(1.0);
    }

    /**
     * Client réel de Spring AI contre un faux serveur Ollama : requête HTTP émise (modèle, schéma,
     * options) et réponse interprétée, sans le générateur de schémas de Spring AI (victools 4
     * épinglé pour le SDK Anthropic, voir le pom).
     */
    @Test
    void client_spring_ai_contre_un_faux_serveur_ollama() throws Exception {
        HttpServer serveur = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        AtomicReference<String> requete = new AtomicReference<>();
        serveur.createContext("/api/chat", echange -> {
            requete.set(new String(echange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] corps = """
                    {"model": "qwen2.5:7b", "created_at": "2026-10-30T10:00:00Z",
                     "message": {"role": "assistant", "content": "{\\"passages\\": [{\\"texte\\": \\"Oui.\\", \\"citations\\": [{\\"document\\": 1, \\"extrait\\": \\"peut comporter\\"}]}]}"},
                     "done": true, "done_reason": "stop", "prompt_eval_count": 900, "eval_count": 300}"""
                    .getBytes(StandardCharsets.UTF_8);
            echange.getResponseHeaders().add("Content-Type", "application/json");
            echange.sendResponseHeaders(200, corps.length);
            echange.getResponseBody().write(corps);
            echange.close();
        });
        serveur.start();
        try {
            OllamaChatModel client = OllamaChatModel.builder()
                    .ollamaApi(OllamaApi.builder().baseUrl("http://127.0.0.1:" + serveur.getAddress().getPort()).build())
                    .options(OllamaChatOptions.builder().model("qwen2.5:7b").numCtx(16384).build())
                    .build();
            AssistantOllama reel = new AssistantOllama(client, new MetriquesQuestions(registre));

            Reponse reponse = reel.rediger("Période d'essai ?", CONTEXTE, List.of(L1221_19));

            assertThat(reponse.passages()).containsExactly(new Passage("Oui. ",
                    List.of(new Citation("LEGIARTI000019071113", "L1221-19", "peut comporter"))));
            assertThat(reel.modele()).isEqualTo("qwen2.5:7b");
            assertThat(reel.consommation()).isEqualTo(new Assistant.Consommation(1, 900, 300));
            JsonNode envoye = JsonMapper.builder().build().readTree(requete.get());
            assertThat(envoye.path("model").asString()).isEqualTo("qwen2.5:7b");
            assertThat(envoye.path("stream").asBoolean()).isFalse();
            assertThat(envoye.path("format").path("required").get(0).asString()).isEqualTo("passages");
            assertThat(envoye.path("options").path("num_ctx").asInt()).isEqualTo(16384);
            assertThat(envoye.path("messages").get(0).path("role").asString()).isEqualTo("system");
        } finally {
            serveur.stop(0);
        }
    }

    /** Réponse au format de Spring AI pour Ollama : texte, fin de génération, jetons (900 / 300). */
    private static ChatResponse reponse(String texte, String fin) {
        return new ChatResponse(
                List.of(new Generation(new AssistantMessage(texte), ChatGenerationMetadata.builder().finishReason(fin).build())),
                ChatResponseMetadata.builder().usage(new DefaultUsage(900, 300)).build());
    }
}
