#!/bin/bash
set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"

SERVICES=("reserve-service" "core-service" "payment-service" "gateway")

# 특정 서비스만 지정 가능: ./redeploy.sh reserve-service core-service
if [ $# -gt 0 ]; then
    SERVICES=("$@")
fi

echo "=== 빌드 ==="
cd "$PROJECT_ROOT"
./gradlew clean build -x test

echo "=== 이미지 빌드: ${SERVICES[*]} ==="
for svc in "${SERVICES[@]}"; do
    if [ "$svc" = "gateway" ]; then
        docker build --build-arg SERVICE_NAME=gateway --build-arg SERVICE_PORT=8080 -t "harness/$svc:latest" .
    else
        docker build --build-arg SERVICE_NAME="$svc" -t "harness/$svc:latest" .
    fi
done

echo "=== Pod 재시작 ==="
kubectl rollout restart deployment "${SERVICES[@]}"

echo "=== Ready 대기 ==="
for svc in "${SERVICES[@]}"; do
    kubectl wait --for=condition=ready pod -l "app=$svc" --timeout=120s
done

echo "=== Dangling 이미지 정리 ==="
docker image prune -f

echo ""
echo "=== 재배포 완료: ${SERVICES[*]} ==="
kubectl get pods -l "app in ($(IFS=,; echo "${SERVICES[*]}"))"
