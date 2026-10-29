package fr.deseille.social.questions;

import fr.deseille.social.consultation.VuesConsultation.ArticleDetail;
import fr.deseille.social.questions.Assistant.Citation;
import fr.deseille.social.questions.Assistant.Passage;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Garde-fou déterministe : chaque extrait cité doit figurer, aux espaces près, dans le texte de
 * l'article auquel la citation le rattache (le texte même transmis au modèle, nota compris). Les
 * citations de l'API reprennent normalement le texte des documents ; ce contrôle protège contre un
 * mauvais rattachement et contre une régression (autre modèle, autre format), sans appel payant.
 */
public final class VerificationCitations {

    private VerificationCitations() {
    }

    /** Citations dont l'extrait est vide, l'article inconnu, ou le texte introuvable dans l'article. */
    public static List<Citation> nonVerifiees(List<Passage> passages, List<ArticleDetail> articles) {
        Map<String, String> textes = articles.stream().collect(Collectors.toMap(ArticleDetail::sourceId,
                a -> normaliser(AssistantClaude.contenu(a)), (a, b) -> a));
        return passages.stream()
                .flatMap(p -> p.citations().stream())
                .filter(c -> !verifiee(c, textes))
                .distinct()
                .toList();
    }

    private static boolean verifiee(Citation c, Map<String, String> textes) {
        String extrait = normaliser(c.texteCite());
        String texte = textes.get(c.articleSourceId());
        return !extrait.isEmpty() && texte != null && texte.contains(extrait);
    }

    /** Espaces Unicode comprises : insécable, fine insécable (typographie française avant ; : %)… */
    private static String normaliser(String texte) {
        return texte == null ? "" : texte.replaceAll("(?U)\\s+", " ").strip();
    }
}
