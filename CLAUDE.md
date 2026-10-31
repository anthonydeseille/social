# Droits des salariés

Application qui aide un salarié à connaître ses droits selon son profil (convention collective,
ancienneté, statut, grossesse…), à partir du Code du travail et des conventions collectives publiés
par SocialGouv (bases DILA LEGI et KALI). Projet personnel, Spring Boot 4 / Java 25 / PostgreSQL.

## Commandes

- `./mvnw verify` : tests unitaires (surefire) et d'intégration `*IT` (failsafe, Testcontainers : Docker requis). C'est ce que lance le CI.
- `node src/test/js/markdown.test.js` : tests de l'affichage Markdown de la page web (aussi lancés par le CI).
- `./mvnw test -Dtest=NomTest` : un test unitaire ; `./mvnw verify -Dit.test=NomIT -Dtest=Aucun -Dsurefire.failIfNoSpecifiedTests=false` : un test d'intégration.
- Ingestion réelle (Postgres démarré via `compose.yaml`) :
  `./mvnw spring-boot:run -Dspring-boot.run.arguments="--droits.ingestion.run=true --spring.main.web-application-type=none"`
- Java 25 obligatoire (`JAVA_HOME`).
- Cluster local k3d : `deploy/local.sh cree|ingere|image|etat|detruit` (voir `docs/deploiement-local.md`).

## Conventions

- **Code, commentaires, commits, PR et tests en français**, y compris le vocabulaire métier (préavis, convention, ETAM…) : c'est la langue du droit et de la juriste qui valide. Seul le vocabulaire imposé par les frameworks reste en anglais (`Repository`, `Controller`…).
- Noms de tests en phrases : `licenciement_6_mois_sans_indemnite`.
- Javadoc sur les classes et les règles non évidentes ; pas de commentaire qui paraphrase le code.
- Schéma uniquement par changesets Liquibase (`src/main/resources/db/changelog/changesets/`), jamais en modifiant un changeset existant ; Hibernate est en `ddl-auto: validate`.
- Chaque PR passe par le CI et la revue Claude ; pas de fusion sans relecture.
- **Documentation tenue à jour au fil du développement** : toute PR qui change le trajet d'une question (étapes, garde-fous, coûts, erreurs, réponse) met à jour `docs/fonctionnement.md` dans la même PR ; idem pour `FEUILLE_DE_ROUTE.md` quand une étape avance. La revue Claude le vérifie.

## Architecture

- `corpus` : entités (`Texte`, `Section`, `Article`, `JournalChangement`) et repositories. `Article.estApplicable` est **la** règle d'applicabilité (état + dates + disparition) ; `RechercheRepository.APPLICABLE` la reproduit en SQL : les modifier ensemble (test de parité dans `RechercheIT`).
- `ingestion` : job Spring Batch, un step par source, sources épinglées sur un commit dans `application.yaml`. La classification des changements se fait dans `CorpusWriter`, dans l'ordre du document, pour ne pas dépendre du découpage en chunks.
- `consultation` : API en lecture seule (textes, sommaire, articles, historique).
- `profil` : contexte d'un profil salarié. **Le profil n'est jamais stocké ni journalisé** (données sensibles RGPD : grossesse, santé).
- `recherche` : recherche plein texte PostgreSQL (configuration `francais`, sans accents) sur le numéro, les titres de section (`article.titres_section`, recalculés par `TitresSection` en fin de step d'ingestion) et le texte.

## Pièges connus

- `@SpringBatchTest` appelle toute méthode de la classe de test qui renvoie un `JobExecution` ou un `StepExecution` : garder ces helpers hors des classes de test (`FixturesIngestion`).
- Jackson 3 refuse par défaut une valeur absente pour un type primitif (`FAIL_ON_NULL_FOR_PRIMITIVES`) : types objets dans les DTO d'entrée.
- Spring Batch 6 : sans `spring-boot-starter-batch-jdbc`, le dépôt de jobs est en mémoire.
- Données SocialGouv : états DILA à ajouter dans `EtatJuridique` (un état inconnu fait échouer l'ingestion, volontairement) ; sections de regroupement sans identifiant dans les conventions (id synthétique) ; texte des conventions en HTML seulement.
- Postgres : « les » n'est pas un mot vide de la configuration française.
- Dates d'un texte LEGI (`texte.date_debut`, `date_fin`) : celles de sa version consolidée dans la source épinglée, pas sa validité. `Texte.estApplicableLe` ne s'y fie pas (sauf entrée en vigueur différée : date de début ; abrogation différée : date de fin) ; la validité se juge article par article.
- victools épinglé en 4.x dans le pom (exigé par le SDK Anthropic) : ne pas utiliser `BeanOutputConverter` ni les outils de Spring AI, construits sur la 5.x.
- Spring AI 2.0 ne fusionne pas les options : des `OllamaChatOptions` passées à l'appel remplacent la configuration (modèle « mistral » par défaut) ; partir de `getOptions().mutate()`.

## Tests

- Vérifier qu'un nouveau test échoue bien sans le correctif (mutation volontaire, puis retour arrière).
- `src/test/resources/evaluation/questions.yaml` : jeu de questions de salariés avec réponses de référence validées par une juriste (champ `valide`).
- `EvaluationIT` pose ces questions sur les vraies sources ; désactivé par défaut car il appelle Claude (payant, `ANTHROPIC_API_KEY`) :
  `./mvnw verify -Dit.test=EvaluationIT -Dtest=Aucun -Dsurefire.failIfNoSpecifiedTests=false -Devaluation=true`
  (`-Devaluation.simulee=true` : faux assistant, gratuit ; `-Devaluation.lots=true` : API Batch, moitié prix, asynchrone ; `-Devaluation.toutes=true` : y compris les questions non validées ; `-Ddroits.assistant.fournisseur=ollama` : modèle local, juge Claude). Rapport dans `target/evaluation/`, avec la note d'un juge automatique (`JugeClaude`).
- `RechercheEvaluationIT` : évaluation de la recherche seule, gratuite (requêtes figées `requetes.yaml`, aucun appel au modèle), lancée par le CI ; échoue si les articles attendus retrouvés baissent par rapport à `recherche-reference.json` :
  `./mvnw verify -Dit.test=RechercheEvaluationIT -Dtest=Aucun -Dsurefire.failIfNoSpecifiedTests=false -Devaluation.recherche=true` (`-Devaluation.recherche.reference=ecrire` pour réécrire la référence après une amélioration).
- Toute évaluation réelle est historisée dans `docs/evaluations/` (rapport brut daté, analyse, ligne dans `README.md` et `historique.csv`) : voir `docs/evaluations/README.md`.
- Feuille de route : `FEUILLE_DE_ROUTE.md`.
