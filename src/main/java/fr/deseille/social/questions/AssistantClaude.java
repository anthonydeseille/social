package fr.deseille.social.questions;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.JsonValue;
import com.anthropic.errors.AnthropicException;
import com.anthropic.models.messages.CitationsConfigParam;
import com.anthropic.models.messages.ContentBlockParam;
import com.anthropic.models.messages.DocumentBlockParam;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.OutputConfig;
import com.anthropic.models.messages.PlainTextSource;
import com.anthropic.models.messages.StopReason;
import com.anthropic.models.messages.StructuredMessage;
import com.anthropic.models.messages.StructuredMessageCreateParams;
import com.anthropic.models.messages.TextCitation;
import com.anthropic.models.messages.Usage;
import fr.deseille.social.consultation.VuesConsultation.ArticleDetail;
import fr.deseille.social.consultation.VuesConsultation.SectionResume;
import fr.deseille.social.profil.ProfilService.ContexteProfil;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.web.util.HtmlUtils;

/**
 * {@link Assistant} adossé à Claude (SDK officiel Anthropic).
 * <ul>
 *   <li>Reformulation : sortie structurée (liste de requêtes).</li>
 *   <li>Rédaction : chaque article est un document avec citations activées, si bien que chaque
 *       passage de la réponse renvoie au texte exact cité.</li>
 * </ul>
 * Repli côté serveur activé ({@code fallbacks: "default"}) : une requête refusée par les filtres
 * de sécurité est rejouée sur un autre modèle ; un refus final est signalé, jamais masqué. Le
 * client n'est créé qu'au premier appel, pour que l'application démarre sans clé d'API. Une erreur du
 * service fait chercher sur la question brute (reformulation) ou renvoie un 503 (rédaction).
 */
@Component
@EnableConfigurationProperties(ClaudeProperties.class)
public class AssistantClaude implements Assistant {

    private static final Logger log = LoggerFactory.getLogger(AssistantClaude.class);

    private static final long MAX_TOKENS = 16_000L;
    private static final int REQUETES_MAX = 4;
    private static final String BETA_REPLI = "server-side-fallback-2026-07-01";

    static final String CONSIGNES_REFORMULATION = """
            Tu prépares la recherche d'articles du Code du travail et d'une convention collective \
            pour répondre à la question d'un salarié.
            Produis de 1 à 4 requêtes de recherche plein texte. Chaque requête compte 2 à 4 mots-clés \
            juridiques, tels qu'ils apparaissent dans les textes (par exemple « période essai », \
            « indemnité licenciement », « congé maternité »), sans phrase ni ponctuation. Un article \
            n'est trouvé que s'il contient tous les mots de la requête : préfère plusieurs requêtes \
            courtes à une longue, et couvre les différents aspects de la question (par exemple la \
            durée et la rémunération).""";

    static final String CONSIGNES_REDACTION = """
            Tu aides des salariés en France à comprendre leurs droits. Tu réponds en français simple.

            - Appuie-toi uniquement sur les articles fournis (Code du travail et, le cas échéant, \
            convention collective) et cite-les pour chaque affirmation de droit.
            - Si les articles ne suffisent pas pour répondre avec certitude, dis-le clairement plutôt \
            que de compléter de mémoire.
            - Tiens compte du profil (ancienneté, statut, classification, contrat, situations). Si une \
            information manquante change la réponse, explique ce qu'elle change.
            - Quand le Code et la convention prévoient des règles différentes, présente les deux et \
            applique la plus favorable au salarié, en le disant.
            - Donne les chiffres utiles (durées, montants, pourcentages) et les conditions pour en \
            bénéficier ; fais les calculs simples à partir du profil en montrant le raisonnement.
            - Commence par la réponse directe en une ou deux phrases, puis les détails.
            - Termine en rappelant qu'il s'agit d'une information générale et qu'en cas de litige, \
            l'inspection du travail, un syndicat ou un avocat peuvent aider.""";

    private final ClaudeProperties properties;
    private final Supplier<AnthropicClient> fabrique;
    private volatile AnthropicClient client;
    private final AtomicLong appels = new AtomicLong();
    private final AtomicLong jetonsEntree = new AtomicLong();
    private final AtomicLong jetonsSortie = new AtomicLong();

    /** Consommation cumulée depuis le démarrage (mesure du coût réel, évaluation). */
    public record Consommation(long appels, long jetonsEntree, long jetonsSortie) {
    }

    public Consommation consommation() {
        return new Consommation(appels.get(), jetonsEntree.get(), jetonsSortie.get());
    }

    @Autowired
    public AssistantClaude(ClaudeProperties properties) {
        this(properties, () -> AnthropicOkHttpClient.builder()
                .fromEnv()
                .timeout(properties.delai())
                .maxRetries(properties.tentatives())
                .build());
    }

    /** Pour les tests : client fourni (simulé). */
    AssistantClaude(ClaudeProperties properties, Supplier<AnthropicClient> fabrique) {
        this.properties = properties;
        this.fabrique = fabrique;
    }

    /** Requêtes de recherche produites par la reformulation (sortie structurée). */
    record Requetes(List<String> requetes) {
    }

    @Override
    public List<String> reformuler(String question, ContexteProfil contexte) {
        StructuredMessageCreateParams<Requetes> params = parametresReformulation(question, contexte);
        StructuredMessage<Requetes> message;
        try {
            message = client().messages().create(params);
        } catch (AnthropicException e) {
            log.warn("Reformulation impossible ({}) : recherche sur la question brute", e.getClass().getSimpleName());
            return List.of(question);
        }
        journaliser("reformulation", message.usage());
        if (message.stopReason().filter(StopReason.REFUSAL::equals).isPresent()) {
            log.warn("Reformulation refusée par le modèle : recherche sur la question brute");
            return List.of(question);
        }
        List<String> requetes = message.content().stream()
                .flatMap(bloc -> bloc.text().stream())
                .flatMap(texte -> texte.text().requetes().stream())
                .filter(r -> r != null && !r.isBlank())
                .map(String::strip)
                .distinct()
                .limit(REQUETES_MAX)
                .toList();
        return requetes.isEmpty() ? List.of(question) : requetes;
    }

    @Override
    public Reponse rediger(String question, ContexteProfil contexte, List<ArticleDetail> articles) {
        Message message;
        try {
            message = client().messages().create(parametresRedaction(question, contexte, articles));
        } catch (AnthropicException e) {
            log.error("Rédaction impossible : {}", e.getClass().getSimpleName());
            throw new AssistantIndisponibleException(e);
        }
        journaliser("rédaction", message.usage());
        return versReponse(message, articles);
    }

    /** Requête de reformulation : sortie structurée, schéma déduit de {@link Requetes}. */
    StructuredMessageCreateParams<Requetes> parametresReformulation(String question, ContexteProfil contexte) {
        return MessageCreateParams.builder()
                .model(properties.modele())
                .maxTokens(MAX_TOKENS)
                .system(CONSIGNES_REFORMULATION)
                .outputConfig(Requetes.class)
                .addUserMessage(DescriptionProfil.decrire(contexte) + "\n\nQuestion du salarié : " + question)
                .putAdditionalHeader("anthropic-beta", BETA_REPLI)
                .putAdditionalBodyProperty("fallbacks", JsonValue.from("default"))
                .build();
    }

    /** Requête de rédaction : un document par article, dans l'ordre, puis le profil et la question. */
    MessageCreateParams parametresRedaction(String question, ContexteProfil contexte, List<ArticleDetail> articles) {
        List<ContentBlockParam> blocs = new ArrayList<>();
        for (ArticleDetail a : articles) {
            blocs.add(ContentBlockParam.ofDocument(DocumentBlockParam.builder()
                    .source(PlainTextSource.builder().data(contenu(a)).build())
                    .title(a.num() + " (" + a.texte().titre() + ")")
                    .context(filAriane(a))
                    .citations(CitationsConfigParam.builder().enabled(true).build())
                    .build()));
        }
        blocs.add(ContentBlockParam.ofText(DescriptionProfil.decrire(contexte) + "\n\nQuestion du salarié : " + question));
        return MessageCreateParams.builder()
                .model(properties.modele())
                .maxTokens(MAX_TOKENS)
                .system(CONSIGNES_REDACTION)
                // Réponses juridiques : niveau de réflexion élevé (le défaut du modèle est moyen)
                .outputConfig(OutputConfig.builder().effort(OutputConfig.Effort.HIGH).build())
                .addUserMessageOfContentBlockParams(blocs)
                .putAdditionalHeader("anthropic-beta", BETA_REPLI)
                .putAdditionalBodyProperty("fallbacks", JsonValue.from("default"))
                .build();
    }

    /**
     * Passages de texte de la réponse et leurs citations, rapportées aux articles par l'index du
     * document ; les blocs de réflexion sont ignorés.
     */
    static Reponse versReponse(Message message, List<ArticleDetail> articles) {
        if (message.stopReason().filter(StopReason.REFUSAL::equals).isPresent()) {
            log.warn("Rédaction refusée par le modèle");
            return new Reponse(List.of(), true, false);
        }
        // Toute autre fin qu'une fin normale (limite de jetons, pause...) laisse une réponse inachevée
        boolean tronquee = message.stopReason().filter(r -> !StopReason.END_TURN.equals(r)).isPresent();
        if (tronquee) {
            log.warn("Rédaction inachevée : {}", message.stopReason().get());
        }
        List<Passage> passages = message.content().stream()
                .flatMap(bloc -> bloc.text().stream())
                .map(texte -> new Passage(texte.text(), texte.citations().orElse(List.of()).stream()
                        .flatMap(c -> citation(c, articles).stream())
                        .toList()))
                .toList();
        return new Reponse(passages, false, tronquee);
    }

    private static Optional<Citation> citation(TextCitation c, List<ArticleDetail> articles) {
        return c.documentIndex()
                .filter(i -> i >= 0 && i < articles.size())
                .map(i -> articles.get(i.intValue()))
                .map(a -> new Citation(a.sourceId(), a.num(), c.citedText()));
    }

    /**
     * Texte de l'article : en clair (Code) ou HTML débarrassé de ses balises et entités (conventions),
     * suivi du nota, qui porte souvent des conditions d'application.
     */
    public static String contenu(ArticleDetail a) {
        String texte = a.contenuTexte() != null ? a.contenuTexte() : enClair(a.contenuHtml());
        String nota = enClair(a.nota());
        return nota.isEmpty() ? texte : texte + "\n\nNota : " + nota;
    }

    private static String enClair(String html) {
        if (html == null) {
            return "";
        }
        return HtmlUtils.htmlUnescape(html.replaceAll("<[^>]+>", " ")).replace('\u00A0', ' ')
                .replaceAll("\\s+", " ").strip();
    }

    private static String filAriane(ArticleDetail a) {
        List<String> titres = a.filAriane().stream().map(SectionResume::titre).toList();
        return titres.isEmpty() ? a.texte().titre() : String.join(" > ", titres);
    }

    private void journaliser(String etape, Usage usage) {
        appels.incrementAndGet();
        jetonsEntree.addAndGet(usage.inputTokens());
        jetonsSortie.addAndGet(usage.outputTokens());
        log.info("Claude, {} : {} jetons en entrée, {} en sortie", etape, usage.inputTokens(), usage.outputTokens());
    }

    /** Client créé au premier appel ; une clé absente lève une {@link AnthropicException}. */
    private AnthropicClient client() {
        AnthropicClient c = client;
        if (c == null) {
            synchronized (this) {
                if (client == null) {
                    client = fabrique.get();
                }
                c = client;
            }
        }
        return c;
    }
}
