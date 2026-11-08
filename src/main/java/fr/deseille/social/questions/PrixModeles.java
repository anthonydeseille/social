package fr.deseille.social.questions;

import java.util.Map;
import java.util.Optional;

/**
 * Prix des modèles Claude, en dollars par million de jetons : coût de chaque appel (métrique,
 * budget journalier) et coût des évaluations. Tarifs de la documentation Anthropic (octobre
 * 2026), à mettre à jour s'ils changent ; un modèle absent a un coût inconnu ({@code NaN}),
 * jamais un coût faux. L'API Batch facture moitié prix ; les modèles locaux sont gratuits.
 */
public final class PrixModeles {

    /** @param entree dollars par million de jetons en entrée ; {@code sortie} : en sortie */
    public record Prix(double entree, double sortie) {

        public double cout(long jetonsEntree, long jetonsSortie) {
            return (jetonsEntree * entree + jetonsSortie * sortie) / 1_000_000;
        }
    }

    private static final Map<String, Prix> CONNUS = Map.of(
            "claude-opus-5-5", new Prix(4.0, 20.0),
            "claude-sonnet-5-5", new Prix(2.0, 10.0),
            "claude-haiku-5-5", new Prix(0.10, 0.50));

    private PrixModeles() {
    }

    public static Optional<Prix> pour(String modele) {
        return Optional.ofNullable(CONNUS.get(modele));
    }

    /** Coût en dollars d'un appel ; {@code NaN} si le modèle n'est pas connu. */
    public static double cout(String modele, long jetonsEntree, long jetonsSortie) {
        return pour(modele).map(p -> p.cout(jetonsEntree, jetonsSortie)).orElse(Double.NaN);
    }
}
