package fr.deseille.social.questions;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.JsonValue;
import com.anthropic.core.ObjectMappers;
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
import com.anthropic.models.messages.TextBlock;
import com.anthropic.models.messages.TextCitation;
import com.anthropic.models.messages.Usage;
import fr.deseille.social.consultation.VuesConsultation.ArticleDetail;
import fr.deseille.social.consultation.VuesConsultation.SectionResume;
import fr.deseille.social.profil.ProfilService.ContexteProfil;
import io.github.anthonydeseille.llmeval4j.pricing.PriceTable;
import io.github.anthonydeseille.llmeval4j.prompts.Prompts;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.DoubleAdder;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
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
@ConditionalOnProperty(name = "droits.assistant.fournisseur", havingValue = "claude", matchIfMissing = true)
public class AssistantClaude implements Assistant {

    private static final Logger log = LoggerFactory.getLogger(AssistantClaude.class);
    private static final String FOURNISSEUR = "claude";

    private static final long MAX_TOKENS = 16_000L;
    private static final int REQUETES_MAX = 4;
    private static final String BETA_REPLI = "server-side-fallback-2026-07-01";

    /**
     * Prix des modèles (llmeval4j, valeurs datées de {@link PriceTable#DEFAULTS_AS_OF}) : coût de chaque
     * appel, budget journalier et coût des évaluations. Un modèle absent a un coût inconnu
     * ({@code NaN}), jamais un coût faux ; ajouter son prix ici ({@link PriceTable#with}).
     */
    public static final PriceTable PRIX = PriceTable.defaults();

    static final String CONSIGNES_REFORMULATION = Consignes.lire("reformulation");
    static final String CONSIGNES_REDACTION = Consignes.lire("redaction");

    /** Versions des consignes de Claude, pour l'évaluation de la recherche (requêtes figées périmées). */
    public static final VersionsConsignes CONSIGNES = new VersionsConsignes(
            Prompts.version(CONSIGNES_REFORMULATION), Prompts.version(CONSIGNES_REDACTION));

    private final ClaudeProperties properties;
    private final Supplier<AnthropicClient> fabrique;
    private final MetriquesQuestions metriques;
    private volatile AnthropicClient client;
    private final AtomicLong appels = new AtomicLong();
    private final AtomicLong jetonsEntree = new AtomicLong();
    private final AtomicLong jetonsSortie = new AtomicLong();
    private final DoubleAdder cout = new DoubleAdder();
    private final DepensesJournalieres depenses;
    private final java.util.Set<String> sansPrix = java.util.concurrent.ConcurrentHashMap.newKeySet();

    @Override
    public Consommation consommation() {
        return new Consommation(appels.get(), jetonsEntree.get(), jetonsSortie.get(), cout.sum());
    }

    @Autowired
    public AssistantClaude(ClaudeProperties properties, MetriquesQuestions metriques, DepensesJournalieres depenses) {
        this(properties, () -> AnthropicOkHttpClient.builder()
                .fromEnv()
                .timeout(properties.delai())
                .maxRetries(properties.tentatives())
                .build(), metriques, depenses);
    }

    /** Hors application (outils, tests) : dépense suivie, sans budget. */
    public AssistantClaude(ClaudeProperties properties, MetriquesQuestions metriques) {
        this(properties, metriques, DepensesJournalieres.sansBudget());
    }

    @Override
    public String modele() {
        return properties.modele();
    }

    @Override
    public String modeleReformulation() {
        return properties.modeleDeReformulation();
    }

    @Override
    public VersionsConsignes consignes() {
        return CONSIGNES;
    }

    /** Pour les tests : client fourni (simulé). */
    AssistantClaude(ClaudeProperties properties, Supplier<AnthropicClient> fabrique, MetriquesQuestions metriques) {
        this(properties, fabrique, metriques, DepensesJournalieres.sansBudget());
    }

    AssistantClaude(ClaudeProperties properties, Supplier<AnthropicClient> fabrique, MetriquesQuestions metriques,
                    DepensesJournalieres depenses) {
        this.properties = properties;
        this.fabrique = fabrique;
        this.metriques = metriques;
        this.depenses = depenses;
        signalerSansPrix(properties.modele());
        signalerSansPrix(properties.modeleDeReformulation());
    }

    /**
     * Un modèle sans prix connu échappe au budget journalier : signalé une fois par modèle (au
     * démarrage pour les modèles configurés). Pas d'échec : on doit pouvoir évaluer un nouveau
     * modèle avant d'en saisir le prix.
     */
    private void signalerSansPrix(String modele) {
        if (PRIX.find(modele).isEmpty() && sansPrix.add(modele)) {
            log.warn("Modèle {} sans prix (AssistantClaude.PRIX) : coût inconnu, ses appels ne comptent pas dans le budget "
                    + "journalier ; ajouter son prix", modele);
        }
    }

    /** Requêtes de recherche produites par la reformulation (sortie structurée). */
    public record Requetes(List<String> requetes) {
    }

    @Override
    public List<String> reformuler(String question, ContexteProfil contexte) {
        StructuredMessageCreateParams<Requetes> params = parametresReformulation(question, contexte);
        StructuredMessage<Requetes> message;
        try {
            message = client().messages().create(params);
        } catch (AnthropicException e) {
            metriques.appelModele(FOURNISSEUR, "reformulation", "erreur");
            log.warn("Reformulation impossible ({}) : recherche sur la question brute", e.getClass().getSimpleName());
            return List.of(question);
        }
        journaliser("reformulation", properties.modeleDeReformulation(), message.usage());
        boolean refusee = message.stopReason().filter(StopReason.REFUSAL::equals).isPresent();
        metriques.appelModele(FOURNISSEUR, "reformulation", refusee ? "refus" : "succes");
        if (refusee) {
            log.warn("Reformulation refusée par le modèle : recherche sur la question brute");
            return List.of(question);
        }
        return nettoyer(message.content().stream()
                .flatMap(bloc -> bloc.text().stream())
                .flatMap(texte -> texte.text().requetes().stream())
                .toList(), question);
    }

    /**
     * Requêtes d'une réponse de reformulation reçue comme message ordinaire (résultat d'un lot,
     * où le SDK ne désérialise pas la sortie structurée) ; la question brute si le modèle a refusé
     * ou si la sortie est illisible.
     */
    public static List<String> requetesDepuis(Message message, String question) {
        if (message.stopReason().filter(StopReason.REFUSAL::equals).isPresent()) {
            return List.of(question);
        }
        String json = message.content().stream().flatMap(bloc -> bloc.text().stream())
                .map(TextBlock::text).collect(Collectors.joining());
        try {
            return nettoyer(ObjectMappers.jsonMapper().readValue(json, Requetes.class).requetes(), question);
        } catch (Exception e) {
            log.warn("Reformulation illisible : recherche sur la question brute");
            return List.of(question);
        }
    }

    /** Requêtes non vides, sans doublon, au plus quatre ; la question brute s'il n'en reste aucune. */
    static List<String> nettoyer(List<String> brutes, String question) {
        List<String> requetes = (brutes == null ? List.<String>of() : brutes).stream()
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
            metriques.appelModele(FOURNISSEUR, "redaction", "erreur");
            log.error("Rédaction impossible : {}", e.getClass().getSimpleName());
            throw new AssistantIndisponibleException(e);
        }
        journaliser("redaction", properties.modele(), message.usage());
        Reponse reponse = versReponse(message, articles);
        metriques.appelModele(FOURNISSEUR, "redaction", reponse.refus() ? "refus" : reponse.tronquee() ? "tronquee" : "succes");
        return reponse;
    }

    /** Requête de reformulation : sortie structurée, schéma déduit de {@link Requetes}. Publique pour les lots. */
    public StructuredMessageCreateParams<Requetes> parametresReformulation(String question, ContexteProfil contexte) {
        return MessageCreateParams.builder()
                .model(properties.modeleDeReformulation())
                .maxTokens(MAX_TOKENS)
                .system(CONSIGNES_REFORMULATION)
                .outputConfig(Requetes.class)
                .addUserMessage(DescriptionProfil.decrire(contexte) + "\n\nQuestion du salarié : " + question)
                .putAdditionalHeader("anthropic-beta", BETA_REPLI)
                .putAdditionalBodyProperty("fallbacks", JsonValue.from("default"))
                .build();
    }

    /** Requête de rédaction : un document par article, dans l'ordre, puis le profil et la question. Publique pour les lots. */
    public MessageCreateParams parametresRedaction(String question, ContexteProfil contexte, List<ArticleDetail> articles) {
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
    public static Reponse versReponse(Message message, List<ArticleDetail> articles) {
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

    static String filAriane(ArticleDetail a) {
        List<String> titres = a.filAriane().stream().map(SectionResume::titre).toList();
        return titres.isEmpty() ? a.texte().titre() : String.join(" > ", titres);
    }

    private void journaliser(String etape, String modele, Usage usage) {
        appels.incrementAndGet();
        jetonsEntree.addAndGet(usage.inputTokens());
        jetonsSortie.addAndGet(usage.outputTokens());
        metriques.jetons(FOURNISSEUR, etape, usage.inputTokens(), usage.outputTokens());
        signalerSansPrix(modele);
        double dollars = PRIX.cost(modele, usage.inputTokens(), usage.outputTokens());
        cout.add(dollars);
        metriques.cout(FOURNISSEUR, etape, dollars);
        depenses.ajouter(dollars);
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
