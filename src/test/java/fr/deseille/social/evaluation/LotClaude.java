package fr.deseille.social.evaluation;

import com.anthropic.client.AnthropicClient;
import com.anthropic.core.ObjectMappers;
import com.anthropic.core.http.StreamResponse;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.batches.BatchCreateParams;
import com.anthropic.models.messages.batches.MessageBatch;
import com.anthropic.models.messages.batches.MessageBatchIndividualResponse;
import com.anthropic.models.messages.batches.MessageBatchResult;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Envoi d'un lot de requêtes à l'API Batch d'Anthropic (moitié prix, traitement asynchrone), suivi
 * jusqu'à la fin, puis résultats par identifiant. Chaque requête est la requête habituelle, convertie
 * par son propre JSON : documents, citations et sortie structurée sont conservés. Les en-têtes et
 * champs propres aux appels directs (repli côté serveur) ne sont pas transmis.
 */
final class LotClaude {

    private final Supplier<AnthropicClient> fabrique;
    private final Duration delaiMax;
    private final Duration intervalle;
    private AnthropicClient client;

    /** Résultat d'une requête : le message, ou la raison de l'échec (erreur, annulation, expiration). */
    record Resultat(Message message, String erreur) {
    }

    LotClaude(Supplier<AnthropicClient> fabrique, Duration delaiMax) {
        this(fabrique, delaiMax, Duration.ofSeconds(20));
    }

    /** @param intervalle délai entre deux consultations de l'état du lot */
    LotClaude(Supplier<AnthropicClient> fabrique, Duration delaiMax, Duration intervalle) {
        this.fabrique = fabrique;
        this.delaiMax = delaiMax;
        this.intervalle = intervalle;
    }

    /**
     * @param requetes requêtes par identifiant (lettres, chiffres, « - » et « _ », 64 caractères au plus)
     * @return résultats par identifiant
     */
    Map<String, Resultat> executer(String nom, Map<String, MessageCreateParams> requetes) throws InterruptedException {
        if (requetes.isEmpty()) {
            return Map.of();
        }
        if (client == null) {
            client = fabrique.get();
        }
        BatchCreateParams.Builder demande = BatchCreateParams.builder();
        requetes.forEach((id, params) -> demande.addRequest(
                BatchCreateParams.Request.builder().customId(id).params(versParams(params)).build()));
        MessageBatch lot = client.messages().batches().create(demande.build());
        System.out.println("Lot « " + nom + " » envoyé : " + lot.id() + ", " + requetes.size() + " requête(s)");

        Instant limite = Instant.now().plus(delaiMax);
        while (!MessageBatch.ProcessingStatus.ENDED.equals(lot.processingStatus())) {
            if (Instant.now().isAfter(limite)) {
                throw new IllegalStateException("Lot « " + nom + " » (" + lot.id() + ") non terminé après " + delaiMax
                        + " : relancer plus tard, il reste consultable dans la console Anthropic");
            }
            Thread.sleep(intervalle.toMillis());
            lot = client.messages().batches().retrieve(lot.id());
        }

        Map<String, Resultat> resultats = new HashMap<>();
        try (StreamResponse<MessageBatchIndividualResponse> flux = client.messages().batches().resultsStreaming(lot.id())) {
            flux.stream().forEach(r -> resultats.put(r.customId(), r.result().succeeded()
                    .map(s -> new Resultat(s.message(), null))
                    .orElseGet(() -> new Resultat(null, raison(r.result())))));
        }
        System.out.println("Lot « " + nom + " » terminé : " + resultats.size() + " résultat(s)");
        return resultats;
    }

    /**
     * Corps de requête d'un lot, obtenu par le JSON de la requête habituelle, sans le champ de repli
     * côté serveur : il exige un en-tête bêta que les requêtes d'un lot ne portent pas.
     */
    static BatchCreateParams.Request.Params versParams(MessageCreateParams params) {
        ObjectNode corps = ObjectMappers.jsonMapper().valueToTree(params._body());
        corps.remove("fallbacks");
        return ObjectMappers.jsonMapper().convertValue(corps, BatchCreateParams.Request.Params.class);
    }

    private static String raison(MessageBatchResult resultat) {
        if (resultat.errored().isPresent()) {
            return "erreur de l'API : " + resultat.errored().get().error();
        }
        if (resultat.canceled().isPresent()) {
            return "requête annulée";
        }
        return "requête expirée avant traitement";
    }
}
