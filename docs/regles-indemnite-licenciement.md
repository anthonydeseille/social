# Indemnité de licenciement : règles du calculateur

Règles appliquées par `POST /api/calculs/indemnite-licenciement`, avec leur source dans le corpus
(Code du travail et convention Syntec, IDCC 1486). Les montants sont calculés par le code, jamais par
le modèle de langage. **Les points marqués « à valider » attendent l'avis de la juriste.**

## Règles appliquées

| Règle | Source | Implémentation |
|---|---|---|
| Droit : CDI, licenciement hors faute grave ou lourde, au moins 8 mois d'ancienneté ininterrompue | L1234-9, Syntec 4.5 (1) | Faute grave ou lourde, CDD, intérim, ou moins de 8 mois complets à la notification : pas d'indemnité, avec la raison |
| Années incomplètes au prorata des mois complets | R1234-1, Syntec 4.5 (2) | Ancienneté en mois complets, divisée par 12 ; calcul exact, arrondi au centième de mois seulement à l'affichage, montant en euros au centime |
| Barème légal : 1/4 de mois par année jusqu'à 10 ans, 1/3 à partir de 10 ans | R1234-2 | Par tranche : exactement 10 ans = 10 × 1/4 |
| Barème Syntec ETAM : 1/4 jusqu'à 10 ans, 1/3 après | Syntec 4.5 (2) | Par tranche (**à valider**, question 1) |
| Barème Syntec ingénieurs et cadres : 1/4 en deçà de 2 ans, 1/3 à partir de 2 ans | Syntec 4.5 (2) | Toutes les années au tiers dès 2 ans (**à valider**, question 1) |
| Montant le plus élevé entre légal et conventionnel | Syntec 4.5 (dernier alinéa) | Les deux calculs sont détaillés, le plus élevé est retenu |
| Ancienneté au sens de Syntec | Syntec 3.7 | Non appliquée finement : pas de prise en compte des suspensions (question 3) |
| Salaire de référence | R1234-4, Syntec 4.5 (2) | Un seul salaire mensuel brut saisi, facultatif (question 4) |

## Questions pour la juriste

### 1. Lecture du barème Syntec au-delà du seuil

Le texte dit, pour les deux catégories, « 1/3 de mois **pour chaque année de présence** » une fois le
seuil passé. Deux lectures possibles :

- **par tranche** : 1/4 pour les années jusqu'au seuil, 1/3 pour les années au-delà ;
- **toutes les années** : dès le seuil atteint, 1/3 pour toutes les années, y compris les premières.

| Cas | Par tranche | Toutes les années |
|---|---|---|
| Cadre, 7 ans et 1 mois | 2 × 1/4 + 5,08 × 1/3 = **2,19 mois** | 7,08 × 1/3 = **2,36 mois** (retenu) |
| ETAM, 12 ans et 1 mois | 10 × 1/4 + 2,08 × 1/3 = **3,19 mois** (retenu) | 12,08 × 1/3 = **4,03 mois** |

Élément à peser : en lecture « toutes les années », l'indemnité d'un cadre **saute** au passage des
2 ans (0,48 mois à 1 an et 11 mois, 0,67 mois à 2 ans) ; en lecture par tranche, elle progresse
continûment (0,48 puis 0,50).

Le calculateur applique aujourd'hui « toutes les années » aux cadres et « par tranche » aux ETAM,
comme le jeu d'évaluation. C'est **incohérent** pour deux phrases construites de la même façon : il
faut trancher, pour chaque catégorie. Le changement tient en une ligne (`BaremeSyntec`).

### 2. Dates d'appréciation

Le calculateur apprécie la condition de 8 mois à la **date de notification**, et calcule le montant
sur l'ancienneté à la **fin du contrat, préavis compris même s'il n'est pas effectué**. Est-ce correct ?

### 3. Suspensions du contrat

Les périodes de suspension (arrêt maladie, congé sans solde…) ne sont pas déduites : le résultat le
signale comme hypothèse. L1234-11 les exclut de l'ancienneté exigée pour le droit ; Syntec 3.7 en
compte certaines (maladies de moins de 6 mois, maternité, congés de formation…). Faut-il les gérer, ou
l'avertissement suffit-il pour une première version ?

### 4. Salaire de référence

Le légal retient la formule la plus favorable entre la moyenne des 12 derniers mois et le tiers des 3
derniers mois, primes annuelles proratisées (R1234-4). Syntec retient le douzième des 12 derniers mois,
primes contractuelles comprises, hors majorations d'heures supplémentaires et indemnités de
déplacement. Le calculateur prend **un seul salaire** saisi par le salarié. Faut-il demander les deux
références, ou un seul chiffre avec une explication suffit-il ?

### 5. Plafond

Le texte consolidé de l'article 4.5 ne prévoit pas de plafond. Confirmer qu'il n'y en a pas.

### 6. Cas hors du calculateur

Non traités, signalés comme tels : licenciement pour inaptitude d'origine professionnelle (indemnité
spéciale doublée, L1226-14), rupture conventionnelle (au moins l'indemnité légale, L1237-13 ; la
conventionnelle s'applique-t-elle ?), salarié protégé, départ ou mise à la retraite. Lesquels traiter
en priorité ?

## Conséquence sur le jeu d'évaluation

Deux réponses de référence de la PR #6 sont à mettre en cohérence avec la décision sur la question 1,
et leurs chiffres sont à préciser (le mois au-delà des années pleines compte au prorata) :

- `indemnite-licenciement-cadre-7ans` (entrée le 2019-10-01) : 7 ans et 1 mois, soit 2,36 mois en
  lecture « toutes les années » (et non 2,33), 1,77 mois au minimum légal (et non 1,75) ;
- `indemnite-licenciement-etam-12ans` (entrée le 2014-10-01) : 12 ans et 1 mois, soit 3,19 mois par
  tranche (et non 3,17), ou 4,03 mois en lecture « toutes les années ».
