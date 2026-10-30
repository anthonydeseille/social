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
./mvnw spring-boot:run             # page web sur http://localhost:8080/, API sur /api
```

Les réponses aux questions (`POST /api/questions`) appellent Claude : clé dans `ANTHROPIC_API_KEY`
(facturée à l'usage).

Ou un modèle local avec [Ollama](https://ollama.com) (gratuit, la question et le profil ne quittent
pas la machine ; plus lent et moins sûr, à comparer par l'évaluation) :

```bash
ollama pull qwen2.5:7b
./mvnw spring-boot:run -Dspring-boot.run.arguments="--droits.assistant.fournisseur=ollama"
# autre machine ou autre modèle : --spring.ai.ollama.base-url=http://hote:11434 --spring.ai.ollama.chat.model=mistral
```

Quota journalier de questions : `droits.questions.quota-journalier` (200 par défaut). L'ancienne clé
`droits.claude.quota-journalier` n'est plus lue.
