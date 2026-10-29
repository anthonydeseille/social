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

        Set<String> articlesCites() {
            if (reponse == null) {
                return Set.of();
            }
            return reponse.passages().stream()
                    .flatMap(p -> p.citations().stream())
                    .map(Citation::articleSourceId)
                    .collect(Collectors.toCollection(LinkedHashSet::new));
        }

        double scoreRecherche() {
            return ratio(trouves().size(), question.articlesAttendus().size());
        }

        double scoreCitations() {
            return ratio(cites().size(), question.articlesAttendus().size());
        }

        private static double ratio(int n, int total) {
            return total == 0 ? 1.0 : (double) n / total;
        }
    }

    /** Tarif de Claude Opus 5.5, en dollars par million de jetons. */
    static final double PRIX_ENTREE = 4.0;
    static final double PRIX_SORTIE = 20.0;

    private RapportEvaluation() {
    }

    static double cout(long jetonsEntree, long jetonsSortie) {
        return (jetonsEntree * PRIX_ENTREE + jetonsSortie * PRIX_SORTIE) / 1_000_000;
    }

    static String markdown(List<Resultat> resultats, String modele, boolean simule) {
        return markdown(resultats, modele, simule, 0, 0);
    }

    static String markdown(List<Resultat> resultats, String modele, boolean simule, long jetonsJugeEntree,
                           long jetonsJugeSortie) {
        StringBuilder md = new StringBuilder();
        List<Resultat> juges = resultats.stream().filter(r -> r.verdict() != null).toList();
        long entree = resultats.stream().mapToLong(Resultat::jetonsEntree).sum();
        long sortie = resultats.stream().mapToLong(Resultat::jetonsSortie).sum();
        md.append("# Rapport d'évaluation\n\n");
        md.append("- Modèle : ").append(simule ? "simulé (aucun appel payant)" : modele).append('\n');
        md.append("- Questions : ").append(resultats.size())
                .append(", en erreur : ").append(resultats.stream().filter(r -> r.erreur() != null).count()).append('\n');
        md.append(String.format(Locale.FRANCE, "- Recherche (articles attendus transmis) : %.0f %%%n",
                100 * resultats.stream().mapToDouble(Resultat::scoreRecherche).average().orElse(0)));
        md.append(String.format(Locale.FRANCE, "- Citations (articles attendus cités) : %.0f %%%n",
                100 * resultats.stream().mapToDouble(Resultat::scoreCitations).average().orElse(0)));
        if (juges.isEmpty()) {
            md.append("- Qualité : juge non lancé\n");
        } else {
            md.append(String.format(Locale.FRANCE, "- Qualité (points clés présents, selon le juge) : %.0f %% sur %d question(s)%n",
                    100 * juges.stream().mapToDouble(r -> r.verdict().scoreQualite()).average().orElse(0), juges.size()));
            md.append("- Erreurs à éviter commises : ")
                    .append(juges.stream().mapToLong(r -> r.verdict().erreursCommises().size()).sum()).append('\n');
        }
        md.append(String.format(Locale.FRANCE, "- Jetons : %d en entrée, %d en sortie ; coût : %.2f $ (%.3f $ par question)%n",
                entree, sortie, cout(entree, sortie), resultats.isEmpty() ? 0 : cout(entree, sortie) / resultats.size()));
        md.append(String.format(Locale.FRANCE, "- Coût du juge : %.2f $%n%n", cout(jetonsJugeEntree, jetonsJugeSortie)));

        md.append("| Question | Recherche | Citations | Qualité | Erreurs | Coût | Durée |\n|---|---|---|---|---|---|---|\n");
        for (Resultat r : resultats) {
            String qualite = r.verdict() == null ? "-" : String.format(Locale.FRANCE, "%.0f %%", 100 * r.verdict().scoreQualite());
            String erreurs = r.verdict() == null ? "-" : String.valueOf(r.verdict().erreursCommises().size());
            md.append(String.format(Locale.FRANCE, "| %s | %d/%d | %d/%d | %s | %s | %.3f $ | %.1f s |%n",
                    r.question().id(), r.trouves().size(), r.question().articlesAttendus().size(),
                    r.cites().size(), r.question().articlesAttendus().size(), qualite, erreurs,
                    cout(r.jetonsEntree(), r.jetonsSortie()), r.dureeMs() / 1000.0));
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
}
