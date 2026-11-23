package fr.deseille.social.questions;

import fr.deseille.social.consultation.ConsultationService;
import fr.deseille.social.consultation.VuesConsultation.ArticleDetail;
import fr.deseille.social.profil.ProfilIncoherentException;
import fr.deseille.social.profil.ProfilSalarie;
import fr.deseille.social.profil.ProfilService;
import fr.deseille.social.profil.ProfilService.ContexteProfil;
import fr.deseille.social.questions.Assistant.Citation;
import fr.deseille.social.questions.Assistant.Passage;
import fr.deseille.social.recherche.RechercheService;
import fr.deseille.social.recherche.RechercheService.Resultat;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Répond à la question d'un salarié : garde-fous d'entrée (injection refusée, données personnelles
 * retirées), contexte du profil, reformulation en requêtes, recherche dans le Code et la convention,
 * calcul par le code quand la question en appelle un (indemnité de licenciement), rédaction à partir
 * des articles retenus et du calcul, avec citations, puis contrôles de sortie (citations, chiffres,
 * troncature).
 */
@Service
public class QuestionService {

    private static final Logger log = LoggerFactory.getLogger(QuestionService.class);

    private static final int RESULTATS_PAR_REQUETE = 10;
    /**
     * Part des articles transmis réservée à la convention collective, quand elle en a de pertinents :
     * sans réserve, les sections du Code occupent toutes les places et des articles décisifs de la
     * convention (indemnité, préavis) ne sont jamais transmis (évaluations du 31 octobre et du 23
     * novembre 2026). Choisie avec l'évaluation gratuite de la recherche ({@code RechercheEvaluationIT}).
     */
    static final double PART_CONVENTION = 2.0 / 8;
    /**
     * Place donnée à un article trouvé par la recherche sémantique seule, quand elle est active. Choisie
     * avec l'évaluation gratuite de la recherche (bge-m3, 23 novembre 2026) : 1 place, 28 → 32 articles
     * attendus sur 48, stable de 0,45 à 0,55 de similarité ; 2 ou 3 places : 30 (les articles trouvés par
     * le sens évincent de bons articles trouvés par les mots).
     */
    static final int PLACES_SEMANTIQUE = 1;
    /** Similarité cosinus minimale d'un article trouvé par le sens. */
    static final double SIMILARITE_MIN = 0.5;
    /**
     * Pertinence minimale d'un article de la convention pour une place réservée, en part de celle du
     * dernier article qui aurait été retenu sans réserve : un article de la convention à peine lié à la
     * question ne doit pas évincer un article du Code bien classé.
     */
    static final double PERTINENCE_MIN_CONVENTION = 0.5;

    private final ProfilService profilService;
    private final RechercheService rechercheService;
    private final ConsultationService consultationService;
    private final Assistant assistant;
    private final MetriquesQuestions metriques;
    private final CalculsQuestions calculs;

    public QuestionService(ProfilService profilService, RechercheService rechercheService,
                           ConsultationService consultationService, Assistant assistant,
                           MetriquesQuestions metriques, CalculsQuestions calculs) {
        this.profilService = profilService;
        this.rechercheService = rechercheService;
        this.consultationService = consultationService;
        this.assistant = assistant;
        this.metriques = metriques;
        this.calculs = calculs;
        metriques.consignes(assistant.modele(), assistant.consignes());
    }

    /**
     * Toute question est comptée dans {@code droits.questions}, y compris quand elle échoue :
     * invalide (requête refusée), indisponible (modèle), erreur (imprévue).
     */
    public ReponseQuestion repondre(String question, ProfilSalarie profil) {
        try {
            return traiter(question, profil);
        } catch (AssistantIndisponibleException e) {
            metriques.question("indisponible");
            throw e;
        } catch (ResponseStatusException e) {
            if (e instanceof ProfilIncoherentException) {
                metriques.gardeFou("profil_incoherent", 1);
            }
            metriques.question(e.getStatusCode().is4xxClientError() ? "invalide" : "erreur");
            throw e;
        } catch (RuntimeException e) {
            metriques.question("erreur");
            throw e;
        }
    }

    private ReponseQuestion traiter(String questionSaisie, ProfilSalarie profilSaisi) {
        // Garde-fous d'entrée, avant tout appel payant : tentative d'injection refusée, données
        // personnelles du texte libre retirées (le modèle reçoit le texte masqué)
        refuserInjection(questionSaisie);
        refuserInjection(profilSaisi.classification());
        DonneesPersonnelles.Masquage masqueQuestion = DonneesPersonnelles.masquer(questionSaisie);
        DonneesPersonnelles.Masquage masqueClassification = DonneesPersonnelles.masquer(profilSaisi.classification());
        String question = masqueQuestion.texte();
        ProfilSalarie profil = masqueClassification.aucun() ? profilSaisi : new ProfilSalarie(profilSaisi.idcc(),
                profilSaisi.dateEntree(), profilSaisi.age(), profilSaisi.statut(), masqueClassification.texte(),
                profilSaisi.typeContrat(), profilSaisi.tempsPartiel(), profilSaisi.situations(), profilSaisi.dateReference());
        ContexteProfil contexte = avecRetraits(profilService.contexte(profil), masqueQuestion, masqueClassification);

        // Le modèle raisonne à la date de référence : la recherche et l'applicabilité aussi
        LocalDate date = contexte.profil().dateReference();
        List<String> requetes = metriques.chronometrer("reformulation", () -> {
            metriques.annoter("modele", assistant.modele());
            metriques.annoter("consignes", assistant.consignes().reformulation());
            List<String> r = assistant.reformuler(question, contexte);
            metriques.annoter("requetes", r.size());
            return r;
        });

        List<ArticleDetail> articles = metriques.chronometrer("recherche", () -> {
            List<ArticleDetail> a = articles(question, requetes, profil.idcc(), date);
            metriques.annoter("articles", a.size());
            metriques.annoter("convention", profil.idcc() != null);
            return a;
        });
        CalculQuestion calcul = calcul(question, contexte);
        if (articles.isEmpty()) {
            return sansArticle(question, contexte, requetes, calcul);
        }

        Assistant.Reponse reponse = metriques.chronometrer("redaction", () -> {
            metriques.annoter("modele", assistant.modele());
            metriques.annoter("consignes", assistant.consignes().redaction());
            metriques.annoter("calcul", calcul != null);
            return assistant.rediger(question, contexte, articles, calcul);
        });
        return assembler(question, contexte, requetes, articles, reponse, calcul);
    }

    private void refuserInjection(String texte) {
        InjectionConsignes.detecter(texte).ifPresent(extrait -> {
            metriques.gardeFou("injection", 1);
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "La question ressemble à une instruction adressée "
                    + "à l'assistant plutôt qu'à une question sur vos droits : reformulez-la.");
        });
    }

    /** Contexte complété d'un avertissement quand des données personnelles ont été retirées. */
    private ContexteProfil avecRetraits(ContexteProfil contexte, DonneesPersonnelles.Masquage... masquages) {
        Set<String> natures = new LinkedHashSet<>();
        for (DonneesPersonnelles.Masquage m : masquages) {
            m.retraits().forEach((nature, nombre) -> {
                natures.add(nature);
                metriques.gardeFou("donnees_personnelles", nombre);
            });
        }
        if (natures.isEmpty()) {
            return contexte;
        }
        List<String> avertissements = new ArrayList<>(contexte.avertissements());
        avertissements.add("Données personnelles retirées avant l'envoi au modèle (" + String.join(", ", natures)
                + ") : elles ne sont pas nécessaires pour connaître vos droits.");
        return new ContexteProfil(contexte.profil(), contexte.anciennete(), contexte.textesApplicables(),
                contexte.conventionDisponible(), avertissements);
    }

    /**
     * Articles retenus pour des requêtes : recherche, fusion, puis détail des meilleurs, avec leur
     * applicabilité à la date donnée. Étape publique pour l'évaluation par lots.
     */
    public List<ArticleDetail> articles(List<String> requetes, Integer idcc, LocalDate date) {
        return articles(null, requetes, idcc, date);
    }

    /**
     * Idem, avec la question pour la recherche sémantique (si elle est active) ; {@code null} : les
     * requêtes, mises bout à bout.
     */
    public List<ArticleDetail> articles(String question, List<String> requetes, Integer idcc, LocalDate date) {
        return selectionner(question, requetes, idcc, date).stream()
                .map(r -> consultationService.article(r.articleSourceId(), date))
                .toList();
    }

    /**
     * Calcul fait par le code pour la question (gratuit), {@code null} si elle n'en appelle aucun.
     * Étape publique pour l'évaluation par lots.
     */
    public CalculQuestion calcul(String question, ContexteProfil contexte) {
        CalculQuestion calcul = calculs.pour(question, contexte).orElse(null);
        if (calcul != null) {
            metriques.calcul(calcul.resultat() == null ? "impossible" : "fait");
        }
        return calcul;
    }

    /** Réponse quand aucun article n'a été trouvé : pas de rédaction. */
    public ReponseQuestion sansArticle(String question, ContexteProfil contexte, List<String> requetes,
                                       CalculQuestion calcul) {
        metriques.question("aucun_article");
        List<String> avertissements = new ArrayList<>(contexte.avertissements());
        avertissements.add("Aucun article pertinent n'a été trouvé : reformulez la question ou précisez le profil.");
        if (CalculQuestion.avertissement(calcul) != null) {
            avertissements.add(CalculQuestion.avertissement(calcul));
        }
        return new ReponseQuestion(question, contexte, requetes, List.of(), List.of(), false, avertissements, List.of(),
                List.of(), assistant.consignes(), calcul);
    }

    /**
     * Réponse finale à partir de la rédaction : contrôles de sortie (citations, troncature),
     * avertissements et métriques. Étape publique pour l'évaluation par lots.
     */
    public ReponseQuestion assembler(String question, ContexteProfil contexte, List<String> requetes,
                                     List<ArticleDetail> articles, Assistant.Reponse reponse, CalculQuestion calcul) {
        List<String> avertissements = new ArrayList<>(contexte.avertissements());
        if (CalculQuestion.avertissement(calcul) != null) {
            avertissements.add(CalculQuestion.avertissement(calcul));
        }
        if (reponse.refus()) {
            avertissements.add("La question n'a pas pu être traitée automatiquement.");
        }
        List<Citation> nonVerifiees = VerificationCitations.nonVerifiees(reponse.passages(), articles);
        if (!nonVerifiees.isEmpty()) {
            avertissements.add(nonVerifiees.size() + " extrait(s) cité(s) introuvable(s) dans les articles : à vérifier.");
        }
        List<String> chiffres = VerificationChiffres.nonRetrouves(reponse.passages(), articles, contexte, question, calcul);
        if (!chiffres.isEmpty()) {
            avertissements.add(chiffres.size() + " chiffre(s) absent(s) des articles transmis et du profil ("
                    + String.join(", ", chiffres) + ") : calculs ou chiffres à vérifier.");
        }
        if (reponse.tronquee()) {
            avertissements.add("Réponse incomplète : elle a été interrompue avant sa fin. Reformulez une question plus précise.");
        }
        metriques.question(reponse.refus() ? "refus" : reponse.tronquee() ? "tronquee" : "repondue");
        metriques.citations((int) reponse.passages().stream().mapToLong(p -> p.citations().size()).sum(),
                nonVerifiees.size());
        metriques.chiffresNonRetrouves(chiffres.size());
        return new ReponseQuestion(question, contexte, requetes, reponse.passages(),
                articles.stream().map(Source::de).toList(), reponse.refus(), avertissements, nonVerifiees, chiffres,
                assistant.consignes(), calcul);
    }

    /**
     * Fusionne les résultats des requêtes : chaque article garde sa meilleure pertinence, puis les
     * meilleurs sont retenus, autant que l'assistant en accepte ({@link Assistant#articlesMax()}).
     * Avec une convention, une part des places ({@link #PART_CONVENTION}) va d'abord à ses meilleurs
     * articles, cherchés à part ; les places qu'elle n'occupe pas reviennent aux autres.
     */
    private List<Resultat> selectionner(String question, List<String> requetes, Integer idcc, LocalDate date) {
        Map<String, Resultat> meilleurs = new LinkedHashMap<>();
        Map<String, Resultat> convention = new LinkedHashMap<>();
        for (String requete : requetes) {
            for (Resultat r : rechercheService.rechercher(requete, idcc, true, RESULTATS_PAR_REQUETE, date)) {
                garderLeMeilleur(meilleurs, r);
            }
            if (idcc != null) {
                for (Resultat r : rechercheService.rechercherConvention(requete, idcc, RESULTATS_PAR_REQUETE, date)) {
                    garderLeMeilleur(convention, r);
                    garderLeMeilleur(meilleurs, r);
                }
            }
        }
        Comparator<Resultat> parPertinence = Comparator.comparingDouble(Resultat::pertinence).reversed();
        int max = assistant.articlesMax();
        List<Resultat> sansReserve = meilleurs.values().stream().sorted(parPertinence).limit(max).toList();
        double seuil = sansReserve.isEmpty() ? 0 : sansReserve.getLast().pertinence() * PERTINENCE_MIN_CONVENTION;
        List<Resultat> reserves = convention.values().stream().sorted(parPertinence)
                .filter(r -> r.pertinence() >= seuil)
                .limit((long) Math.floor(max * PART_CONVENTION)).toList();
        Set<String> dejaRetenus = new HashSet<>();
        reserves.forEach(r -> dejaRetenus.add(r.articleSourceId()));
        List<Resultat> retenus = new ArrayList<>(reserves);
        meilleurs.values().stream().sorted(parPertinence).filter(r -> !dejaRetenus.contains(r.articleSourceId()))
                .limit(max - reserves.size()).forEach(retenus::add);
        retenus.sort(parPertinence);
        return ajouterSemantiques(retenus, reserves, question != null ? question : String.join(" ; ", requetes), idcc, date);
    }

    /**
     * Recherche sémantique, si elle est active : jusqu'à {@link #PLACES_SEMANTIQUE} articles trouvés
     * par le sens et pas par les mots, assez proches de la question ({@link #SIMILARITE_MIN}), prennent
     * la place des derniers articles retenus par les mots (hors places réservées à la convention).
     */
    private List<Resultat> ajouterSemantiques(List<Resultat> retenus, List<Resultat> reserves, String texte,
                                              Integer idcc, LocalDate date) {
        if (PLACES_SEMANTIQUE == 0 || !rechercheService.semantiqueActive()) {
            return retenus;
        }
        Set<String> deja = new HashSet<>();
        retenus.forEach(r -> deja.add(r.articleSourceId()));
        List<Resultat> semantiques;
        try {
            semantiques = rechercheService.rechercherSemantique(texte, idcc, RESULTATS_PAR_REQUETE, date)
                    .stream().filter(r -> r.pertinence() >= SIMILARITE_MIN && !deja.contains(r.articleSourceId()))
                    .limit(PLACES_SEMANTIQUE).toList();
        } catch (RuntimeException e) {
            // Modèle d'embeddings injoignable : les articles trouvés par les mots suffisent (jamais la question au journal)
            log.warn("Recherche sémantique indisponible ({}) : articles trouvés par les mots seulement",
                    e.getClass().getSimpleName());
            return retenus;
        }
        if (semantiques.isEmpty()) {
            return retenus;
        }
        List<Resultat> resultat = new ArrayList<>(retenus);
        int aRetirer = Math.max(0, resultat.size() + semantiques.size() - assistant.articlesMax());
        Set<String> reservees = new HashSet<>();
        reserves.forEach(r -> reservees.add(r.articleSourceId()));
        for (int i = resultat.size() - 1; i >= 0 && aRetirer > 0; i--) {
            if (!reservees.contains(resultat.get(i).articleSourceId())) {
                resultat.remove(i);
                aRetirer--;
            }
        }
        resultat.addAll(semantiques);
        return resultat;
    }

    private static void garderLeMeilleur(Map<String, Resultat> parArticle, Resultat r) {
        parArticle.merge(r.articleSourceId(), r, (a, b) -> a.pertinence() >= b.pertinence() ? a : b);
    }

    /** Article transmis à la rédaction, pour que le client puisse afficher toutes les sources. */
    public record Source(String articleSourceId, String num, String texteSourceId, String texteTitre) {

        static Source de(ArticleDetail a) {
            return new Source(a.sourceId(), a.num(), a.texte().sourceId(), a.texte().titre());
        }
    }

    /**
     * @param requetes  requêtes de recherche issues de la reformulation (transparence)
     * @param passages  réponse, passage par passage, avec citations
     * @param sources   articles transmis à la rédaction
     * @param refus     le modèle a refusé de répondre
     * @param citationsNonVerifiees citations dont l'extrait est introuvable dans l'article cité
     *                  ({@link VerificationCitations}) : à signaler, sans les retirer
     * @param chiffresNonRetrouves chiffres de la réponse absents des articles transmis, du profil et de
     *                  la question ({@link VerificationChiffres}) : à vérifier, sans les retirer
     * @param consignes versions des consignes envoyées au modèle ({@link Consignes}), pour relier
     *                  une réponse aux consignes qui l'ont produite
     * @param calcul    calcul fait par le code pour la question (montants, détail, hypothèses), que la
     *                  réponse explique ; {@code null} pour une question sans calcul
     */
    public record ReponseQuestion(String question, ContexteProfil contexte, List<String> requetes,
                                  List<Passage> passages, List<Source> sources, boolean refus,
                                  List<String> avertissements, List<Citation> citationsNonVerifiees,
                                  List<String> chiffresNonRetrouves, Assistant.VersionsConsignes consignes,
                                  CalculQuestion calcul) {
    }
}
