# Droits des salariés

Application qui aide un salarié à connaître ses droits selon son profil (convention collective,
ancienneté, statut, grossesse…), à partir du Code du travail et des conventions collectives publiés
par SocialGouv (bases DILA LEGI et KALI). Projet personnel, Spring Boot 4 / Java 25 / PostgreSQL.

## Commandes

- `./mvnw verify` : tests unitaires (surefire) et d'intégration `*IT` (failsafe, Testcontainers : Docker requis). C'est ce que lance le CI.
- `./mvnw test -Dtest=NomTest` : un test unitaire ; `./mvnw verify -Dit.test=NomIT -Dtest=Aucun -Dsurefire.failIfNoSpecifiedTests=false` : un test d'intégration.
- Ingestion réelle (Postgres démarré via `compose.yaml`) :
  `./mvnw spring-boot:run -Dspring-boot.run.arguments="--droits.ingestion.run=true --spring.main.web-application-type=none"`
- Java 25 obligatoire (`JAVA_HOME`).

## Conventions

- **Code, commentaires, commits, PR et tests en français**, y compris le vocabulaire métier (préavis, convention, ETAM…) : c'est la langue du droit et de la juriste qui valide. Seul le vocabulaire imposé par les frameworks reste en anglais (`Repository`, `Controller`…).
- Noms de tests en phrases : `licenciement_6_mois_sans_indemnite`.
- Javadoc sur les classes et les règles non évidentes ; pas de commentaire qui paraphrase le code.
- Schéma uniquement par changesets Liquibase (`src/main/resources/db/changelog/changesets/`), jamais en modifiant un changeset existant ; Hibernate est en `ddl-auto: validate`.
- Chaque PR passe par le CI et la revue Claude ; pas de fusion sans relecture.

## Architecture

- `corpus` : entités (`Texte`, `Section`, `Article`, `JournalChangement`) et repositories. `Article.estApplicable` est **la** règle d'applicabilité (état + dates + disparition) ; `RechercheRepository.APPLICABLE` la reproduit en SQL : les modifier ensemble (test de parité dans `RechercheIT`).
- `ingestion` : job Spring Batch, un step par source, sources épinglées sur un commit dans `application.yaml`. La classification des changements se fait dans `CorpusWriter`, dans l'ordre du document, pour ne pas dépendre du découpage en chunks.
- `consultation` : API en lecture seule (textes, sommaire, articles, historique).
- `profil` : contexte d'un profil salarié. **Le profil n'est jamais stocké ni journalisé** (données sensibles RGPD : grossesse, santé).
- `recherche` : recherche plein texte PostgreSQL (configuration `francais`, sans accents).

## Pièges connus

- `@SpringBatchTest` appelle toute méthode de la classe de test qui renvoie un `JobExecution` ou un `StepExecution` : garder ces helpers hors des classes de test (`FixturesIngestion`).
- Jackson 3 refuse par défaut une valeur absente pour un type primitif (`FAIL_ON_NULL_FOR_PRIMITIVES`) : types objets dans les DTO d'entrée.
- Spring Batch 6 : sans `spring-boot-starter-batch-jdbc`, le dépôt de jobs est en mémoire.
- Données SocialGouv : états DILA à ajouter dans `EtatJuridique` (un état inconnu fait échouer l'ingestion, volontairement) ; sections de regroupement sans identifiant dans les conventions (id synthétique) ; texte des conventions en HTML seulement.
- Postgres : « les » n'est pas un mot vide de la configuration française.

## Tests

- Vérifier qu'un nouveau test échoue bien sans le correctif (mutation volontaire, puis retour arrière).
- `src/test/resources/evaluation/questions.yaml` : jeu de questions de salariés avec réponses de référence validées par une juriste (champ `valide`).
