# Le trajet d'une question

Ce que fait l'application quand un salarié pose une question : deux appels à Claude seulement, tout
le reste est du code et de la base de données (gratuit et prévisible).

```mermaid
flowchart TD
    S(["👤 Salarié<br/>question + profil<br/>(IDCC, statut, contrat, ancienneté, situations…)"])

    S --> G0
    G0{"0. Garde-fous d'entrée<br/>validation · quota du jour"}
    G0 -->|"invalide"| E400["400 Requête invalide"]
    G0 -->|"quota atteint"| E429["429 Trop de questions"]
    G0 -->|"ok"| P1

    P1["1. Contexte du profil<br/>ancienneté · textes applicables à la date de référence<br/>avertissements"]
    P1 --> C2

    C2["🤖 2. Reformulation (Claude)<br/>1 à 4 requêtes de mots-clés juridiques<br/>sortie structurée · ≈ 1 centime"]
    C2 -.->|"Claude indisponible ou refus :<br/>question brute"| R3
    C2 --> R3

    R3["3. Recherche (PostgreSQL)<br/>plein texte français, sans accents<br/>Code + convention · articles applicables<br/>fusion → 8 meilleurs articles<br/>moins de 100 ms"]
    R3 -->|"aucun article"| VIDE["Réponse vide + avertissement<br/>(pas d'appel n° 2)"]
    R3 --> C4

    C4["🤖 4. Rédaction (Claude)<br/>8 articles = 8 documents, citations activées<br/>+ profil + question + consignes<br/>≈ 4 à 7 centimes"]
    C4 -->|"panne"| E503["503 Service indisponible"]
    C4 -.->|"refus des filtres :<br/>rejoué sur un autre modèle"| C4
    C4 --> V5

    V5["5. Contrôles de sortie<br/>extraits cités présents dans les articles ?<br/>réponse interrompue ?"]
    V5 --> REP

    REP(["📄 Réponse<br/>passages avec citations · sources<br/>requêtes · contexte · avertissements<br/>citations non vérifiées"])

    classDef claude fill:#fde7c8,stroke:#d97706,color:#000
    classDef gratuit fill:#e3f2e1,stroke:#2e7d32,color:#000
    classDef erreur fill:#fbe0e0,stroke:#c62828,color:#000
    class C2,C4 claude
    class G0,P1,R3,V5 gratuit
    class E400,E429,E503,VIDE erreur
```

Légende : orange = appel payant à Claude, vert = code et base de données (gratuit), rouge = sortie en erreur.

## Les étapes

| Étape | Rôle | Coût |
|---|---|---|
| 0. Garde-fous d'entrée | Validation de la question (≤ 1000 caractères) et du profil, quota journalier global (200 par défaut). Ni la question ni le profil ne sont stockés ou journalisés (données sensibles : grossesse, santé). | gratuit |
| 1. Contexte du profil | Ancienneté à la date de référence (aujourd'hui par défaut), textes applicables à cette date (Code du travail, convention collective si intégrée), avertissements (convention non renseignée ou non intégrée). | gratuit |
| 2. Reformulation | Claude transforme la question en 1 à 4 requêtes de mots-clés juridiques, tels qu'ils apparaissent dans les textes. S'il est indisponible ou refuse, la recherche se fait sur la question brute. | ≈ 1 centime |
| 3. Recherche | Recherche plein texte dans le Code et la convention, limitée aux articles applicables à la date de référence ; fusion des résultats et sélection des 8 meilleurs. | gratuit |
| 4. Rédaction | Claude reçoit les 8 articles comme documents, avec les citations activées, et rédige en suivant ses consignes : s'en tenir aux articles, appliquer la disposition la plus favorable au salarié en le disant, donner les chiffres et les conditions, rappeler qu'il s'agit d'une information générale. | ≈ 4 à 7 centimes |
| 5. Contrôles de sortie | Vérification que chaque extrait cité existe dans l'article cité ; signalement d'une réponse interrompue. | gratuit |

## Ce qu'il faut retenir

- **Claude ne cherche pas lui-même et ne répond pas de mémoire** : il ne voit que les articles choisis à l'étape 3, et chaque affirmation pointe vers le passage précis de l'article qui la fonde.
- **La qualité dépend d'abord de la recherche** : si le bon article n'est pas parmi les 8, Claude ne peut pas le citer. C'est pourquoi l'évaluation mesure séparément la recherche et les citations.
- **Les incidents sont visibles** : refus, panne, réponse interrompue, citation introuvable ou absence d'article donnent un statut ou un avertissement explicite, jamais une réponse présentée comme complète.

## Observabilité

Chaque question alimente des métriques (Micrometer), consultables sur `/actuator/metrics` et au
format Prometheus sur `/actuator/prometheus`, **sans donnée personnelle** (ni question, ni profil) :

| Métrique | Étiquettes | Ce qu'elle suit |
|---|---|---|
| `droits.questions` | issue : repondue, aucun_article, refus, tronquee, indisponible | issue de chaque question |
| `droits.questions.etape` | etape : reformulation, recherche, redaction | durée de chaque étape |
| `droits.claude.appels` | etape, issue : succes, refus, erreur | appels à Claude |
| `droits.claude.jetons` | etape, sens : entree, sortie | jetons consommés (donc le coût) |
| `droits.citations` | verification : verifiee, non_verifiee | résultat du contrôle de sortie |
| `droits.questions.quota_atteint` | | questions refusées par le quota |

Ces points d'accès sont à protéger, ou à exposer sur un port interne, avant toute mise en ligne.

## L'évaluation

Le même trajet sert à mesurer la fiabilité des réponses, sur un jeu de questions dont la réponse a été
validée par une juriste (`src/test/resources/evaluation/questions.yaml`).

```mermaid
flowchart LR
    J["Jeu de questions<br/>validé par la juriste"] --> T["Même trajet<br/>étapes 0 à 5"]
    T --> JG["🤖 Juge (Claude)<br/>points clés présents ?<br/>erreurs commises ?<br/>≈ 1 à 2 centimes"]
    JG --> RP["Rapport<br/>recherche · citations<br/>citations vérifiées · qualité · coût"]

    classDef claude fill:#fde7c8,stroke:#d97706,color:#000
    class JG claude
```

| Indicateur | Question posée |
|---|---|
| Recherche | Les articles attendus ont-ils été transmis à Claude ? |
| Citations | Claude a-t-il cité les articles attendus ? |
| Citations vérifiées | Les extraits cités existent-ils dans les articles ? |
| Qualité (juge) | Les points clés de la réponse de référence sont-ils présents, chiffres exacts compris ? |

Lancement : voir `CLAUDE.md` (section Tests).

## Calculateur d'indemnité de licenciement

`POST /api/calculs/indemnite-licenciement` calcule l'indemnité **par le code**, sans appel au modèle
(gratuit) : légale et, si la convention est intégrée, conventionnelle ; le montant le plus élevé est
retenu, avec le détail des deux calculs, les articles sources et les hypothèses.

```mermaid
flowchart LR
    D(["Profil + date de notification<br/>+ fin du contrat + motif<br/>+ salaire (facultatif)"]) --> C{"Droit ?<br/>CDI · hors faute grave<br/>8 mois à la notification"}
    C -->|"non"| N["Pas d'indemnité<br/>+ raison"]
    C -->|"oui"| L["Légal<br/>R1234-2"]
    C -->|"oui"| K["Conventionnel<br/>ex. Syntec 4.5"]
    L --> M["Le plus élevé<br/>en mois de salaire<br/>(× salaire = euros)"]
    K --> M

    classDef gratuit fill:#e3f2e1,stroke:#2e7d32,color:#000
    class C,L,K,M gratuit
```

Règles, sources et questions en cours de validation juridique :
[`regles-indemnite-licenciement.md`](regles-indemnite-licenciement.md).

## Évolution prévue

Le calculateur sera branché sur le trajet des questions, entre la recherche et la rédaction : quand la
question porte sur l'indemnité de licenciement, le montant calculé sera transmis à Claude, qui
l'expliquera au lieu de le calculer. La suite est décrite dans
[`FEUILLE_DE_ROUTE.md`](../FEUILLE_DE_ROUTE.md).
