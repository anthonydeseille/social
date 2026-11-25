# Droits des salariés

[![CI](https://github.com/anthonydeseille/social/actions/workflows/ci.yml/badge.svg)](https://github.com/anthonydeseille/social/actions/workflows/ci.yml)
[![Licence Apache 2.0](https://img.shields.io/badge/licence-Apache%202.0-blue.svg)](LICENSE)

*In English: a Spring Boot application that answers French employees' questions about their rights,
using Claude grounded only on applicable articles of the French Labour Code and collective
agreements, with verified citations, input and output guardrails, cost tracking and a continuous
evaluation pipeline in CI. The code and the documentation are written in French.*

Application qui aide un salarié à connaître ses droits selon son profil (convention collective,
ancienneté, statut, contrat, grossesse…), à partir du Code du travail et des conventions collectives
publiés par SocialGouv. Conventions intégrées : Syntec (1486), métallurgie (3248), commerce
alimentaire (2216), transports routiers (0016), hôtels-cafés-restaurants (1979), services de
l'automobile (1090), propreté (3043). Les réponses sont rédigées par Claude **à partir des seuls articles
applicables**, chaque affirmation citant le texte exact qui la fonde.

> Information générale, pas un conseil juridique. Projet personnel.

## Ce que montre ce projet

Une application LLM de bout en bout, de la recherche des sources à l'exploitation, où chaque
changement est mesuré.

- **Réponses vérifiables** : Claude ne reçoit que les articles applicables à la date du profil et les
  cite (citations natives de l'API). Le code vérifie ensuite que chaque extrait cité existe dans
  l'article, et signale tout chiffre absent des sources. Voir [le trajet d'une question](docs/fonctionnement.md).
- **Garde-fous** : refus des tentatives d'injection avant tout appel payant, masquage des données
  personnelles de la question. Le profil n'est jamais stocké ni journalisé (grossesse, santé : RGPD).
  Les cas pièges sont rejoués à chaque build (`adversarial.yaml`).
- **Évaluation continue** : un jeu de questions de salariés avec réponses de référence, un juge
  automatique et un historique des résultats. Dans la CI, l'évaluation de la recherche, gratuite,
  tourne à chaque PR. L'évaluation complète (environ 1 $) se déclenche sur les PR qui touchent aux
  consignes, à la recherche ou au modèle, et chaque lundi. Elle bloque sur une baisse nette par
  rapport à l'historique. Voir [l'évaluation](docs/fonctionnement.md#lévaluation),
  [l'historique](docs/evaluations/README.md) et [l'évaluation en CI](docs/evaluation-ci.md).
- **Coûts maîtrisés** : coût de chaque appel, budget journalier avec alertes, modèles choisis par la
  mesure, évaluation par lots à moitié prix. Voir [coûts et modèles](docs/couts-et-modeles.md).
- **Recherche hybride** : plein texte PostgreSQL (français, sans accents, titres de section pondérés),
  avec en option une recherche par le sens (pgvector, vecteurs bge-m3 calculés en local).
- **Calculs faits par le code, pas par le modèle** : indemnité de licenciement (Code du travail et
  conventions), passée au modèle comme un résultat à expliquer. Voir le
  [calculateur](docs/fonctionnement.md#calculateur-dindemnité-de-licenciement).
- **Exploitation** : traces et métriques OpenTelemetry, tableaux de bord Grafana, déploiement dans
  Kubernetes en local (k3d). Voir [l'observabilité](docs/fonctionnement.md#observabilité) et
  [le déploiement local](docs/deploiement-local.md).
- **Ingestion versionnée** : un job Spring Batch par source, sources épinglées sur un commit,
  historique des changements d'articles.

La mécanique générique d'évaluation (prix des modèles, versions des consignes, budget, historique,
détection des régressions) a été extraite dans une librairie publique :
[llmeval4j](https://github.com/anthonydeseille/llmeval4j).

## Où en est-on

Sur la dernière évaluation complète (20 questions, Claude Opus), le juge note la qualité des réponses
autour de 72 %, et 60 % des articles attendus sont transmis à la rédaction. Entre deux lancements du
même système, la note varie de ±6 points. Les réponses de référence sont **en cours de validation par
une juriste** : jusque-là, les notes restent indicatives. Les prochaines étapes sont dans la
[feuille de route](FEUILLE_DE_ROUTE.md).

## Démarrer

Prérequis : Java 25, Docker.

```bash
./mvnw verify                      # tests unitaires et d'intégration
./mvnw spring-boot:run -Dspring-boot.run.arguments="--droits.ingestion.run=true --spring.main.web-application-type=none"
                                   # ingestion des sources (Code du travail, 7 conventions)
./mvnw spring-boot:run             # page web sur http://localhost:8080/, API sur /api
```

Les réponses aux questions (`POST /api/questions`) appellent Claude : clé dans `ANTHROPIC_API_KEY`
(facturée à l'usage). Quota journalier de questions : `droits.questions.quota-journalier` (200 par
défaut).

Ou un modèle local avec [Ollama](https://ollama.com) (gratuit, la question et le profil ne quittent
pas la machine ; plus lent et moins sûr, à comparer par l'évaluation) :

```bash
ollama pull qwen2.5:7b
./mvnw spring-boot:run -Dspring-boot.run.arguments="--droits.assistant.fournisseur=ollama"
# autre machine ou autre modèle : --spring.ai.ollama.base-url=http://hote:11434 --spring.ai.ollama.chat.model=mistral
```

Conventions de développement (langue, tests, documentation, pièges connus) : [CLAUDE.md](CLAUDE.md).

## Sources et licence

Code du travail et conventions collectives : bases LEGI et KALI de la DILA, via
[SocialGouv](https://github.com/SocialGouv/legi-data), sous Licence Ouverte. Le code de ce projet est
sous [licence Apache 2.0](LICENSE).
