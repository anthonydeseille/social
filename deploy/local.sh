#!/usr/bin/env bash
# Déploiement local dans un cluster k3d (voir docs/deploiement-local.md).
#   deploy/local.sh cree     cluster, image, Secrets et déploiement
#   deploy/local.sh image    reconstruit et réimporte l'image, redémarre l'appli
#   deploy/local.sh ingere   lance l'ingestion des sources et suit ses journaux
#   deploy/local.sh etat     ressources du namespace
#   deploy/local.sh detruit  supprime le cluster et ses données
set -euo pipefail

CLUSTER=droits
NAMESPACE=droits
IMAGE=social:local
POSTGRES=postgres:18.6
RACINE="$(cd "$(dirname "$0")/.." && pwd)"
export PATH="/opt/local/bin:$PATH"

prerequis() {
    for outil in docker k3d kubectl; do
        command -v "$outil" > /dev/null || { echo "Outil manquant : $outil" >&2; exit 1; }
    done
}

image() {
    (cd "$RACINE" && ./mvnw -B -q -DskipTests spring-boot:build-image -Dspring-boot.build-image.imageName="$IMAGE")
    k3d image import --cluster "$CLUSTER" "$IMAGE"
}

# Mot de passe Postgres tiré au hasard à la création, conservé ensuite dans le cluster ;
# clé Anthropic recopiée depuis l'environnement seulement si elle est définie.
secrets() {
    if ! kubectl -n "$NAMESPACE" get secret postgres > /dev/null 2>&1; then
        kubectl -n "$NAMESPACE" create secret generic postgres \
            --from-literal=mot-de-passe="$(openssl rand -hex 24)"
    fi
    if [ -n "${ANTHROPIC_API_KEY:-}" ]; then
        kubectl -n "$NAMESPACE" create secret generic anthropic --from-literal=cle="$ANTHROPIC_API_KEY" \
            --dry-run=client -o yaml | kubectl apply -f - > /dev/null
        echo "Secret anthropic à jour (fournisseur claude possible)"
    fi
}

cree() {
    if ! k3d cluster list "$CLUSTER" > /dev/null 2>&1; then
        k3d cluster create --config "$RACINE/deploy/k3d/cluster.yaml"
    fi
    kubectl config use-context "k3d-$CLUSTER" > /dev/null
    image
    docker image inspect "$POSTGRES" > /dev/null 2>&1 || docker pull -q "$POSTGRES"
    k3d image import --cluster "$CLUSTER" "$POSTGRES"
    kubectl apply -f "$RACINE/deploy/k8s/namespace.yaml" > /dev/null
    secrets
    kubectl apply -k "$RACINE/deploy/k8s"
    kubectl -n "$NAMESPACE" rollout status statefulset/postgres --timeout=180s
    kubectl -n "$NAMESPACE" rollout status deployment/social --timeout=300s
    echo "Application : http://localhost:8080/ (base vide : lancer « $0 ingere »)"
}

ingere() {
    kubectl -n "$NAMESPACE" delete job ingestion --ignore-not-found > /dev/null
    kubectl apply -f "$RACINE/deploy/k8s/ingestion.yaml"
    # Journaux disponibles une fois le conteneur démarré (après l'attente de Postgres)
    until kubectl -n "$NAMESPACE" logs -f job/ingestion 2> /dev/null; do sleep 2; done
    kubectl -n "$NAMESPACE" wait --for=condition=Complete job/ingestion --timeout=60s
}

prerequis
case "${1:-}" in
    cree) cree ;;
    image)
        kubectl config use-context "k3d-$CLUSTER" > /dev/null
        image
        kubectl -n "$NAMESPACE" rollout restart deployment/social
        kubectl -n "$NAMESPACE" rollout status deployment/social --timeout=300s
        ;;
    ingere) kubectl config use-context "k3d-$CLUSTER" > /dev/null; ingere ;;
    etat) kubectl --context "k3d-$CLUSTER" -n "$NAMESPACE" get all,pvc,secrets ;;
    detruit) k3d cluster delete "$CLUSTER" ;;
    *) sed -n '2,8p' "$0" | sed 's/^# \{0,1\}//'; exit 1 ;;
esac
