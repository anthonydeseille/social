package fr.deseille.social.evaluation;

import fr.deseille.social.corpus.Article;
import fr.deseille.social.corpus.ArticleRepository;
import fr.deseille.social.evaluation.JeuEvaluation.ArticleAttendu;
import fr.deseille.social.evaluation.JeuEvaluation.Question;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Contrôle des articles attendus du jeu d'évaluation sur le corpus ingéré, avant toute mesure :
 * chacun doit exister, porter le numéro annoncé (quand le corpus en a un) et être applicable à la date
 * de référence de sa question. Sinon (révision épinglée changée, identifiant erroné), la mesure serait
 * fausse sans que rien ne le signale.
 */
final class ArticlesAttendus {

    private ArticlesAttendus() {
    }

    /** Anomalies, une par article attendu en défaut ; vide si tout est en ordre. */
    static List<String> anomalies(List<Question> questions, ArticleRepository articleRepository) {
        Map<String, Article> articles = articleRepository.findBySourceIdIn(questions.stream()
                        .flatMap(q -> q.articlesAttendus().stream()).map(ArticleAttendu::sourceId).toList())
                .stream().collect(Collectors.toMap(Article::getSourceId, Function.identity()));
        List<String> anomalies = new ArrayList<>();
        for (Question q : questions) {
            LocalDate date = q.profil().dateReference() != null ? q.profil().dateReference() : LocalDate.now();
            for (ArticleAttendu attendu : q.articlesAttendus()) {
                Article article = articles.get(attendu.sourceId());
                String reference = q.id() + " : " + attendu.num() + " (" + attendu.sourceId() + ")";
                if (article == null) {
                    anomalies.add(reference + " absent du corpus");
                } else if (article.getNum() != null && !article.getNum().equals(attendu.num())) {
                    anomalies.add(reference + " porte le numéro " + article.getNum());
                } else if (!article.estApplicableLe(date)) {
                    anomalies.add(reference + " non applicable au " + date + " (" + article.getEtat() + ")");
                }
            }
        }
        return anomalies;
    }
}
