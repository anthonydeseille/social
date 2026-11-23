# Quatrième évaluation : calcul dans la page, salaire lu dans la question (23 novembre 2026)

Même jeu de 20 questions, même mode (API Batch), sur la branche de la **PR #38** au commit `c58eb5c`,
lancée d'office par la CI (la PR touche le trajet des questions). Depuis la
[troisième évaluation](2026-11-23-calcul-dans-questions.md), du même jour :

- calcul affiché dans la page web (sans effet sur les réponses) ;
- salaire mensuel brut lu dans la question (aucune question du jeu n'en donne) ;
- exclusion du calcul resserrée : « une indemnité et un préavis » déclenche désormais le calcul
  (question licenciement-6-mois), « indemnité de / pour / au titre du préavis » l'exclut.

Consignes inchangées (`79d5ae25`, `ee307162`, `89ac4e8f`). Rapport brut :
[`2026-11-23b-rapport-brut.md`](2026-11-23b-rapport-brut.md).

## En bref

- **Rien ne baisse au-delà du bruit** : qualité 67 % → 70 %, recherche 52 % → 51 % (indicatif),
  citations vérifiées 195/195, une erreur à éviter (conges-payes-6ans, comme avant). Coût : 0,94 $ +
  0,28 $ = **1,22 $**.
- **licenciement-6-mois passe désormais par le calculateur** : « pas d'indemnité avant 8 mois »,
  point clé validé par le juge ; qualité inchangée (50 %), le point manquant reste la durée du préavis
  (1 mois), que la réponse refuse de donner faute d'article Syntec transmis.
- **Les deux questions de calcul restent à 100 %** (2,36 et 3,19 mois).
- Mouvements dans le bruit de la reformulation : fin-cdd 25 % → 75 % (un article de plus),
  temps-partiel 40 % → 80 %, absences-recherche-emploi toujours à 0 % (article non transmis).

## Conclusions

- Seconde mesure au même niveau que la troisième : la recherche autour de 51-52 % est la valeur
  habituelle de cette reformulation, pas une baisse.
- Le manque récurrent reste la convention (Syntec 4.5, durées de préavis) : c'est le prochain
  chantier, l'équilibre Code / convention dans la sélection des articles.
