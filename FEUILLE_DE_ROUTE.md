# Feuille de route

Objectif : permettre à un salarié de connaître ses droits selon son profil, avec des réponses
fiables et sourcées. Ordre indicatif ; chaque étape passe par une PR, le CI et la revue Claude.

## Fait

- Ingestion du Code du travail et de la convention Syntec (sources épinglées, journal des changements)
- CI GitHub Actions, action Claude (revue automatique, mentions `@claude`)
- Profil salarié et API de consultation
- Recherche plein texte en français
- Questions-réponses avec citations (Claude)
- Lanceur d'évaluation sur les vraies sources, juge automatique

## Plan de base

1. **Évaluation de référence** : valider le jeu de questions avec la juriste, lancer l'évaluation, ajuster reformulation, consignes ou recherche selon les résultats.
2. **Calculateur d'indemnité de licenciement** : légale et Syntec, application de la plus favorable, montants calculés par le code (jamais par le modèle), expliqués par Claude.
3. **Recherche sémantique** (embeddings) : seulement si l'évaluation montre des articles pertinents introuvables.

## LLMOps

1. **Juge automatique** ✅ : note les points clés et les erreurs à éviter ; la juriste n'arbitre que les désaccords.
2. **Évaluation via l'API Batch** : moitié prix, l'évaluation n'étant pas urgente.
3. **Vérification déterministe des citations** : chaque extrait cité doit exister mot pour mot dans l'article ; signaler les chiffres absents des articles cités.
4. **Métriques et tableau de bord** (Micrometer, Actuator) : jetons, coût, latence par étape, refus, troncatures, « aucun article », quota.
5. **Évaluation dans la CI** : sur étiquette `eval` quand une PR touche consignes, recherche ou modèle ; blocage si le score baisse ; historique des scores.
6. **Prompts versionnés** : consignes dans des fichiers, version inscrite dans chaque réponse et chaque rapport.
7. **Traces OpenTelemetry** par question (reformulation, recherche, rédaction), sans donnée personnelle.
8. **Garde-fou données personnelles** : retirer nom, e-mail, numéro de sécurité sociale avant envoi au modèle.
9. **Coûts** : cache de prompt, modèle par étape (à décider sur le score d'évaluation), alertes de dépense.
10. **Corpus vivant** : à chaque ingestion qui modifie des articles, réévaluer les questions qui les citent.
11. **Automatisation** : évaluation nocturne avec issue en cas de baisse, correction automatique de la CI par l'action Claude, évaluation comparative à chaque nouveau modèle.
12. **Boucle de retour** : « utile / pas utile » en production, mauvaises réponses transformées en questions d'évaluation (anonymisées, validées).

## Pour des utilisateurs réels

1. **Autres conventions collectives** (métallurgie, commerce alimentaire, hôtels-cafés-restaurants, bâtiment…).
2. **Retrouver l'IDCC** à partir de l'entreprise (SIRET) ou du nom de la convention.
3. **Interface web** : profil gardé dans le navigateur, question, réponse avec citations cliquables.
4. **Mise à jour planifiée du corpus** et affichage des changements.
5. **Mise en ligne** : hébergement, limitation par client, absence de journalisation des profils, mentions « information générale, pas un conseil juridique ».

## En suspens

- Protection de `main` (GitHub Pro ou dépôt public).
- GitLab CI, pour comparaison.
