# Première évaluation sur les vraies sources (30 octobre 2026)

Première mesure de bout en bout : 20 questions de salariés posées à l'application, avec le Code du
travail et la convention Syntec réels, puis réponses notées par un juge automatique. Le rapport brut
(réponses complètes, verdicts du juge) est en annexe :
[`2026-10-30-rapport-brut.md`](2026-10-30-rapport-brut.md).

## En bref

- **Claude est fiable sur ce qu'il cite** : les 186 extraits cités se trouvent mot pour mot dans les
  articles. Quand l'article utile lui manque, il le dit plutôt que de répondre de mémoire.
- **La recherche est le maillon faible** : seuls 45 % des articles attendus ont été transmis à Claude.
  Ce sont presque toujours les articles de principe **du Code du travail** qui manquent : le plus
  souvent trouvés mais classés trop bas (13e à 39e rang alors que 8 sont retenus), parfois
  introuvables parce que l'article n'emploie pas les mots cherchés.
- **Un bug a faussé toutes les réponses** : le Code était jugé « non intégré » à la date des questions
  (1er novembre 2026), et l'appli l'aurait dit à tous les utilisateurs à partir de cette date.
  Corrigé par la PR #22.
- **Qualité selon le juge : 70 %** des points clés, malgré ces deux défauts ; une erreur à éviter
  commise (barème d'indemnité ETAM), liée à une question encore ouverte pour la juriste.
- **Coût : 1,36 $** pour 20 questions (API Batch, moitié prix), dont 0,29 $ de juge.

Ces chiffres sont **une base de départ, pas une note de l'application** : le bug a pesé sur toutes les
réponses, et le jeu n'est pas encore validé par la juriste (voir [Limites](#limites)).

## Ce qui a été mesuré

| | |
|---|---|
| Jeu de questions | 20 questions de `src/test/resources/evaluation/questions.yaml` (préavis, indemnité, grossesse, congés, maladie, temps de travail, CDD, handicap), 18 avec la convention Syntec et 2 sans convention renseignée ; **aucune encore validée par la juriste** (`valide: false`) |
| Sources | Code du travail (legi-data `c7bda6b`) et 7 conventions (kali-data `84b1d67`), révisions épinglées, ingérées au lancement |
| Trajet | celui de l'application : reformulation, recherche, rédaction, contrôle des citations (`docs/fonctionnement.md`) |
| Modèle | Claude Opus 5.5 (effort élevé pour la rédaction), API Batch d'Anthropic (moitié prix, trois lots successifs : reformulations, rédactions, juge) |
| Juge | Claude Opus 5.5, sortie structurée : pour chaque point clé de la réponse de référence, présent ou non ; pour chaque erreur à éviter, commise ou non |
| Lancement | `./mvnw verify -Dit.test=EvaluationIT -Dtest=Aucun -Dsurefire.failIfNoSpecifiedTests=false -Devaluation=true -Devaluation.lots=true -Devaluation.toutes=true` (`main` au commit `50edaac`) |

Indicateurs :

| Indicateur | Question posée |
|---|---|
| Recherche | Les articles attendus ont-ils été transmis à Claude ? |
| Citations | Claude a-t-il cité les articles attendus ? |
| Citations vérifiées | Chaque extrait cité existe-t-il dans l'article cité ? (contrôle déterministe) |
| Qualité | Part des points clés de la réponse de référence présents, selon le juge |
| Erreurs | Erreurs à éviter commises, selon le juge |

## Résultats

| Indicateur | Résultat |
|---|---|
| Questions traitées | 20/20, aucune en erreur |
| Recherche | **45 %** des articles attendus transmis (moyenne par question ; 21 articles sur 48 au total, soit 44 %) |
| Citations | 45 % des articles attendus cités (tous ceux qui ont été transmis l'ont été) |
| Citations vérifiées | **186/186** |
| Qualité (juge) | **70 %** des points clés (moyenne par question) |
| Erreurs à éviter commises | 1 |
| Jetons | 267 900 en entrée, 53 348 en sortie |
| Coût | 1,07 $ (0,053 $ par question) + juge 0,29 $ = **1,36 $** |

### Par question

| Question | Recherche | Qualité | Erreurs | Articles attendus non transmis |
|---|---|---|---|---|
| periode-essai-cadre | 2/3 | 100 % | 0 | L1221-19 |
| periode-essai-etam-250 | 1/2 | 100 % | 0 | L1221-19 |
| preavis-demission-cadre | 1/2 | 100 % | 0 | L1237-1 |
| preavis-licenciement-etam-3ans | 0/2 | **0 %** | 0 | Syntec 4.2, L1234-1 |
| absences-recherche-emploi | 0/1 | **0 %** | 0 | Syntec 4.3 |
| indemnite-licenciement-cadre-7ans | 1/3 | 75 % | 0 | L1234-9, R1234-2 |
| indemnite-licenciement-etam-12ans | 1/2 | 67 % | **1** | R1234-2 |
| licenciement-6-mois | 1/4 | 50 % | 0 | L1234-9, L1234-1, Syntec 4.2 |
| grossesse-annonce | 1/1 | 100 % | 0 | — |
| grossesse-licenciement | 1/2 | 100 % | 0 | L1225-4 |
| grossesse-amenagement-horaire | 1/2 | 100 % | 0 | L1225-16 |
| conge-maternite | 1/2 | 33 % | 0 | L1225-17 |
| conges-payes-6ans | 1/2 | 100 % | 0 | L3141-3 |
| conge-mariage | 2/2 | 100 % | 0 | — |
| maladie-cadre-2ans | 1/2 | 100 % | 0 | L1226-1 |
| maladie-etam-6mois | 1/2 | 67 % | 0 | L1226-1 |
| temps-partiel-heures-complementaires | 2/5 | 40 % | 0 | L3123-28, L3123-20, L3123-9 |
| heures-supplementaires | 1/4 | 50 % | 0 | Syntec 6.2, L3121-36, L3121-27 |
| fin-cdd | 0/2 | 25 % | 0 | L1243-8, L1243-10 |
| handicap-preavis | 2/3 | 100 % | 0 | L1234-1 |

La qualité suit la recherche : les cinq questions les plus faibles (0 à 40 %) sont celles où les
articles décisifs n'ont pas été transmis.

## Analyse

### 1. Le Code du travail jugé « non intégré » (bug, corrigé)

**Constat.** Les 20 réponses portent l'avertissement « Le Code du travail n'est pas encore intégré :
aucun droit ne peut être évalué », alors que des articles du Code ont été transmis.

**Cause.** Le texte « Code du travail » porte en base les dates de sa *version consolidée* dans la
source épinglée : en vigueur du 8 octobre au **1er novembre 2026** (date de la version suivante).
`Texte.estApplicableLe` les prenait pour sa période de validité. Les questions étant datées du
1er novembre, le Code était exclu du contexte du profil. Dans l'application réelle, le même
avertissement serait apparu **pour tous les utilisateurs à partir du 1er novembre**.

**Effet sur les réponses.** L'avertissement fait partie de la description du profil transmise à
Claude. Claude en a tenu compte : il refuse par exemple de calculer l'indemnité légale
(indemnite-licenciement-cadre-7ans) ou de comparer le Code et la convention (maladie-etam-6mois). Le
juge lui-même s'y est laissé prendre (« le Code n'est pas censé être intégré »).

**Correction.** PR #22 : l'état du texte décide (un texte abrogé porte `ABROGE`), la validité dans le
temps reste jugée article par article.

### 2. La recherche classe mal les articles du Code

**Constat.** Les requêtes produites par la reformulation sont bonnes (« période essai », « préavis
licenciement », « congés payés durée », « heures complémentaires »), et les articles de la convention
sont bien trouvés. Ce sont les articles de principe du Code qui manquent.

**Cause 1 : noyés parmi des articles voisins.** Le Code compte 11 591 articles applicables ; pour
« période essai », 62 articles applicables du Code parlent de période d'essai (CDD, intérim,
apprentissage…), et L1221-19, l'article de principe, arrive au 39e rang. Le classement par fréquence des mots ne sait pas
qu'il est l'article de référence.

**Cause 2 : les mots sont dans le titre, pas dans l'article.** L3141-3 dit « deux jours et demi
ouvrables par mois » sans jamais écrire « congés payés » : ces mots sont dans le titre de sa section.
L1225-4 dit « rompre le contrat de travail », pas « licenciement ». L'index ne contient que le numéro
et le texte des articles, pas les titres de section.

**Essai (gratuit, en SQL sur la base du cluster local).** Rang de l'article attendu, sur le Code et la
convention Syntec, avant et après ajout des titres de section à l'index :

| Requête | Article attendu | Aujourd'hui | Avec les titres de section |
|---|---|---|---|
| indemnité licenciement | L1234-9 | 13 | **1** |
| préavis licenciement | L1234-1 | 29 | **5** |
| licenciement grossesse | L1225-4 | introuvable | **4** |
| heures complémentaires | L3123-28 | 10 | **7** |
| période essai | L1221-19 | 39 | 12 |
| congé maternité | L1225-17 | 19 | 11 |
| heures supplémentaires majoration | L3121-36 | 25 | 22 |
| congés payés durée | L3141-3 | introuvable | 61 |
| indemnité précarité | L1243-8 | 6 | 6 |
| maladie maintien salaire | L1226-1 | introuvable | introuvable |
| délai congé démission | L1237-1 | introuvable | introuvable |
| préavis démission cadres | Syntec 4.2 | 1 | 1 |

Les titres de section apportent un gain net et régulier. Des réglages du classement (normalisation
par la longueur, « ou » au lieu de « et » entre les mots) ont aussi été essayés : ils améliorent certains
cas et en dégradent d'autres, sans gain d'ensemble sur ces 12 cas.

**Cause 3 : limites de la recherche par mots-clés.** L1226-1 parle d'« indemnité complémentaire »,
pas de « maintien de salaire » ; L1237-1 de « préavis » de démission, pas de « délai-congé ». Aucun
réglage lexical ne relie ces formulations : c'est le cas d'usage de la recherche sémantique
(embeddings), prévue dans la feuille de route « si l'évaluation montre des articles pertinents
introuvables ».

**Anomalies relevées au passage.**
- **Ancienne numérotation du Code** : 64 articles d'avant la recodification de 2008 (L212-4-4,
  L321-4-2, L321-13…) sont marqués « en vigueur » par la source DILA (non abrogés formellement) et
  ont été transmis à Claude à la place des articles actuels. À examiner avec la juriste avant de les
  exclure.
- **Articles sans numéro** transmis à Claude (conventions : textes attachés, annexes) : ils occupent
  des places parmi les 8 sans être identifiables pour le salarié.

### 3. Le comportement de Claude

- **Fidélité** : 186 citations, toutes vérifiées dans le texte de l'article cité.
- **Prudence** : quand l'article décisif manque, Claude le dit (« je ne peux pas vous confirmer… »)
  au lieu de répondre de mémoire. C'est le comportement voulu, mais il coûte en complétude : les
  réponses à 0 % (préavis ETAM, absences pour recherche d'emploi) sont des réponses prudentes, pas
  fausses.
- **Hors sujet** : quelques digressions sur des articles transmis mais sans rapport (inaptitude pour
  une question de préavis, foires et salons pour des heures supplémentaires), signe qu'un article
  sans rapport parmi les 8 n'est pas neutre.
- **L'erreur commise** (indemnite-licenciement-etam-12ans) : Claude présente deux lectures du
  barème ETAM, dont une (1/3 de mois sur toute l'ancienneté, environ 4,03 mois au lieu de 3,19) que
  le juge estime infondée et susceptible de faire réclamer 26 % de trop. C'est exactement la
  **question ouverte pour la juriste** sur la lecture du barème Syntec (par tranche ou toutes les
  années).

### 4. Le juge

Le juge a appliqué les points clés avec rigueur : il refuse un chiffre présenté comme une option
parmi d'autres. Il a toutefois été trompé par le faux avertissement sur le Code. Ses notes sont à
relire après la correction et la validation du jeu par la juriste.

## Conclusions

1. **La rédaction est sûre** : pas de citation inventée, pas de réponse de mémoire. L'effort doit
   porter sur ce que Claude reçoit, pas sur ce qu'il en fait.
2. **La recherche plafonne la qualité** : améliorer le classement des articles du Code est le levier
   le plus fort et le moins cher (aucun appel payant de plus).
3. **La recherche par mots-clés a atteint ses limites sur une partie des questions** : la recherche
   sémantique est désormais justifiée par des cas concrets.
4. **L'évaluation a déjà payé** : pour 1,36 $, elle a révélé un bug qui aurait touché tous les
   utilisateurs dès le lendemain.

## Actions

| Priorité | Action | État |
|---|---|---|
| 1 | Code applicable après la fin de sa version consolidée | fait (PR #22) |
| 2 | Titres de section dans l'index de recherche (nouveau changeset, réingestion) | à faire |
| 3 | Articles de l'ancienne numérotation et sans numéro : décider avec la juriste s'ils sont écartés de la rédaction | à décider |
| 4 | Relancer l'évaluation après 1 et 2 pour mesurer le gain (≈ 1,40 $ en lots) | après 1 et 2 |
| 5 | Lecture du barème ETAM : à trancher par la juriste, puis consigne ou calculateur | en attente de la juriste |
| 6 | Recherche sémantique pour les articles introuvables par mots-clés (L1226-1, L1237-1) | à concevoir |
| 7 | Validation du jeu de questions par la juriste (page de relecture) | en attente |

## Limites

- **Jeu non validé** : les réponses de référence n'ont pas encore été relues par la juriste ; un point
  clé faux fausserait la note.
- **Petit échantillon** : 20 questions, presque toutes Syntec (les 6 autres conventions ne sont pas
  évaluées), un seul passage ; un écart de 5 points
  n'est pas significatif.
- **Juge automatique** : Claude note Claude ; il a été trompé par le faux avertissement. La juriste
  n'arbitre que les désaccords, ce qui suppose de relire ses verdicts.
- **Résultats entachés par le bug** : toutes les réponses ont été rédigées avec un profil annonçant le
  Code absent ; la prochaine évaluation donnera la vraie base.
