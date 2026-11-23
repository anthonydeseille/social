# Feuille de route

Objectif : permettre à un salarié de connaître ses droits selon son profil, avec des réponses
fiables et sourcées. Ordre indicatif ; chaque étape passe par une PR, le CI et la revue Claude.

## Fait

- Ingestion du Code du travail et de 7 conventions collectives, un step par source (sources épinglées, journal des changements)
- CI GitHub Actions, action Claude (revue automatique, mentions `@claude`)
- Profil salarié et API de consultation
- Recherche plein texte en français
- Questions-réponses avec citations (Claude)
- Lanceur d'évaluation sur les vraies sources, juge automatique

## Plan de base

1. **Évaluation de référence** : valider le jeu de questions avec la juriste, lancer l'évaluation, ajuster reformulation, consignes ou recherche selon les résultats.
   - Jeu de 20 questions prêt ✅ (articles vérifiés dans le corpus à chaque lancement) ; validation juridique en attente (champ `valide`).
   - Première évaluation ✅ ([`docs/evaluations/2026-10-30-premiere-evaluation.md`](docs/evaluations/2026-10-30-premiere-evaluation.md)) : citations fiables (186/186), qualité 70 %, recherche 45 % (articles du Code mal classés) ; un bug d'applicabilité du Code révélé et corrigé (PR #22). Titres de section ajoutés à l'index ✅.
   - Deuxième évaluation ✅ ([`docs/evaluations/2026-10-31-titres-section.md`](docs/evaluations/2026-10-31-titres-section.md)) : recherche 58 % (Code retrouvé), qualité 71 % ; la convention est désormais évincée par les sections du Code. Suite : sélection équilibrée, mesurée par l'évaluation gratuite de la recherche : places réservées à la convention ✅ (2 sur 8, si ses articles sont assez pertinents : 26 → 28 articles attendus sur 48, Syntec 4.5 transmis pour les trois questions d'indemnité) ; qualité 67 % → 77 % à l'évaluation complète ([`docs/evaluations/2026-11-23c-places-convention.md`](docs/evaluations/2026-11-23c-places-convention.md)) ; plafond par section du Code essayé et écarté (sans gain) ; historique : [`docs/evaluations/`](docs/evaluations/README.md).
   - Troisième évaluation ✅, la première lancée par la CI ([`docs/evaluations/2026-11-23-calcul-dans-questions.md`](docs/evaluations/2026-11-23-calcul-dans-questions.md)) : les deux questions de calcul sont justes (indemnité cadre : 0 % → 100 %) ; la recherche baisse (58 % → 52 %) parce que la reformulation varie d'un lancement à l'autre, mêmes consignes et même modèle. Seuil de l'évaluation complète réservé à la qualité, aux erreurs et aux citations vérifiées ✅ : la recherche y est indicative, contrôlée sur requêtes figées.
2. **Calculateur d'indemnité de licenciement** : légale et Syntec, application de la plus favorable, montants calculés par le code (jamais par le modèle), expliqués par Claude.
   - Calcul et API ✅ (`docs/regles-indemnite-licenciement.md`) ; règles en cours de validation juridique.
   - Branché sur le trajet des questions ✅ : une question sur l'indemnité de licenciement déclenche le calcul, dont Claude reprend les chiffres sans les recalculer (`CalculsQuestions`). Effet mesuré par la troisième évaluation ✅ (avant la correction qui fait aussi calculer « une indemnité et un préavis ? », question licenciement-6-mois). Affiché dans la page web ✅ ; salaire mensuel brut lu dans la question, pour un montant en euros ✅.
3. **Recherche sémantique** (embeddings) : seulement si l'évaluation montre des articles pertinents introuvables.
   - Justifiée par la première évaluation : L1226-1 (« indemnité complémentaire », cherché comme « maintien de salaire ») et L1237-1 sont introuvables par mots-clés.

## LLMOps

Objectif : une boucle fermée. Chaque changement (consigne, recherche, modèle, corpus) est mesuré
automatiquement, comparé à l'historique, tracé en production et corrigé à partir des retours.

### En place

- **Évaluation** ✅ : jeu de 20 questions, juge automatique (points clés, erreurs à éviter), API Batch (moitié prix), rapport par question ; historique des résultats ([`docs/evaluations/`](docs/evaluations/README.md), `historique.csv`). Limite : pas de reprise si un lot dépasse le délai d'attente.
- **Vérification déterministe des citations** ✅ : chaque extrait cité doit exister dans l'article.
- **Métriques** ✅ (Micrometer, `/actuator/prometheus`) : issues, durées par étape, appels et jetons par fournisseur, citations, quota.
- **Quota journalier** ✅ et **deux fournisseurs** ✅ (Claude, Ollama local).
- **Déploiement local k3d** ✅, base du tableau de bord et de la mise en ligne.

### Plan

1. ✅ **Évaluation gratuite de la recherche, dans chaque PR** (`RechercheEvaluationIT`, job CI) : requêtes de reformulation figées par question (fichier versionné, régénérées à la demande), puis part des articles attendus parmi les articles retenus, sans appel au modèle ; résumé JSON de chaque évaluation complète, versé dans `historique.csv`.
2. ✅ **Consignes versionnées** : consignes (reformulation, rédaction, juge) dans des fichiers `prompts/`, version (empreinte du texte envoyé) inscrite dans chaque réponse de l'API, chaque rapport, l'historique et les métriques ; requêtes figées signalées périmées quand la consigne de reformulation change.
3. ✅ **Évaluation complète dans la CI** ([`docs/evaluation-ci.md`](docs/evaluation-ci.md), workflow `evaluation.yml` ; fédération à configurer dans la Console avant le premier lancement) : sur étiquette `eval`, et d'office quand une PR touche consignes, recherche ou modèle ; authentification par fédération d'identité (jeton OIDC de GitHub Actions, règle limitée au dépôt, sans clé stockée) ; comparaison à la dernière ligne de l'historique, blocage en cas de baisse nette, résumé commenté dans la PR ; budget : à la demande et hebdomadaire plutôt que nocturne (1,13 à 1,36 $ par lancement jusqu'ici).
4. **Observabilité en production** : traces OpenTelemetry par question (une étape par span : jetons, modèle, version des consignes, articles transmis ; aucune donnée du profil) ; Grafana et alertes dans le cluster k3d (coût par jour, latence, refus, troncatures, citations non vérifiées, « aucun article ») ; diagnostic du cluster par k8sgpt, plugins k9s.
5. ✅ **Garde-fous** (déterministes, gratuits ; reste : questions hors sujet dans l'évaluation complète, qui passent par le modèle) : en entrée, retrait des données personnelles avant envoi au modèle (e-mail, numéro de sécurité sociale, téléphone) et détection des tentatives d'injection ; en sortie, chaque chiffre de la réponse présent dans les articles cités ou venant du calculateur ; jeu adversarial dans l'évaluation (hors sujet, injections, profils incohérents).
6. ✅ **Coût et choix des modèles, sur preuves** ([`docs/couts-et-modeles.md`](docs/couts-et-modeles.md) ; outils en place, mesures à lancer : reformulation Opus / Sonnet / Haiku, puis évaluation complète du couple retenu ; cache de prompt écarté, preuves dans la doc) : évaluation par modèle et par étape (Opus, Sonnet, Haiku, Ollama ; reformulation par un modèle plus léger si le score de recherche tient), cache de prompt sur les consignes, alerte de dépense, évaluation comparative à chaque nouveau modèle.
7. **Boucle de retour et corpus vivant** : « utile / pas utile » dans la page web (sans profil), mauvaises réponses transformées en questions d'évaluation après validation de la juriste ; réévaluation des questions qui citent un article modifié par une ingestion (journal des changements) ; jeu étendu aux 6 autres conventions.

## Pour des utilisateurs réels

1. **Autres conventions collectives** : 6 ajoutées ✅ (métallurgie, commerce alimentaire, transports routiers, hôtels-cafés-restaurants, services de l'automobile, propreté ; environ 3,5 millions de salariés de plus). Leurs barèmes d'indemnité restent à intégrer, après validation juridique. Suivantes possibles : bâtiment (1596, 1597), handicap (0413), commerces de gros (0573).
2. **Retrouver l'IDCC** à partir de l'entreprise (SIRET) ou du nom de la convention.
   - Par le nom, le SIREN ou le SIRET de l'entreprise ✅ (annuaire public des entreprises, `/api/entreprises`, choix dans la page). À faire : par le nom de la convention, et proposer les conventions intégrées les plus proches quand celle de l'entreprise ne l'est pas.
3. **Interface web** : profil gardé dans le navigateur, question, réponse avec citations cliquables.
   - Première version minimale ✅ (`src/main/resources/static`) ; à enrichir (calculateur d'indemnité, retours « utile / pas utile »).
4. **Mise à jour planifiée du corpus** et affichage des changements.
5. **Mise en ligne** : hébergement, limitation par client, absence de journalisation des profils, mentions « information générale, pas un conseil juridique ».
   - Déploiement local dans Kubernetes ✅ (k3d, `deploy/`, `docs/deploiement-local.md`) : image, Postgres, ingestion en Job, manifestes validés par le CI. À suivre : diagnostic par k8sgpt et plugins k9s.

## Vitrine publique

But : montrer une application LLMOps de bout en bout. La librairie montre la mécanique, social son usage réel.

1. **Librairie publique d'évaluation de LLM pour Java, avec suivi des coûts** (dépôt séparé, intégrée à JUnit 5 et Maven, sans dépendance à Spring, licence Apache 2.0). Contenu extrait de social : prix des modèles (table surchargeable, valeurs par défaut), coût de chaque appel et budget journalier (alertes, refus), version des consignes par empreinte, historique CSV et comparaison avec seuils de bruit, rapport d'évaluation (tarif direct ou par lots). Rien de propre au métier : questions validées, historique, vérification des citations et des chiffres, consignes du juge et recherche restent dans social.
   - Nom : `llmeval4j` (`io.github.anthonydeseille:llmeval4j`), code en anglais.
   - Dépôt public avec CI (Java 17 et 25) et exemple minimal ✅ ([github.com/anthonydeseille/llmeval4j](https://github.com/anthonydeseille/llmeval4j)).
   - Extraction du code ✅ : prix, versions des consignes, budget journalier, historique CSV, contrôle de régression.
   - Social consomme la librairie ✅ (PR #34).
   - 0.1.0 publiée ✅ ([release](https://github.com/anthonydeseille/llmeval4j/releases/tag/0.1.0), JitPack), utilisée par social.
   - À faire : Maven Central (`io.github.anthonydeseille`, signature GPG, workflow de release).
2. **Passer social en public** :
   - Claude ne répond qu'au propriétaire et aux collaborateurs (`claude.yml`) ✅ (PR #32).
   - Permet aussi la protection de `main` (voir « En suspens »).
   - À faire, gitleaks en premier (un secret trouvé impose de réécrire l'historique avant toute relecture) : gitleaks sur tout l'historique Git ; règle de fédération resserrée sur le propriétaire du dépôt ; accord de la juriste pour publier ses réponses validées (et la nommer ou non) ; relecture de `docs/evaluations/`.

## En suspens

- Protection de `main` (GitHub Pro ou dépôt public, voir « Vitrine publique »).
- GitLab CI, pour comparaison.
