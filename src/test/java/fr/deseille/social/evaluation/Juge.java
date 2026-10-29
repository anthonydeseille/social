package fr.deseille.social.evaluation;

import fr.deseille.social.evaluation.JeuEvaluation.Question;
import fr.deseille.social.questions.QuestionService.ReponseQuestion;
import java.util.List;

/**
 * Juge automatique : confronte une réponse aux points clés et aux erreurs à éviter de la réponse de
 * référence. Il remplace la coche manuelle des points clés ; la juriste n'arbitre que les désaccords.
 */
interface Juge {

    Verdict juger(Question question, ReponseQuestion reponse);

    record PointJuge(String point, boolean present, String justification) {
    }

    record ErreurJugee(String erreur, boolean commise, String justification) {
    }

    /**
     * @param commentaire toute autre affirmation fausse ou dangereuse au regard de la référence,
     *                    vide sinon
     */
    record Verdict(List<PointJuge> points, List<ErreurJugee> erreurs, String commentaire) {

        /** Part des points clés présents (1 s'il n'y en a aucun). */
        double scoreQualite() {
            return points.isEmpty() ? 1.0 : (double) points.stream().filter(PointJuge::present).count() / points.size();
        }

        List<ErreurJugee> erreursCommises() {
            return erreurs.stream().filter(ErreurJugee::commise).toList();
        }
    }
}
