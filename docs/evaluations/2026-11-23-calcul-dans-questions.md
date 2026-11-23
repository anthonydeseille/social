# Troisième évaluation : le calculateur d'indemnité dans les questions (23 novembre 2026)

Même jeu de 20 questions, même mode (API Batch), sur la branche de la **PR #36** au commit `c7df5f5`
(première évaluation lancée par la CI, avec la fédération d'identité). Depuis la
[deuxième évaluation](2026-10-31-titres-section.md), un seul changement qui touche les réponses :

- **PR #36** : une question sur l'indemnité de licenciement déclenche le calculateur de l'application
  ; ses chiffres et hypothèses sont transmis à la rédaction, qui doit les reprendre tels quels
  (consignes de rédaction `5d624a71` → `ee307162`).

La reformulation (consignes `79d5ae25`) et la recherche n'ont pas changé.

Rapport brut : [`2026-11-23-rapport-brut.md`](2026-11-23-rapport-brut.md).

## En bref

- **Le but est atteint** : les deux questions de calcul donnent le bon montant, repris du
  calculateur. **indemnite-licenciement-cadre-7ans passe de 0 % à 100 %** (2,36 mois, contre 1,77
  mois légaux) ; indemnite-licenciement-etam-12ans reste à 100 % (3,19 mois).
- **La « baisse nette » signalée par la CI (recherche 58 % → 52 %) ne vient pas de la PR** : les
  pertes portent sur trois questions sans calcul, et leurs requêtes de reformulation ont changé d'un
  lancement à l'autre, avec les mêmes consignes et le même modèle (voir plus bas). Le contrôle a été
  outrepassé pour cette PR.
- **Qualité : 71 % → 67 %**, dans le bruit d'un seul passage : +100 points sur l'indemnité cadre et
  +33 sur le congé de mariage, −100 sur les absences pour recherche d'emploi (article non transmis),
  −67 sur la démission cadre, −40 sur les heures complémentaires.
- **Citations vérifiées : 202/202.** Coût : 0,92 $ + 0,29 $ (juge) = **1,21 $**.

## Résultats

| Indicateur | 31 octobre | 23 novembre |
|---|---|---|
| Recherche (moyenne par question) | 58 % | 52 % |
| Articles attendus transmis | 26/48 | 24/48 |
| Citations vérifiées | 197/197 | 202/202 |
| Qualité (juge, moyenne par question) | 71 % | 67 % |
| Erreurs à éviter commises | 1 | 1 |
| Jetons en entrée / en sortie | 174 631 / 50 289 | 195 246 / 52 537 |
| Coût (questions + juge) | 0,85 $ + 0,28 $ | 0,92 $ + 0,29 $ |

### Questions qui ont bougé

| Question | Recherche | Qualité | Calcul |
|---|---|---|---|
| periode-essai-etam-250 | 2/2 → 1/2 | 100 % → 100 % | non |
| preavis-demission-cadre | 1/2 → 1/2 | 100 % → **33 %** | non |
| absences-recherche-emploi | 1/1 → **0/1** | 100 % → **0 %** | non |
| indemnite-licenciement-cadre-7ans | 1/3 → 2/3 | 0 % → **100 %** | **oui** |
| conge-mariage | 1/2 → 2/2 | 67 % → 100 % | non |
| temps-partiel-heures-complementaires | 4/5 → **2/5** | 80 % → **40 %** | non |

Les 14 autres questions sont identiques en recherche et en qualité.

## Pourquoi la recherche baisse sans que la PR y touche

Les articles transmis dépendent des requêtes que Claude écrit à la reformulation. Avec les mêmes
consignes (`79d5ae25`) et le même modèle, elles diffèrent d'un lancement à l'autre ; par exemple :

- periode-essai-etam-250 : « délai prévenance essai » (31 octobre) a disparu ;
- absences-recherche-emploi : « heures recherche emploi rémunérées » est devenu « heures recherche
  emploi ».

L'évaluation de la recherche seule (`RechercheEvaluationIT`, requêtes figées dans `requetes.yaml`),
lancée par la CI sur cette PR, est restée à sa référence : la recherche elle-même n'a pas bougé.
Cette variation de la reformulation suffit à faire franchir le seuil de 5 points sur 20 questions.

## Les deux réponses de calcul

- **Cadre, 7 ans, licenciement économique** : « au moins 2,36 mois de salaire brut », convention
  Syntec plus favorable que le légal (1,77 mois), salaire de référence au douzième des 12 derniers
  mois. Le juge relève une réserve inutile : la réponse laisse entendre que le quart de mois pourrait
  valoir pour les deux premières années, alors que le tiers s'applique à toutes les années dès 2 ans.
- **ETAM, 12 ans** : 3,19 mois (2,50 + 0,69), légal et Syntec identiques ; la réponse dit qu'elle ne
  peut pas vérifier l'article 4.5 elle-même et rappelle la validation juridique en cours. Le juge
  relève une phrase confuse sur l'indemnité compensatrice de préavis (hors calcul).

Dans les deux cas, **l'article 4.5 de Syntec n'est toujours pas transmis** par la recherche : le
calculateur compense ici, mais pas pour les autres questions qui en dépendent.

## Conclusions et actions

- **licenciement-6-mois n'avait pas déclenché le calcul** : « ai-je droit à une indemnité et à un
  préavis ? » était exclu parce que le mot « préavis » y figure. Corrigé dans la même PR (le préavis
  et les congés n'excluent plus que nommés comme indemnité : « indemnité de préavis ») ; la réponse
  donnée était de toute façon juste (pas d'indemnité avant 8 mois).
- **Rendre la comparaison moins sensible au hasard de la reformulation** : comparer la recherche sur
  les requêtes figées (déjà fait par `RechercheEvaluationIT`) et réserver le seuil de l'évaluation
  complète à la qualité et aux erreurs, ou fixer la température de la reformulation. À décider.
- Cette ligne devient la référence des prochaines comparaisons (52 % en recherche) : la prochaine
  évaluation dira si 58 % ou 52 % est la valeur habituelle.
- Toujours à faire : faire remonter l'article 4.5 de Syntec (équilibre Code / convention, voir la
  [deuxième évaluation](2026-10-31-titres-section.md)).
