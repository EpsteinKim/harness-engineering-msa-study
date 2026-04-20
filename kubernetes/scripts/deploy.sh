#!/bin/bash
set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
K8S_DIR="$SCRIPT_DIR/.."

echo "=== minikube Docker 환경 설정 ==="
eval $(minikube docker-env)

echo "=== 이미지 빌드 ==="
cd "$PROJECT_ROOT"
docker build --build-arg SERVICE_NAME=gateway --build-arg SERVICE_PORT=8080 -t harness/gateway:latest .
docker build --build-arg SERVICE_NAME=core-service -t harness/core-service:latest .
docker build --build-arg SERVICE_NAME=reserve-service -t harness/reserve-service:latest .
docker build --build-arg SERVICE_NAME=payment-service -t harness/payment-service:latest .

echo "=== ConfigMap + Secret 배포 ==="
kubectl apply -f "$K8S_DIR/config/"

echo "=== 인프라 배포 (Redis + Kafka) ==="
kubectl apply -f "$K8S_DIR/infrastructure/"

echo "=== 인프라 Ready 대기 ==="
kubectl wait --for=condition=ready pod -l app=redis --timeout=120s
kubectl wait --for=condition=ready pod -l app=kafka --timeout=180s

echo "=== 앱 서비스 배포 ==="
kubectl apply -f "$K8S_DIR/apps/"

echo "=== 앱 서비스 Ready 대기 ==="
kubectl wait --for=condition=ready pod -l app=core-service --timeout=120s
kubectl wait --for=condition=ready pod -l app=reserve-service --timeout=120s
kubectl wait --for=condition=ready pod -l app=payment-service --timeout=120s
kubectl wait --for=condition=ready pod -l app=gateway --timeout=120s

echo "=== Ingress 배포 ==="
kubectl apply -f "$K8S_DIR/ingress/"

echo ""
echo "=== 배포 완료 ==="
kubectl get pods
echo ""
kubectl get svc
echo ""
kubectl get hpa
echo ""
echo "Ingress 사용: /etc/hosts에 '$(minikube ip)  harness.local' 추가"
