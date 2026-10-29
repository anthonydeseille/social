# Droits des salariés

Application qui aide un salarié à connaître ses droits selon son profil (convention collective,
ancienneté, statut, contrat, grossesse…), à partir du Code du travail et des conventions collectives
publiés par SocialGouv. Conventions intégrées : Syntec (1486), métallurgie (3248), commerce
alimentaire (2216), transports routiers (0016), hôtels-cafés-restaurants (1979), services de
l'automobile (1090), propreté (3043). Les réponses sont rédigées par Claude **à partir des seuls articles
applicables**, chaque affirmation citant le texte exact qui la fonde.

> Information générale, pas un conseil juridique.

- [Le trajet d'une question](docs/fonctionnement.md) : schéma et explications
- [Feuille de route](FEUILLE_DE_ROUTE.md)
- [Conventions de développement](CLAUDE.md)

## Démarrer

Prérequis : Java 25, Docker.

```bash
./mvnw verify                      # tests unitaires et d'intégration
./mvnw spring-boot:run -Dspring-boot.run.arguments="--droits.ingestion.run=true --spring.main.web-application-type=none"
                                   # ingestion des sources (Code du travail, 7 conventions)
./mvnw spring-boot:run             # API sur http://localhost:8080/api
```

Les réponses aux questions (`POST /api/questions`) appellent Claude : clé dans `ANTHROPIC_API_KEY`
(facturée à l'usage).
