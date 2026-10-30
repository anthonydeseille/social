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
2. **Calculateur d'indemnité de licenciement** : légale et Syntec, application de la plus favorable, montants calculés par le code (jamais par le modèle), expliqués par Claude.
   - Calcul et API ✅ (`docs/regles-indemnite-licenciement.md`) ; règles en cours de validation juridique.
   - À faire : brancher le calcul sur le trajet des questions (montant transmis à Claude, qui l'explique).
3. **Recherche sémantique** (embeddings) : seulement si l'évaluation montre des articles pertinents introuvables.

## LLMOps

1. **Juge automatique** ✅ : note les points clés et les erreurs à éviter ; la juriste n'arbitre que les désaccords.
2. **Évaluation via l'API Batch** ✅ (`-Devaluation.lots=true`) : moitié prix, trois lots successifs ; testée sans appel réseau, premier lancement réel à faire. Limite : pas de reprise si un lot dépasse le délai d'attente (les lots déjà terminés sont perdus pour le rapport).
3. **Vérification déterministe des citations** ✅ : chaque extrait cité doit exister dans l'article (aux espaces près), signalé sinon. Reste à faire : un indicateur d'évaluation pour les chiffres absents des articles cités (pas dans l'appli : les montants calculés n'y figurent jamais).
4. **Métriques et tableau de bord** (Micrometer, Actuator) : jetons, coût, latence par étape, refus, troncatures, « aucun article », quota.
   - Métriques ✅ (`/actuator/metrics`, `/actuator/prometheus`, voir `docs/fonctionnement.md`) ; reste le tableau de bord (Prometheus et Grafana, par exemple) et la protection des points d'accès.
5. **Évaluation dans la CI** : sur étiquette `eval` quand une PR touche consignes, recherche ou modèle ; blocage si le score baisse ; historique des scores.
6. **Prompts versionnés** : consignes dans des fichiers, version inscrite dans chaque réponse et chaque rapport.
7. **Traces OpenTelemetry** par question (reformulation, recherche, rédaction), sans donnée personnelle.
8. **Garde-fou données personnelles** : retirer nom, e-mail, numéro de sécurité sociale avant envoi au modèle.
   - Authentification sans clé permanente : fédération d'identité d'Anthropic (Workload Identity Federation), jeton de courte durée échangé contre l'identité de la charge de travail. D'abord pour l'évaluation dans la CI (jeton OIDC de GitHub Actions, règle limitée à la branche `main`), puis pour la mise en ligne (émetteur public du Kubernetes hébergé). Inapplicable au cluster k3d local, dont l'émetteur n'est pas joignable par Anthropic.
9. **Coûts** : cache de prompt, modèle par étape (à décider sur le score d'évaluation), alertes de dépense.
   - Modèle local par Ollama ✅ (`droits.assistant.fournisseur=ollama`, Spring AI) : gratuit, profil gardé sur la machine ; essayé dans le cluster k3d avec Qwen 2.5 7B et Mistral 7B (reformulation corrigée, rédaction lente : plusieurs minutes sur processeur) ; évaluation comparative à faire, idéalement sur une machine avec carte graphique.
10. **Corpus vivant** : à chaque ingestion qui modifie des articles, réévaluer les questions qui les citent.
11. **Automatisation** : évaluation nocturne avec issue en cas de baisse, correction automatique de la CI par l'action Claude, évaluation comparative à chaque nouveau modèle.
12. **Boucle de retour** : « utile / pas utile » en production, mauvaises réponses transformées en questions d'évaluation (anonymisées, validées).

## Pour des utilisateurs réels

1. **Autres conventions collectives** : 6 ajoutées ✅ (métallurgie, commerce alimentaire, transports routiers, hôtels-cafés-restaurants, services de l'automobile, propreté ; environ 3,5 millions de salariés de plus). Leurs barèmes d'indemnité restent à intégrer, après validation juridique. Suivantes possibles : bâtiment (1596, 1597), handicap (0413), commerces de gros (0573).
2. **Retrouver l'IDCC** à partir de l'entreprise (SIRET) ou du nom de la convention.
3. **Interface web** : profil gardé dans le navigateur, question, réponse avec citations cliquables.
   - Première version minimale ✅ (`src/main/resources/static`) ; à enrichir (calculateur d'indemnité, retours « utile / pas utile »).
4. **Mise à jour planifiée du corpus** et affichage des changements.
5. **Mise en ligne** : hébergement, limitation par client, absence de journalisation des profils, mentions « information générale, pas un conseil juridique ».
   - Déploiement local dans Kubernetes ✅ (k3d, `deploy/`, `docs/deploiement-local.md`) : image, Postgres, ingestion en Job, manifestes validés par le CI. À suivre : diagnostic par k8sgpt et plugins k9s.

## En suspens

- Protection de `main` (GitHub Pro ou dépôt public).
- GitLab CI, pour comparaison.
