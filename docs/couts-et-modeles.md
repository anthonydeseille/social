# Coût et choix des modèles

Chaque question coûte deux appels à Claude : la reformulation (quelques centaines de jetons) et la
rédaction (8 articles, réflexion élevée : l'essentiel du coût, 4 à 7 centimes avec Opus 5.5). Les
leviers se choisissent sur mesure, jamais d'après une intuition : chaque changement de modèle passe par
une évaluation.

## Ce qui est en place

| Outil | Rôle |
|---|---|
| `AssistantClaude.PRIX` | prix de chaque modèle (dollars par million de jetons), seule table à tenir à jour : celle de [llmeval4j](https://github.com/anthonydeseille/llmeval4j) (`PriceTable`), à compléter là par `with` pour un modèle absent ; un modèle absent a un coût inconnu, jamais faux |
| `droits.assistant.cout_usd` (fournisseur, etape) | coût de chaque appel, au prix du modèle de l'étape |
| `droits.assistant.depense_jour_usd` | dépense du jour, pour alerter dans un tableau de bord |
| Budget journalier (`droits.questions.budget-journalier-usd`, 10 $ par défaut) | au-delà, questions refusées (429) jusqu'au lendemain ; alerte dans le journal à 80 % et à 100 %. Complète le quota (200 questions) : un modèle plus cher ou des rédactions longues épuisent le budget avant le quota. Approximatif : la dépense est constatée après chaque appel, des questions simultanées peuvent le dépasser de quelques dixièmes de dollar. Décompte par `DailyBudget` (llmeval4j). Un modèle sans prix y échappe (signalé dans le journal) |
| `droits.claude.modele-reformulation` | modèle de la reformulation, s'il diffère de celui de la rédaction (`droits.claude.modele`) |
| Évaluation de la reformulation seule | `-Devaluation.etape=reformulation` : reformulation et recherche, ni rédaction ni juge ; quelques centimes |
| Évaluation complète d'un autre modèle | `workflow_dispatch` du workflow « Évaluation complète » (champs `modele`, `modele_reformulation`), comparée au modèle actuel tant que le nouveau n'a pas d'historique |

Ces garde-fous applicatifs complètent la **limite de dépense mensuelle de la Console Anthropic**, à
fixer sur l'espace de travail de l'application et sur celui du compte de service de la CI.

## Choisir le modèle d'une étape

1. **Reformulation** : son seul effet mesurable est la recherche. On compare les modèles sur
   l'indicateur « recherche » de la reformulation seule (20 appels courts) :

   ```
   ./mvnw verify -Dit.test=EvaluationIT -Dtest=Aucun -Dsurefire.failIfNoSpecifiedTests=false \
     -Devaluation=true -Devaluation.toutes=true -Devaluation.etape=reformulation \
     -Ddroits.claude.modele-reformulation=claude-haiku-5-5
   ```

   (ou Actions → *Évaluation complète* → *Run workflow*, étape `reformulation`). Rapport :
   `target/evaluation/reformulation-*.md`. Ce lancement ne compare rien et ne bloque rien : un job
   vert ne prouve pas que la recherche tient, il faut lire le score. Les reformulations varient d'un passage à l'autre : lancer
   deux fois chaque modèle avant de conclure. Un modèle plus léger est retenu si la recherche ne
   baisse pas au-delà de ce bruit.
2. **Confirmation** par une évaluation complète avec le couple retenu (rédaction inchangée), comparée
   à l'historique : la reformulation influe aussi sur la rédaction, par les articles transmis.
3. **Rédaction** : un modèle plus léger se juge sur la qualité (juge), les erreurs à éviter et les
   citations, par une évaluation complète du nouveau modèle (`modele`), comparée au modèle actuel.
4. Le choix retenu est inscrit dans `application.yaml`, et l'évaluation dans l'historique
   ([`evaluations/`](evaluations/README.md)).

Chaque nouveau modèle publié par Anthropic passe par les mêmes étapes avant d'être adopté.

Ordre de grandeur des prix (octobre 2026, `PriceTable` de llmeval4j) : Opus 5.5 4 $ / 20 $ par million de jetons
(entrée / sortie), Sonnet 5.5 2 $ / 10 $, Haiku 5.5 0,10 $ / 0,50 $. La reformulation représente
moins d'un centime par question avec Opus : la passer à Haiku économise peu en valeur absolue ; la
rédaction est le vrai levier, et le plus risqué pour la qualité.

## Cache de prompt : pas aujourd'hui

Le cache de prompt fait payer 0,05 à 0,1 fois le prix normal les jetons d'un préfixe déjà envoyé, mais
il ne s'applique qu'à partir d'un préfixe de **512 jetons** (Opus 5.5, Sonnet 5.5, Haiku 5.5) et coûte
1,25 fois le prix à l'écriture. Or :

- les consignes, seule partie commune à toutes les questions, font environ 170 jetons
  (reformulation, 622 caractères) et 280 jetons (rédaction, 1 092 caractères) : sous le seuil, rien
  ne serait mis en cache ;
- la suite de la requête (articles, profil, question) change à chaque question : un cache posé
  après elle serait écrit à 1,25 fois le prix et presque jamais relu ;
- le trafic est faible : deux questions partagent rarement un préfixe à moins de 5 minutes d'écart.

À reconsidérer si les consignes dépassent 512 jetons (exemples, règles par convention) et si le trafic
devient continu : le champ `usage.cache_read_input_tokens` des réponses dira alors si le cache est relu.
