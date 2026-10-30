package fr.deseille.social.questions;

import fr.deseille.social.consultation.VuesConsultation.ArticleDetail;
import fr.deseille.social.profil.ProfilService.ContexteProfil;
import fr.deseille.social.questions.AssistantClaude.Requetes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@link Assistant} adossé à un modèle local servi par Ollama (Spring AI) : la question et le profil
 * ne quittent pas la machine qui héberge le modèle, et l'appel est gratuit. Choisi par
 * {@code droits.assistant.fournisseur=ollama} ; l'adresse et le modèle se règlent par
 * {@code spring.ai.ollama.base-url} et {@code spring.ai.ollama.chat.model}.
 * <p>
 * Ollama n'a pas de citations natives : les deux étapes demandent une sortie JSON imposée par un
 * schéma (format structuré d'Ollama, qui contraint la génération). Les schémas sont écrits à la
 * main : le générateur de Spring AI ({@code BeanOutputConverter}) dépend d'une version de victools
 * incompatible avec celle du SDK Anthropic (voir le pom). La rédaction renvoie des
 * passages, chacun avec le numéro du document cité et l'extrait recopié ; ces extraits passent
 * ensuite, comme ceux de Claude, par {@link VerificationCitations}, garde-fou indispensable avec
 * un petit modèle qui peut paraphraser au lieu de recopier.
 * <p>
 * Une erreur d'appel fait chercher sur la question brute (reformulation) ou renvoie un 503
 * (rédaction), comme pour Claude ; une rédaction illisible renvoie aussi un 503, sauf si elle a été
 * coupée par la limite de jetons (réponse signalée tronquée).
 */
@Component
@ConditionalOnProperty(name = "droits.assistant.fournisseur", havingValue = "ollama")
public class AssistantOllama implements Assistant {

    private static final Logger log = LoggerFactory.getLogger(AssistantOllama.class);
    private static final String FOURNISSEUR = "ollama";

    static final String CONSIGNES_CITATIONS = """
            Format de la réponse : les articles sont numérotés (document 1, document 2…). Découpe ta \
            réponse en passages, dans l'ordre de lecture. Pour chaque passage qui énonce une règle, \
            indique dans « citations » le numéro du document et un extrait recopié mot pour mot, sans \
            rien changer (une phrase ou un membre de phrase de l'article). Un passage de liaison n'a \
            pas de citation. N'invente jamais d'extrait.""";

    /**
     * Exemples pour les petits modèles, qui recopient sinon le profil dans les requêtes (« idcc 1486
     * cdi cadre… ») et les allongent. Thèmes choisis hors du jeu d'évaluation, pour ne pas le fausser.
     */
    static final String EXEMPLES_REFORMULATION = """
            N'ajoute jamais aux requêtes le numéro de convention, le type de contrat ni d'autres \
            éléments du profil : ils servent déjà à choisir les textes. Exemples :
            - « Je suis cadre en CDI chez un employeur Syntec, puis-je demander du télétravail ? » → \
            {"requetes": ["télétravail", "télétravail accord", "télétravail refus employeur"]}
            - « Salariée en CDI depuis 5 ans, comment se passe une rupture conventionnelle ? » → \
            {"requetes": ["rupture conventionnelle", "rupture conventionnelle indemnité", "homologation rupture"]}
            - « Je travaille la nuit dans un hôtel, ai-je droit à une contrepartie ? » → \
            {"requetes": ["travail nuit contrepartie", "repos compensateur nuit"]}""";

    /** Mots vides, toujours retirés des requêtes : la recherche les exigerait. */
    private static final Set<String> MOTS_VIDES = Set.of(
            "idcc", "le", "la", "les", "l", "de", "du", "des", "d", "un", "une", "et", "ou", "en", "à", "au",
            "aux", "pour", "par", "sur", "mon", "ma", "mes", "je", "suis", "est", "que", "qui");

    /**
     * Mots que les petits modèles recopient du profil : retirés, sauf quand la question elle-même les
     * emploie (« prime de fin de CDD », « convention de forfait »).
     */
    private static final Set<String> MOTS_DU_PROFIL = Set.of("convention", "collective", "cdi", "cdd", "non");

    /** Mots par requête au plus : la recherche exige tous les mots d'une requête. */
    private static final int MOTS_MAX = 3;

    /** Schéma de {@link Requetes}. */
    static final Map<String, Object> SCHEMA_REQUETES = Map.of(
            "type", "object",
            "properties", Map.of("requetes", Map.of("type", "array", "items", Map.of("type", "string"),
                    "minItems", 1, "maxItems", 4)),
            "required", List.of("requetes"));

    /** Schéma de {@link Redaction}. */
    static final Map<String, Object> SCHEMA_REDACTION = Map.of(
            "type", "object",
            "properties", Map.of("passages", Map.of("type", "array", "items", Map.of(
                    "type", "object",
                    "properties", Map.of(
                            "texte", Map.of("type", "string"),
                            "citations", Map.of("type", "array", "items", Map.of(
                                    "type", "object",
                                    "properties", Map.of(
                                            "document", Map.of("type", "integer", "minimum", 1),
                                            "extrait", Map.of("type", "string")),
                                    "required", List.of("document", "extrait")))),
                    "required", List.of("texte", "citations")))),
            "required", List.of("passages"));

    private static final JsonMapper JSON = JsonMapper.builder()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();

    /** Réponse de rédaction attendue. */
    record Redaction(List<PassageRedige> passages) {
    }

    record PassageRedige(String texte, List<CitationRedigee> citations) {
    }

    /** @param document numéro du document cité, à partir de 1 */
    record CitationRedigee(Integer document, String extrait) {
    }

    private final OllamaChatModel modele;
    private final MetriquesQuestions metriques;
    private final AtomicLong appels = new AtomicLong();
    private final AtomicLong jetonsEntree = new AtomicLong();
    private final AtomicLong jetonsSortie = new AtomicLong();

    public AssistantOllama(OllamaChatModel modele, MetriquesQuestions metriques) {
        this.modele = modele;
        this.metriques = metriques;
    }

    /**
     * Quatre articles au lieu de huit : sur un processeur sans carte graphique, la lecture des
     * articles domine la durée de rédaction (au-delà de 10 minutes avec huit articles et un 7B).
     */
    @Override
    public int articlesMax() {
        return 4;
    }

    @Override
    public String modele() {
        return modele.getOptions().getModel();
    }

    @Override
    public Consommation consommation() {
        return new Consommation(appels.get(), jetonsEntree.get(), jetonsSortie.get());
    }

    @Override
    public List<String> reformuler(String question, ContexteProfil contexte) {
        ChatResponse reponse;
        try {
            reponse = modele.call(new Prompt(List.of(new SystemMessage(AssistantClaude.CONSIGNES_REFORMULATION + "\n\n" + EXEMPLES_REFORMULATION),
                    new UserMessage(DescriptionProfil.decrire(contexte) + "\n\nQuestion du salarié : " + question)),
                    options(SCHEMA_REQUETES)));
        } catch (RuntimeException e) {
            metriques.appelModele(FOURNISSEUR, "reformulation", "erreur");
            log.warn("Reformulation impossible ({}) : recherche sur la question brute", e.getClass().getSimpleName());
            return List.of(question);
        }
        journaliser("reformulation", reponse);
        try {
            List<String> requetes = AssistantClaude.nettoyer(
                    simplifier(JSON.readValue(texte(reponse), Requetes.class).requetes(), question), question);
            metriques.appelModele(FOURNISSEUR, "reformulation", "succes");
            return requetes;
        } catch (RuntimeException e) {
            metriques.appelModele(FOURNISSEUR, "reformulation", "illisible");
            log.warn("Reformulation illisible : recherche sur la question brute");
            return List.of(question);
        }
    }

    @Override
    public Reponse rediger(String question, ContexteProfil contexte, List<ArticleDetail> articles) {
        ChatResponse reponse;
        try {
            reponse = modele.call(prompt(question, contexte, articles));
        } catch (RuntimeException e) {
            metriques.appelModele(FOURNISSEUR, "redaction", "erreur");
            log.error("Rédaction impossible : {}", e.getClass().getSimpleName());
            throw new AssistantIndisponibleException(e);
        }
        journaliser("redaction", reponse);
        // Ollama indique « length » quand la limite de jetons (num_predict) a coupé la génération
        boolean tronquee = "length".equalsIgnoreCase(finDeGeneration(reponse));
        Redaction redaction;
        try {
            redaction = JSON.readValue(texte(reponse), Redaction.class);
        } catch (RuntimeException e) {
            if (tronquee) {
                metriques.appelModele(FOURNISSEUR, "redaction", "tronquee");
                log.warn("Rédaction coupée par la limite de jetons, JSON inachevé");
                return new Reponse(List.of(), false, true);
            }
            metriques.appelModele(FOURNISSEUR, "redaction", "illisible");
            log.error("Rédaction illisible : réponse hors du format demandé");
            throw new AssistantIndisponibleException(e);
        }
        metriques.appelModele(FOURNISSEUR, "redaction", tronquee ? "tronquee" : "succes");
        return new Reponse(passages(redaction, articles), false, tronquee);
    }

    /**
     * Requêtes ramenées à des mots-clés : sans chiffres ni mots vides, sans les mots recopiés du
     * profil (IDCC, « convention collective », type de contrat) quand la question ne les emploie pas,
     * trois mots au plus. Garde-fou propre aux petits modèles, dont les requêtes trop longues ne
     * trouvent aucun article ; celles de Claude ne passent pas par là.
     */
    static List<String> simplifier(List<String> requetes, String question) {
        if (requetes == null) {
            return List.of();
        }
        Set<String> motsDeLaQuestion = Set.copyOf(Arrays.asList(
                (question == null ? "" : question).toLowerCase(Locale.FRENCH).split("[^\\p{L}]+")));
        List<String> simplifiees = new ArrayList<>();
        for (String requete : requetes) {
            if (requete == null) {
                continue;
            }
            simplifiees.add(Arrays.stream(requete.toLowerCase(Locale.FRENCH).split("[^\\p{L}]+"))
                    .filter(mot -> !mot.isEmpty() && !MOTS_VIDES.contains(mot))
                    .filter(mot -> !MOTS_DU_PROFIL.contains(mot) || motsDeLaQuestion.contains(mot))
                    .limit(MOTS_MAX)
                    .collect(Collectors.joining(" ")));
        }
        return simplifiees;
    }

    /**
     * Consignes de Claude complétées du format de citation ; chaque article est un document
     * numéroté, avec son emplacement dans le texte, puis viennent le profil et la question.
     */
    Prompt prompt(String question, ContexteProfil contexte, List<ArticleDetail> articles) {
        StringBuilder documents = new StringBuilder();
        for (int i = 0; i < articles.size(); i++) {
            ArticleDetail a = articles.get(i);
            documents.append("Document ").append(i + 1).append(" : article ").append(a.num())
                    .append(" (").append(a.texte().titre()).append(")\n")
                    .append("Emplacement : ").append(AssistantClaude.filAriane(a)).append('\n')
                    .append(AssistantClaude.contenu(a)).append("\n\n");
        }
        return new Prompt(List.of(
                new SystemMessage(AssistantClaude.CONSIGNES_REDACTION + "\n\n" + CONSIGNES_CITATIONS),
                new UserMessage(documents + DescriptionProfil.decrire(contexte) + "\n\nQuestion du salarié : " + question)),
                options(SCHEMA_REDACTION));
    }

    /**
     * Passages de la réponse, citations rapportées aux articles par leur numéro de document ; une
     * citation vers un document inexistant est écartée (le modèle s'est trompé de numéro).
     */
    static List<Passage> passages(Redaction redaction, List<ArticleDetail> articles) {
        List<Passage> passages = new ArrayList<>();
        for (PassageRedige p : Objects.requireNonNullElse(redaction.passages(), List.<PassageRedige>of())) {
            if (p == null || p.texte() == null || p.texte().isBlank()) {
                continue;
            }
            List<Citation> citations = new ArrayList<>();
            for (CitationRedigee c : Objects.requireNonNullElse(p.citations(), List.<CitationRedigee>of())) {
                if (c == null || c.document() == null || c.document() < 1 || c.document() > articles.size()) {
                    log.warn("Citation écartée : document inexistant");
                    continue;
                }
                ArticleDetail a = articles.get(c.document() - 1);
                citations.add(new Citation(a.sourceId(), a.num(), c.extrait()));
            }
            // Un espace entre passages : le client les met bout à bout, comme les blocs de Claude
            passages.add(new Passage(p.texte().strip() + " ", citations));
        }
        return passages;
    }

    /**
     * Options configurées ({@code spring.ai.ollama.chat.*}), plus la sortie contrainte par le schéma
     * JSON de la réponse attendue. Spring AI ne fusionne pas : des options passées à l'appel
     * remplacent celles de la configuration (et le modèle par défaut du builder est « mistral »).
     */
    private OllamaChatOptions options(Map<String, Object> schema) {
        return modele.getOptions().mutate().format(schema).build();
    }

    private static String texte(ChatResponse reponse) {
        Generation resultat = reponse.getResult();
        return resultat == null || resultat.getOutput().getText() == null ? "" : resultat.getOutput().getText();
    }

    private static String finDeGeneration(ChatResponse reponse) {
        Generation resultat = reponse.getResult();
        return resultat == null ? null : resultat.getMetadata().getFinishReason();
    }

    private void journaliser(String etape, ChatResponse reponse) {
        Usage usage = reponse.getMetadata().getUsage();
        long entree = usage == null || usage.getPromptTokens() == null ? 0 : usage.getPromptTokens();
        long sortie = usage == null || usage.getCompletionTokens() == null ? 0 : usage.getCompletionTokens();
        appels.incrementAndGet();
        jetonsEntree.addAndGet(entree);
        jetonsSortie.addAndGet(sortie);
        metriques.jetons(FOURNISSEUR, etape, entree, sortie);
        log.info("Ollama, {} : {} jetons en entrée, {} en sortie", etape, entree, sortie);
    }
}
