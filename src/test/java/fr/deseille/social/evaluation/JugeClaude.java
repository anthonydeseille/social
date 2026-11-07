package fr.deseille.social.evaluation;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.ObjectMappers;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.StructuredMessage;
import com.anthropic.models.messages.StructuredMessageCreateParams;
import com.anthropic.models.messages.TextBlock;
import fr.deseille.social.evaluation.JeuEvaluation.Question;
import fr.deseille.social.questions.Assistant.Citation;
import fr.deseille.social.questions.Assistant.Passage;
import fr.deseille.social.questions.ClaudeProperties;
import fr.deseille.social.questions.Consignes;
import fr.deseille.social.questions.DescriptionProfil;
import fr.deseille.social.questions.QuestionService.ReponseQuestion;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * {@link Juge} adossé à Claude, en sortie structurée. Le modèle du juge est réglable
 * ({@code -Devaluation.juge.modele}) ; par défaut celui de l'application.
 */
final class JugeClaude implements Juge {

    private static final long MAX_TOKENS = 16_000L;

    /** Consignes du juge ({@code src/test/resources/prompts/juge.md}) et leur version, pour l'historique. */
    static final String CONSIGNES = Consignes.lire("juge");
    static final String VERSION = Consignes.version(CONSIGNES);

    private final String modele;
    private final Supplier<AnthropicClient> fabrique;
    private AnthropicClient client;
    private final AtomicLong jetonsEntree = new AtomicLong();
    private final AtomicLong jetonsSortie = new AtomicLong();

    JugeClaude(String modele, ClaudeProperties properties) {
        this(modele, () -> AnthropicOkHttpClient.builder()
                .fromEnv()
                .timeout(properties.delai())
                .maxRetries(properties.tentatives())
                .build());
    }

    JugeClaude(String modele, Supplier<AnthropicClient> fabrique) {
        this.modele = modele;
        this.fabrique = fabrique;
    }

    long jetonsEntree() {
        return jetonsEntree.get();
    }

    long jetonsSortie() {
        return jetonsSortie.get();
    }

    @Override
    public Verdict juger(Question question, ReponseQuestion reponse) {
        if (client == null) {
            client = fabrique.get();
        }
        StructuredMessage<Verdict> message = client.messages().create(parametres(question, reponse));
        jetonsEntree.addAndGet(message.usage().inputTokens());
        jetonsSortie.addAndGet(message.usage().outputTokens());
        return message.content().stream()
                .flatMap(bloc -> bloc.text().stream())
                .map(texte -> texte.text())
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Verdict absent (fin : " + message.stopReason().orElse(null) + ")"));
    }

    /** Verdict d'une réponse reçue comme message ordinaire (résultat d'un lot). */
    static Verdict verdictDepuis(Message message) {
        String json = message.content().stream().flatMap(bloc -> bloc.text().stream())
                .map(TextBlock::text).collect(Collectors.joining());
        if (json.isBlank()) {
            throw new IllegalStateException("Verdict absent (fin : " + message.stopReason().orElse(null) + ")");
        }
        try {
            return ObjectMappers.jsonMapper().readValue(json, Verdict.class);
        } catch (Exception e) {
            throw new IllegalStateException("Verdict illisible : " + e.getMessage(), e);
        }
    }

    StructuredMessageCreateParams<Verdict> parametres(Question question, ReponseQuestion reponse) {
        return MessageCreateParams.builder()
                .model(modele)
                .maxTokens(MAX_TOKENS)
                .system(CONSIGNES)
                .outputConfig(Verdict.class)
                .addUserMessage(message(question, reponse))
                .build();
    }

    static String message(Question question, ReponseQuestion reponse) {
        return """
                Question du salarié : %s

                %s

                Réponse de référence : %s

                Points clés :
                %s

                Erreurs à éviter :
                %s

                Réponse évaluée (articles cités entre crochets) :
                %s""".formatted(
                question.question(),
                DescriptionProfil.decrire(reponse.contexte()),
                question.reponseAttendue().strip(),
                numeroter(question.pointsCles()),
                question.erreursAEviter().isEmpty() ? "(aucune)" : numeroter(question.erreursAEviter()),
                texte(reponse.passages()));
    }

    private static String numeroter(List<String> lignes) {
        return IntStream.range(0, lignes.size()).mapToObj(i -> (i + 1) + ". " + lignes.get(i))
                .collect(Collectors.joining("\n"));
    }

    private static String texte(List<Passage> passages) {
        return passages.stream().map(p -> p.citations().isEmpty() ? p.texte()
                        : p.texte() + " [" + p.citations().stream().map(Citation::num).distinct()
                        .collect(Collectors.joining(", ")) + "]")
                .collect(Collectors.joining());
    }
}
