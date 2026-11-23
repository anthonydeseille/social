# Sixième évaluation : observabilité, et mesure du bruit (23 novembre 2026)

Même jeu de 20 questions, même mode (API Batch), sur la branche de la **PR #41** au commit `3525001`,
lancée d'office par la CI (la PR touche le trajet des questions). La PR n'ajoute que des traces et des
métriques : **rien ne change dans ce qui est envoyé au modèle**. Consignes, recherche et sélection sont
celles de la [cinquième évaluation](2026-11-23c-places-convention.md).

Rapport brut : [`2026-11-23d-rapport-brut.md`](2026-11-23d-rapport-brut.md).

## Résultat

| Indicateur | 5e évaluation | 6e évaluation |
|---|---|---|
| Recherche (indicatif) | 66 % | 55 % |
| Qualité (juge) | 77 % | 71 % (−6 : « baisse nette » pour la CI) |
| Erreurs à éviter | 1 | 1 |
| Citations vérifiées | 209/209 | 204/204 |
| Coût | 1,24 $ | 1,19 $ |

Toute la baisse de qualité tient à **deux questions**, les mêmes qui bougent d'un lancement à l'autre
depuis le 31 octobre :

| Question | 5e | 6e | Requêtes de la reformulation |
|---|---|---|---|
| absences-recherche-emploi | 1/1, 100 % | 0/1, **0 %** | « heures recherche emploi rémunérées » devient « heures recherche emploi » : l'article Syntec n'est plus transmis |
| temps-partiel-heures-complementaires | 4/5, 80 % | 2/5, 60 % | « heures complémentaires » et « temps partiel durée » deviennent « heures complémentaires temps partiel » et « limite heures complémentaires » |

Les 18 autres questions ont la même qualité.

## Conclusions

- **Ce n'est pas une régression** : deux lancements d'un même système donnent 77 % et 71 %. La PR est
  fusionnée en passant outre le contrôle, compromis expliqué dans la PR.
- **Le seuil de 5 points sur la qualité est trop serré pour 20 questions** : une seule question qui
  passe de 100 % à 0 % fait perdre 5 points. Le bruit vient presque entièrement de la reformulation.
- **Piste retenue pour la suite** : pour une PR qui ne touche ni la reformulation ni le modèle, faire
  l'évaluation complète sur les **requêtes figées** (`requetes.yaml`), comme l'évaluation gratuite de la
  recherche. La qualité ne mesurerait plus que la recherche et la rédaction, sans le hasard de la
  reformulation. À défaut : seuil relevé à 10 points, ou deux lancements.
