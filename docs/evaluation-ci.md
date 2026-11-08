# Évaluation complète dans la CI

Le workflow [`evaluation.yml`](../.github/workflows/evaluation.yml) pose le jeu d'évaluation à Claude
sur les vraies sources (`EvaluationIT`, API Batch, toutes les questions), puis compare le résultat à la
dernière ligne de [`evaluations/historique.csv`](evaluations/historique.csv) faite avec le même modèle.

## Quand il se lance

| Déclencheur | Pourquoi |
|---|---|
| Étiquette `eval` posée sur une PR | à la demande. L'étiquette est retirée en fin de lancement : la reposer relance (par exemple après de nouveaux commits) |
| Ouverture, réouverture ou sortie de brouillon d'une PR qui touche `src/*/resources/prompts/` (consignes), `recherche/`, `questions/` (trajet d'une question, paramètres des appels) ou `application.yaml` (modèle, sources épinglées) | ce qui change les réponses. Les commits suivants ne relancent pas d'office (coût) : après une retouche, reposer l'étiquette |
| Chaque lundi à 5 h (UTC), sur `main` | dérive du modèle ou du corpus sans changement de code |
| À la demande (`workflow_dispatch`) | champs facultatifs : `etape` (`reformulation` : reformulation et recherche seules, quelques centimes), `modele`, `modele_reformulation`, `reference` (modèle actuel, auquel se compare un modèle sans historique) ; voir [`couts-et-modeles.md`](couts-et-modeles.md) |

Les PR venant d'un dépôt dérivé ne lancent rien. Coût : environ 1,10 à 1,40 $ par lancement (API
Batch, moitié prix), soit 5 à 6 $ par mois pour le seul passage hebdomadaire.

## Ce qu'il produit

- Un **commentaire dans la PR** (et le résumé du job) : écart par indicateur à la référence, versions
  des consignes, coût, décision.
- L'**échec du job sur une baisse nette** (seuils dans `ComparaisonEvaluation`) : plus de 5 points de
  recherche, d'articles cités ou de qualité, plus d'une erreur à éviter de plus, plus de 2 points de
  citations vérifiées. Les écarts plus petits sont le bruit d'un passage à l'autre. Tant que `main`
  n'est pas protégée (voir la feuille de route), le blocage est un signal : la fusion reste possible,
  en l'expliquant dans la PR.
- Un **artefact** (90 jours) : rapport, résumé JSON, ligne d'historique, requêtes de reformulation.

La référence est la dernière ligne de l'historique avec le même modèle **et le même nombre de
questions** : un sous-ensemble ou un jeu agrandi ne se compare pas (pas de blocage, c'est dit dans le
commentaire). Le mode (lots ou direct) n'entre pas en compte.

Un lancement de la CI n'est pas ajouté d'office à l'historique : on l'y verse, avec son analyse, comme
une évaluation locale ([`evaluations/README.md`](evaluations/README.md)), quand il marque une étape.

## Authentification : fédération d'identité, sans clé stockée

Le job ne contient aucune clé d'API. GitHub lui délivre un jeton d'identité (OIDC) signé, qui dit quel
dépôt et quel workflow tournent ; le SDK Anthropic l'échange contre un jeton Anthropic de courte durée
(10 minutes), lié à un compte de service. Le jeton GitHub ne vit que 5 minutes et ne s'échange qu'une
fois : le job le réécrit chaque minute, et le SDK relit le fichier à chaque renouvellement.

### Mise en place (une fois)

1. **Console Anthropic** → [Workload identity](https://platform.claude.com/settings/workload-identity-federation)
   → *Connect workload* → tuile *GitHub Actions*. Valeurs :
   - émetteur : `https://token.actions.githubusercontent.com`, JWKS en mode `discovery` ;
   - compte de service : `evaluation-ci` (rôle `developer`), membre de l'espace de travail facturé ;
   - règle `evaluation-ci` :
     - `subject_prefix` : `repo:anthonydeseille/social:*` (PR : `…:pull_request`, `main` : `…:ref:refs/heads/main`) ;
     - `audience` : `https://api.anthropic.com` ;
     - `claims` : `repository_owner` = `anthonydeseille`, `repository` = `anthonydeseille/social` ;
     - `condition` (CEL), pour que seul ce workflow obtienne un jeton (les workflows de revue Claude
       demandent aussi des jetons OIDC) :
       `claims.job_workflow_ref.startsWith("anthonydeseille/social/.github/workflows/evaluation.yml@")` ;
     - portée `workspace:developer` (l'API Batch en fait partie), durée `600` secondes.
2. **GitHub** → Settings → Secrets and variables → Actions → *Variables* (pas des secrets : ces
   identifiants ne donnent aucun accès sans le jeton signé par GitHub) :
   `ANTHROPIC_FEDERATION_RULE_ID` (`fdrl_…`), `ANTHROPIC_ORGANIZATION_ID` (UUID, page Organization de
   la Console), `ANTHROPIC_SERVICE_ACCOUNT_ID` (`svac_…`).
3. **Premier lancement** : Actions → *Évaluation complète* → *Run workflow*. En cas de refus (401
   `Authentication failed`, volontairement opaque), la raison est dans l'onglet *History* de la page
   Workload identity de la Console (`match_subject_prefix`, `jti_reused`…).

Ne pas définir de secret `ANTHROPIC_API_KEY` sur ce workflow : une clé, même vide, passe avant la
fédération.

### Limites de sécurité

Sur une PR, c'est le workflow **de la branche** qui s'exécute : quiconque peut pousser une branche dans
le dépôt peut modifier `evaluation.yml` ou les tests et obtenir un jeton Anthropic de 10 minutes. La
condition CEL protège des autres workflows (y compris sur `refs/pull/N/merge`, qu'elle accepte), pas
d'une branche modifiée ; les dépôts dérivés, eux, n'obtiennent aucun jeton. Le dépôt étant personnel,
c'est acceptable ; avec des contributeurs, réserver la règle à `main` et déclencher les PR par
`workflow_dispatch`. Dans tous les cas, plafonner la dépense de l'espace de travail du compte de
service dans la Console (limite de dépense mensuelle).
