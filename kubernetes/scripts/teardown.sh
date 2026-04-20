#!/bin/bash
set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
K8S_DIR="$SCRIPT_DIR/.."

echo "=== Ingress 제거 ==="
kubectl delete -f "$K8S_DIR/ingress/" --ignore-not-found

echo "=== 앱 서비스 제거 ==="
kubectl delete -f "$K8S_DIR/apps/" --ignore-not-found

echo "=== 인프라 제거 ==="
kubectl delete -f "$K8S_DIR/infrastructure/" --ignore-not-found

echo "=== ConfigMap + Secret 제거 ==="
kubectl delete -f "$K8S_DIR/config/" --ignore-not-found

echo ""
echo "=== 정리 완료 ==="
kubectl get pods
