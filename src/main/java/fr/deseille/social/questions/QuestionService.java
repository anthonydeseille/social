package fr.deseille.social.questions;

import fr.deseille.social.consultation.ConsultationService;
import fr.deseille.social.consultation.VuesConsultation.ArticleDetail;
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
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Répond à la question d'un salarié : contexte du profil, reformulation en requêtes, recherche dans
 * le Code et la convention, puis rédaction à partir des articles retenus, avec citations.
 */
@Service
public class QuestionService {

    /** Articles transmis pour la rédaction : assez pour couvrir Code et convention, sans diluer. */
    static final int ARTICLES_MAX = 8;
    private static final int RESULTATS_PAR_REQUETE = 10;

    private final ProfilService profilService;
    private final RechercheService rechercheService;
    private final ConsultationService consultationService;
    private final Assistant assistant;
    private final MetriquesQuestions metriques;

    public QuestionService(ProfilService profilService, RechercheService rechercheService,
                           ConsultationService consultationService, Assistant assistant,
                           MetriquesQuestions metriques) {
        this.profilService = profilService;
        this.rechercheService = rechercheService;
        this.consultationService = consultationService;
        this.assistant = assistant;
        this.metriques = metriques;
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
            metriques.question(e.getStatusCode().is4xxClientError() ? "invalide" : "erreur");
            throw e;
        } catch (RuntimeException e) {
            metriques.question("erreur");
            throw e;
        }
    }

    private ReponseQuestion traiter(String question, ProfilSalarie profil) {
        ContexteProfil contexte = profilService.contexte(profil);
        // Le modèle raisonne à la date de référence : la recherche et l'applicabilité aussi
        LocalDate date = contexte.profil().dateReference();
        List<String> requetes = metriques.chronometrer("reformulation", () -> assistant.reformuler(question, contexte));

        List<ArticleDetail> articles = metriques.chronometrer("recherche", () -> selectionner(requetes, profil.idcc(), date)
                .stream()
                .map(r -> consultationService.article(r.articleSourceId(), date))
                .toList());
        List<String> avertissements = new ArrayList<>(contexte.avertissements());
        if (articles.isEmpty()) {
            metriques.question("aucun_article");
            avertissements.add("Aucun article pertinent n'a été trouvé : reformulez la question ou précisez le profil.");
            return new ReponseQuestion(question, contexte, requetes, List.of(), List.of(), false, avertissements, List.of());
        }

        Assistant.Reponse reponse = metriques.chronometrer("redaction", () -> assistant.rediger(question, contexte, articles));
        if (reponse.refus()) {
            avertissements.add("La question n'a pas pu être traitée automatiquement.");
        }
        List<Citation> nonVerifiees = VerificationCitations.nonVerifiees(reponse.passages(), articles);
        if (!nonVerifiees.isEmpty()) {
            avertissements.add(nonVerifiees.size() + " extrait(s) cité(s) introuvable(s) dans les articles : à vérifier.");
        }
        if (reponse.tronquee()) {
            avertissements.add("Réponse incomplète : elle a été interrompue avant sa fin. Reformulez une question plus précise.");
        }
        metriques.question(reponse.refus() ? "refus" : reponse.tronquee() ? "tronquee" : "repondue");
        metriques.citations((int) reponse.passages().stream().mapToLong(p -> p.citations().size()).sum(),
                nonVerifiees.size());
        return new ReponseQuestion(question, contexte, requetes, reponse.passages(),
                articles.stream().map(Source::de).toList(), reponse.refus(), avertissements, nonVerifiees);
    }

    /**
     * Fusionne les résultats des requêtes : chaque article garde sa meilleure pertinence, puis les
     * {@link #ARTICLES_MAX} meilleurs sont retenus.
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
                .limit(ARTICLES_MAX)
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
     */
    public record ReponseQuestion(String question, ContexteProfil contexte, List<String> requetes,
                                  List<Passage> passages, List<Source> sources, boolean refus,
                                  List<String> avertissements, List<Citation> citationsNonVerifiees) {
    }
}
