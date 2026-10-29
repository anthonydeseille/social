package fr.deseille.social.evaluation;

import fr.deseille.social.evaluation.JeuEvaluation.ArticleAttendu;
import fr.deseille.social.evaluation.JeuEvaluation.Question;
import fr.deseille.social.questions.Assistant.Citation;
import fr.deseille.social.questions.Assistant.Passage;
import fr.deseille.social.questions.QuestionService.ReponseQuestion;
import fr.deseille.social.questions.QuestionService.Source;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Function;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Résultats d'une évaluation et leur rapport Markdown. Deux mesures automatiques par question, pour
 * savoir d'où vient une mauvaise réponse :
 * <ul>
 *   <li><b>recherche</b> : part des articles attendus parmi ceux transmis au modèle ;</li>
 *   <li><b>citations</b> : part des articles attendus effectivement cités dans la réponse.</li>
 * </ul>
 * La justesse du fond est notée par le {@link Juge} (points clés présents, erreurs commises) quand
 * il est actif ; la juriste n'arbitre que les désaccords.
 */
final class RapportEvaluation {

    /**
     * Résultat d'une question ; {@code erreur} non nul si l'appel a échoué, {@code verdict} nul si le
     * juge n'a pas tourné ({@code erreurJuge} en dit la raison s'il a échoué).
     */
    record Resultat(Question question, ReponseQuestion reponse, String erreur, long jetonsEntree, long jetonsSortie,
                    long dureeMs, Juge.Verdict verdict, String erreurJuge) {

        Resultat(Question question, ReponseQuestion reponse, String erreur, long jetonsEntree, long jetonsSortie,
                 long dureeMs) {
            this(question, reponse, erreur, jetonsEntree, jetonsSortie, dureeMs, null, null);
        }

        Resultat avecVerdict(Juge.Verdict verdict, String erreurJuge) {
            return new Resultat(question, reponse, erreur, jetonsEntree, jetonsSortie, dureeMs, verdict, erreurJuge);
        }

        List<ArticleAttendu> trouves() {
            Set<String> transmis = reponse == null ? Set.of()
                    : reponse.sources().stream().map(Source::articleSourceId).collect(Collectors.toSet());
            return question.articlesAttendus().stream().filter(a -> transmis.contains(a.sourceId())).toList();
        }

        List<ArticleAttendu> cites() {
            Set<String> cites = articlesCites();
            return question.articlesAttendus().stream().filter(a -> cites.contains(a.sourceId())).toList();
        }

        List<Citation> citations() {
            return reponse == null ? List.of() : reponse.passages().stream().flatMap(p -> p.citations().stream()).toList();
        }

        List<Citation> nonVerifiees() {
            return reponse == null ? List.of() : reponse.citationsNonVerifiees();
        }

        Set<String> articlesCites() {
            if (reponse == null) {
                return Set.of();
            }
            return reponse.passages().stream()
                    .flatMap(p -> p.citations().stream())
                    .map(Citation::articleSourceId)
                    .collect(Collectors.toCollection(LinkedHashSet::new));
        }

        /** Part des articles attendus transmis au modèle ; null sans article attendu (rien à mesurer). */
        Double scoreRecherche() {
            return ratio(trouves().size(), question.articlesAttendus().size());
        }

        /** Part des articles attendus cités ; null sans article attendu. */
        Double scoreCitations() {
            return ratio(cites().size(), question.articlesAttendus().size());
        }

        private static Double ratio(int n, int total) {
            return total == 0 ? null : (double) n / total;
        }
    }

    private RapportEvaluation() {
    }

    static String markdown(List<Resultat> resultats, String modele, boolean simule) {
        return markdown(resultats, Tarif.pour(modele), simule, Tarif.pour(modele), 0, 0, resultats.size());
    }

    /**
     * @param prevues nombre de questions prévues : le rapport est réécrit après chaque question, et
     *                signalé partiel tant qu'elles ne sont pas toutes passées
     */
    static String markdown(List<Resultat> resultats, Tarif tarif, boolean simule, Tarif tarifJuge,
                           long jetonsJugeEntree, long jetonsJugeSortie, int prevues) {
        StringBuilder md = new StringBuilder();
        List<Resultat> juges = resultats.stream().filter(r -> r.verdict() != null).toList();
        long entree = resultats.stream().mapToLong(Resultat::jetonsEntree).sum();
        long sortie = resultats.stream().mapToLong(Resultat::jetonsSortie).sum();
        md.append("# Rapport d'évaluation").append(resultats.size() < prevues ? " (partiel)" : "").append("\n\n");
        md.append("- Modèle : ").append(simule ? "simulé (aucun appel payant)" : tarif.libelle()).append('\n');
        md.append("- Questions : ").append(resultats.size()).append('/').append(prevues)
                .append(", en erreur : ").append(resultats.stream().filter(r -> r.erreur() != null).count()).append('\n');
        md.append("- Recherche (articles attendus transmis) : ").append(moyenne(resultats, Resultat::scoreRecherche)).append('\n');
        md.append("- Citations (articles attendus cités) : ").append(moyenne(resultats, Resultat::scoreCitations)).append('\n');
        long citations = resultats.stream().mapToLong(r -> r.citations().size()).sum();
        long nonVerifiees = resultats.stream().mapToLong(r -> r.nonVerifiees().size()).sum();
        md.append("- Citations vérifiées (extrait présent dans l'article) : ").append(citations - nonVerifiees)
                .append('/').append(citations).append('\n');
        if (juges.isEmpty()) {
            md.append("- Qualité : juge non lancé\n");
        } else {
            md.append(String.format(Locale.FRANCE, "- Qualité (points clés présents, selon le juge) : %.0f %% sur %d question(s)%n",
                    100 * juges.stream().mapToDouble(r -> r.verdict().scoreQualite()).average().orElse(0), juges.size()));
            md.append("- Erreurs à éviter commises : ")
                    .append(juges.stream().mapToLong(r -> r.verdict().erreursCommises().size()).sum()).append('\n');
        }
        double cout = tarif.cout(entree, sortie);
        md.append(String.format(Locale.FRANCE, "- Jetons : %d en entrée, %d en sortie ; coût : %s (%s par question)%n",
                entree, sortie, Tarif.dollars(cout, "%.2f $"),
                Tarif.dollars(resultats.isEmpty() ? 0 : cout / resultats.size(), "%.3f $")));
        md.append("- Coût du juge : ").append(Tarif.dollars(tarifJuge.cout(jetonsJugeEntree, jetonsJugeSortie), "%.2f $"))
                .append(tarifJuge.modele().equals(tarif.modele()) ? "" : " (" + tarifJuge.libelle() + ")").append("\n\n");

        md.append("| Question | Recherche | Citations | Qualité | Erreurs | Coût | Durée |\n|---|---|---|---|---|---|---|\n");
        for (Resultat r : resultats) {
            String qualite = r.verdict() == null ? "-" : String.format(Locale.FRANCE, "%.0f %%", 100 * r.verdict().scoreQualite());
            String erreurs = r.verdict() == null ? "-" : String.valueOf(r.verdict().erreursCommises().size());
            int attendus = r.question().articlesAttendus().size();
            md.append(String.format(Locale.FRANCE, "| %s | %s | %s | %s | %s | %s | %.1f s |%n",
                    r.question().id(),
                    attendus == 0 ? "n/a" : r.trouves().size() + "/" + attendus,
                    attendus == 0 ? "n/a" : r.cites().size() + "/" + attendus,
                    qualite, erreurs, Tarif.dollars(tarif.cout(r.jetonsEntree(), r.jetonsSortie()), "%.3f $"),
                    r.dureeMs() / 1000.0));
        }

        for (Resultat r : resultats) {
            Question q = r.question();
            md.append("\n---\n\n## ").append(q.id()).append(" (").append(q.theme()).append(")\n\n");
            md.append("**Question** : ").append(q.question()).append("\n\n");
            if (r.erreur() != null) {
                md.append("**Erreur** : ").append(r.erreur()).append("\n");
                continue;
            }
            ReponseQuestion rep = r.reponse();
            md.append("**Requêtes** : ").append(String.join(" ; ", rep.requetes())).append("\n\n");
            md.append("**Réponse**").append(rep.refus() ? " (refus)" : "").append(" :\n\n");
            for (Passage p : rep.passages()) {
                md.append(p.texte());
                if (!p.citations().isEmpty()) {
                    md.append(" [").append(p.citations().stream().map(Citation::num).distinct()
                            .collect(Collectors.joining(", "))).append(']');
                }
            }
            md.append("\n\n");
            if (!rep.avertissements().isEmpty()) {
                md.append("**Avertissements** : ").append(String.join(" / ", rep.avertissements())).append("\n\n");
            }
            if (!r.nonVerifiees().isEmpty()) {
                md.append("**Citations non vérifiées** :\n\n");
                r.nonVerifiees().forEach(c -> md.append("- ").append(c.num()).append(" : « ").append(c.texteCite())
                        .append(" »\n"));
                md.append('\n');
            }
            md.append("**Articles transmis** : ").append(rep.sources().stream().map(Source::num)
                    .collect(Collectors.joining(", "))).append("\n\n");
            List<String> manquantsRecherche = new ArrayList<>();
            List<String> manquantsCitations = new ArrayList<>();
            for (ArticleAttendu a : q.articlesAttendus()) {
                if (!r.trouves().contains(a)) {
                    manquantsRecherche.add(a.num() + " (" + a.texte() + ")");
                } else if (!r.cites().contains(a)) {
                    manquantsCitations.add(a.num() + " (" + a.texte() + ")");
                }
            }
            md.append("**Attendus non trouvés par la recherche** : ")
                    .append(manquantsRecherche.isEmpty() ? "aucun" : String.join(", ", manquantsRecherche)).append("\n\n");
            md.append("**Attendus trouvés mais non cités** : ")
                    .append(manquantsCitations.isEmpty() ? "aucun" : String.join(", ", manquantsCitations)).append("\n\n");
            if (r.verdict() != null) {
                md.append("**Points clés (juge)** :\n\n");
                r.verdict().points().forEach(p -> md.append(p.present() ? "- ✅ " : "- ❌ ").append(p.point())
                        .append(" : ").append(p.justification()).append('\n'));
                if (!r.verdict().erreursCommises().isEmpty()) {
                    md.append("\n**Erreurs commises (juge)** :\n\n");
                    r.verdict().erreursCommises().forEach(e -> md.append("- ⚠️ ").append(e.erreur())
                            .append(" : ").append(e.justification()).append('\n'));
                }
                if (r.verdict().commentaire() != null && !r.verdict().commentaire().isBlank()) {
                    md.append("\n**Commentaire du juge** : ").append(r.verdict().commentaire()).append('\n');
                }
            } else {
                if (r.erreurJuge() != null) {
                    md.append("**Juge en erreur** : ").append(r.erreurJuge()).append("\n\n");
                }
                md.append("**Points clés à vérifier** :\n\n");
                q.pointsCles().forEach(p -> md.append("- [ ] ").append(p).append('\n'));
                if (!q.erreursAEviter().isEmpty()) {
                    md.append("\n**Erreurs à éviter** :\n\n");
                    q.erreursAEviter().forEach(e -> md.append("- ").append(e).append('\n'));
                }
            }
        }
        return md.toString();
    }

    /** Moyenne des scores définis, en pourcentage ; les questions sans article attendu sont exclues. */
    private static String moyenne(List<Resultat> resultats, Function<Resultat, Double> score) {
        List<Double> scores = resultats.stream().map(score).filter(Objects::nonNull).toList();
        if (scores.isEmpty()) {
            return "n/a";
        }
        return String.format(Locale.FRANCE, "%.0f %% (sur %d question(s))",
                100 * scores.stream().mapToDouble(Double::doubleValue).average().orElse(0), scores.size());
    }
}
