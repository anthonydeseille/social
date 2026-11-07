# Historique des évaluations

Chaque évaluation pose le jeu de questions (`src/test/resources/evaluation/questions.yaml`) à
l'application, sur les vraies sources, puis fait noter les réponses par un juge automatique
(méthode et indicateurs : [`docs/fonctionnement.md`](../fonctionnement.md#lévaluation)).

Les résultats ne sont jamais réécrits : chaque lancement ajoute une ligne à l'historique et deux
fichiers datés.

## Historique

| Date | Commit | Ce qui a changé | Recherche | Citations vérifiées | Qualité | Erreurs | Coût | Analyse |
|---|---|---|---|---|---|---|---|---|
| 2026-10-30 | `50edaac` | référence (première évaluation) | 45 % | 186/186 | 70 % | 1 | 1,36 $ | [analyse](2026-10-30-premiere-evaluation.md) · [brut](2026-10-30-rapport-brut.md) |
| 2026-10-31 | `6c672d5` | Code applicable (#22), titres de section dans l'index (#24) | **58 %** | 197/197 | 71 % | 1 | 1,13 $ | [analyse](2026-10-31-titres-section.md) · [brut](2026-10-31-rapport-brut.md) |

Recherche : articles attendus transmis à Claude ; qualité : points clés présents selon le juge ;
moyennes par question. Tant que le jeu n'est pas validé par la juriste (`valide: false`), les notes
de qualité restent indicatives.

Les mêmes chiffres, lisibles par un programme (tableau de bord, comparaison dans la CI) :
[`historique.csv`](historique.csv). Colonnes :

| Colonne | Contenu |
|---|---|
| `date`, `commit`, `changement`, `mode`, `modele` | lancement : jour, commit de `main` évalué, ce qui a changé depuis la précédente, `lots` ou `direct`, modèle de l'application |
| `questions`, `validees` | questions posées, dont validées par la juriste |
| `recherche_pct` | articles attendus transmis à Claude (moyenne par question, en %) |
| `articles_transmis`, `articles_attendus` | les mêmes, en total sur le jeu |
| `articles_cites_pct` | articles attendus cités dans la réponse (moyenne par question, en %) |
| `citations_verifiees`, `citations_total` | citations dont l'extrait est présent dans l'article, sur toutes les citations |
| `qualite_pct`, `erreurs` | points clés présents selon le juge (moyenne, en %), erreurs à éviter commises |
| `jetons_entree`, `jetons_sortie`, `cout_questions_usd`, `cout_juge_usd` | consommation et coût réels (dollars) |
| `analyse` | fichier d'analyse de l'évaluation |

## Ajouter une évaluation

1. Lancer l'évaluation (commande dans `CLAUDE.md`, section Tests) sur un commit de `main`.
2. Copier le rapport de `target/evaluation/` en `AAAA-MM-JJ-rapport-brut.md` (ne plus le modifier
   ensuite).
3. Écrire l'analyse `AAAA-MM-JJ-<sujet>.md` : ce qui a changé depuis la précédente, résultats,
   comparaison question par question, conclusions et actions.
4. Ajouter une ligne ici et dans `historique.csv` (mêmes colonnes ; un seul passage par ligne).

Pour comparer deux évaluations, ne retenir que les écarts nets : 20 questions et un seul passage ne
permettent pas de conclure sur quelques points.
