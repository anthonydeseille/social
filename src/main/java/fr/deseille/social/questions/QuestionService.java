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
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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

    private static final int RESULTATS_PAR_REQUETE = 10;

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
        List<String> requetes = metriques.chronometrer("reformulation", () -> assistant.reformuler(question, contexte));

        List<ArticleDetail> articles = metriques.chronometrer("recherche", () -> articles(requetes, profil.idcc(), date));
        CalculQuestion calcul = calcul(question, contexte);
        if (articles.isEmpty()) {
            return sansArticle(question, contexte, requetes, calcul);
        }

        Assistant.Reponse reponse = metriques.chronometrer("redaction",
                () -> assistant.rediger(question, contexte, articles, calcul));
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
        return selectionner(requetes, idcc, date).stream()
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
     */
    private List<Resultat> selectionner(List<String> requetes, Integer idcc, LocalDate date) {
        Map<String, Resultat> meilleurs = new LinkedHashMap<>();
        for (String requete : requetes) {
            for (Resultat r : rechercheService.rechercher(requete, idcc, true, RESULTATS_PAR_REQUETE, date)) {
                meilleurs.merge(r.articleSourceId(), r, (a, b) -> a.pertinence() >= b.pertinence() ? a : b);
            }
        }
        return meilleurs.values().stream()
                .sorted(Comparator.comparingDouble(Resultat::pertinence).reversed())
                .limit(assistant.articlesMax())
                .toList();
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
