# Deuxième évaluation : après la correction du Code et les titres de section (31 octobre 2026)

Même jeu de 20 questions, même mode (API Batch), sur `main` au commit `6c672d5`. Depuis la
[première évaluation](2026-10-30-premiere-evaluation.md), deux changements :

- **PR #22** : le Code du travail n'est plus jugé « non intégré » au 1er novembre (dates de version
  consolidée prises pour sa validité) ;
- **PR #24** : les titres de section entrent dans l'index de recherche, au même poids que le numéro.

Rapport brut : [`2026-10-31-rapport-brut.md`](2026-10-31-rapport-brut.md).

## En bref

- **Recherche : 45 % → 58 %** (21 → 26 articles attendus transmis sur 48). Les articles de principe
  du Code remontent comme prévu (13 gagnés).
- **Mais la convention est évincée** : 7 articles Syntec décisifs ont disparu (4.5 trois fois, 5.1,
  5.7, 9.3, 4.2), plus le chapitre IX (texte de regroupement, attendu par une seule question). Le titre de section avantage tous les articles d'une même section du Code, qui
  occupent alors les 8 places.
- **Qualité stable : 70 % → 71 %**, faite de nets progrès (absences pour recherche d'emploi, heures
  complémentaires) et de reculs là où l'article Syntec manque (indemnité cadre, congés payés).
- **Citations vérifiées : 197/197.** Plus aucun avertissement « Code non intégré ».
- **Coût : 1,36 $ → 1,13 $** (un tiers de jetons en entrée en moins : les articles transmis sont
  plus courts).

**Conclusion** : le levier suivant n'est plus de trouver les articles du Code, mais d'**équilibrer la
sélection** entre le Code et la convention, et entre sections.

## Résultats

| Indicateur | 30 octobre | 31 octobre |
|---|---|---|
| Recherche (moyenne par question) | 45 % | **58 %** |
| Articles attendus transmis | 21/48 | **26/48** |
| Citations vérifiées | 186/186 | 197/197 |
| Qualité (juge, moyenne par question) | 70 % | 71 % |
| Erreurs à éviter commises | 1 | 1 |
| Jetons en entrée / en sortie | 267 900 / 53 348 | 174 631 / 50 289 |
| Coût (questions + juge) | 1,07 $ + 0,29 $ | 0,85 $ + 0,28 $ |

### Par question

| Question | Recherche | Qualité | Erreurs |
|---|---|---|---|
| periode-essai-cadre | 2/3 → **3/3** | 100 % → 100 % | 0 → 0 |
| periode-essai-etam-250 | 1/2 → **2/2** | 100 % → 100 % | 0 → 0 |
| preavis-demission-cadre | 1/2 → 1/2 | 100 % → 100 % | 0 → 0 |
| preavis-licenciement-etam-3ans | 0/2 → **1/2** | 0 % → **33 %** | 0 → 0 |
| absences-recherche-emploi | 0/1 → **1/1** | 0 % → **100 %** | 0 → 0 |
| indemnite-licenciement-cadre-7ans | 1/3 → 1/3 | 75 % → **0 %** | 0 → 0 |
| indemnite-licenciement-etam-12ans | 1/2 → 1/2 | 67 % → **100 %** | 1 → **0** |
| licenciement-6-mois | 1/4 → 1/4 | 50 % → 50 % | 0 → 0 |
| grossesse-annonce | 1/1 → 1/1 | 100 % → 100 % | 0 → 0 |
| grossesse-licenciement | 1/2 → 1/2 | 100 % → 100 % | 0 → 0 |
| grossesse-amenagement-horaire | 1/2 → **2/2** | 100 % → 100 % | 0 → 0 |
| conge-maternite | 1/2 → 1/2 | 33 % → 33 % | 0 → 0 |
| conges-payes-6ans | 1/2 → 1/2 | 100 % → **33 %** | 0 → **1** |
| conge-mariage | 2/2 → **1/2** | 100 % → **67 %** | 0 → 0 |
| maladie-cadre-2ans | 1/2 → 1/2 | 100 % → 100 % | 0 → 0 |
| maladie-etam-6mois | 1/2 → 1/2 | 67 % → 67 % | 0 → 0 |
| temps-partiel-heures-complementaires | 2/5 → **4/5** | 40 % → **80 %** | 0 → 0 |
| heures-supplementaires | 1/4 → **0/4** | 50 % → **25 %** | 0 → 0 |
| fin-cdd | 0/2 → 0/2 | 25 % → 25 % | 0 → 0 |
| handicap-preavis | 2/3 → 2/3 | 100 % → 100 % | 0 → 0 |

Un même score « 1/2 » peut cacher un échange : conges-payes-6ans gagne L3141-3 (Code) mais perd 5.1
(Syntec).

### Articles attendus gagnés et perdus

| Gagnés (14) | Perdus (9) |
|---|---|
| L1221-19 ×2, L1234-1 ×2, L1234-9 ×2, R1234-2, L1225-4, L1225-16, L3141-3, L3123-9, L3123-20, L3123-28 (Code) ; 4.3 (Syntec) | 4.5 ×3, 5.1, 5.7, 9.3, 4.2, chapitre IX (Syntec) ; L3121-33 (Code) |

## Analyse

### 1. Les deux corrections ont fait leur effet

- **Code applicable** : plus aucune réponse ne porte l'avertissement « Code non intégré ». Claude
  compare désormais le Code et la convention quand il a les deux (indemnite-licenciement-etam-12ans
  passe à 100 %, et l'erreur de la première évaluation, une seconde lecture du barème ETAM, n'est plus
  commise).
- **Titres de section** : 13 articles du Code gagnés, dont tous ceux que l'essai SQL de la première
  évaluation annonçait (L1234-1, L1234-9, L1225-4, L3141-3, L3123-28).

### 2. Effet inverse : la convention évincée par les sections du Code

Le titre de section donne le même avantage à tous les articles d'une section. Exemples d'articles
transmis :

| Question | Les 8 articles transmis | Syntec manquant |
|---|---|---|
| preavis-licenciement-etam-3ans | L1234-1 à L1234-8 (toute la sous-section « Préavis et indemnité compensatrice de préavis ») | 4.2 |
| conges-payes-6ans | L3141-3, -4, -5, -5-1, -9, -10, -11, D3141-3 | 5.1 |
| conge-mariage | L3142-1, -1-1, -2, -4, -5, D3142-1-1 à -1-3 | 5.7 |
| indemnite-licenciement-cadre-7ans | L1233-26, -58, -59, -60, -60-1, L1234-9, -10, -11 | 4.5 |

Claude, privé de l'article de la convention, reste prudent (« je ne peux pas vous confirmer la règle
Syntec ») : d'où les reculs (indemnité cadre 75 % → 0 %, congés payés 100 % → 33 %). La seule
« erreur » comptée (conges-payes-6ans) est ce refus de confirmer le jour d'ancienneté Syntec.

### 3. Ce qui reste hors d'atteinte

- **heures-supplementaires** (0/4) : les places vont à des articles sans rapport (D5132-43-* :
  ateliers et chantiers d'insertion), dont la sous-section « Dérogation à la durée hebdomadaire de
  travail » correspond mot pour mot à la requête « durée hebdomadaire travail » ; L3121-27, -33, -36
  et Syntec 6.2 sont évincés. Le plafond par section limiterait ce cas aussi.
- **fin-cdd** (0/2) : L1243-8 (prime de précarité) toujours absent ; Claude confond alors la prime et
  l'indemnité de congés payés.
- **Formulations différentes** (L1226-1 « indemnité complémentaire », L1237-1) : relèvent de la
  recherche sémantique.

## Conclusions

1. **La recherche progresse nettement (45 → 58 %)** et les deux corrections sont confirmées.
2. **La sélection doit être équilibrée** : réserver des places à la convention quand elle est
   applicable, et limiter le nombre d'articles retenus par section. C'est le prochain levier, sans
   coût supplémentaire.
3. **La qualité dépend de la convention autant que du Code** : sur ce jeu presque entièrement Syntec,
   perdre l'article conventionnel coûte plus que de gagner un article du Code.
4. **Mesurer la recherche gratuitement devient indispensable** : ces réglages se jouent article par
   article, et chaque essai coûte aujourd'hui une évaluation complète (≈ 1,10 $).

## Actions

| Priorité | Action | État |
|---|---|---|
| 1 | Évaluation gratuite de la recherche, dans chaque PR (requêtes figées, référence versionnée) | en cours (point 1 du plan LLMOps) |
| 2 | Sélection équilibrée : places réservées à la convention, plafond d'articles par section, mesurés par l'évaluation de la recherche | à faire |
| 3 | Nouvelle évaluation complète après 2 | après 2 |
| 4 | Articles de l'ancienne numérotation et sans numéro, recherche sémantique, validation par la juriste | inchangé depuis la première évaluation |

## Limites

Mêmes limites que la [première évaluation](2026-10-30-premiere-evaluation.md#limites) : jeu non validé
par la juriste, 20 questions presque toutes Syntec, un seul passage, juge automatique. Les
reformulations ne sont pas identiques d'un passage à l'autre (modèle non déterministe) : une partie des
écarts par question vient des requêtes elles-mêmes, ce que l'évaluation de la recherche à requêtes
figées éliminera.
