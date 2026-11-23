# Septième évaluation : PR de la recherche sémantique, désactivée (24 novembre 2026)

Même jeu de 20 questions, lancée d'office par la CI sur la branche de la **PR #46** (commit `edb9850`).
La recherche sémantique y est **désactivée par défaut** : rien ne change dans ce qui est envoyé au
modèle. La PR touche `QuestionService`, donc l'évaluation a refait la reformulation (pas de requêtes
figées). Rapport brut : [`2026-11-24-rapport-brut.md`](2026-11-24-rapport-brut.md).

| Indicateur | 6e évaluation | 7e évaluation |
|---|---|---|
| Recherche (indicatif) | 55 % | 60 % |
| Qualité (juge) | 71 % | 72 % |
| Erreurs à éviter | 1 | 1 |
| Citations vérifiées | 204/204 | 219/219 |
| Coût | 1,19 $ | 1,29 $ |

Pas de baisse : troisième mesure d'un système identique (77 %, 71 %, 72 % de qualité), qui confirme
un bruit de quelques points dû à la reformulation. L'effet de la recherche sémantique elle-même a été
mesuré par l'évaluation gratuite de la recherche (28 → 32 articles attendus sur 48, voir la PR #46) ;
il le sera par une évaluation complète une fois les vecteurs fournis à la CI.
