# k8s/monitoring

Prometheus + Grafana + Alertmanager (kube-prometheus-stack). 게이트웨이 지표를 모으고, 대시보드로 보고, 장애면 Discord `알림-장애`로 알림.

| 파일 | 내용 | 반영 |
| --- | --- | --- |
| `values.yaml` | 차트 설정 (보관 7일/2GB, 30초 수집, 자원 한도, Alertmanager → Discord) | `helm upgrade` |
| `gateway-podmonitor.yaml` | 게이트웨이 Pod 8081 `/actuator/prometheus` 수집 | `kubectl apply` |
| `dashboard-gateway.yaml` | Grafana 대시보드 "Tollgate / Gateway" (코드로 관리) | `kubectl apply` |
| `alert-rules.yaml` | 우리 서비스 알림 규칙 5개 | `kubectl apply` |

## 처음 설치 (클러스터를 새로 만들었을 때 한 번)

kubectl 연결(SSM 포트 포워딩) 후 저장소 최상위에서:

```bash
# 1. 비밀값: SSM → Secret (값이 화면·파일에 남지 않음)
kubectl create namespace monitoring
kubectl create secret generic grafana-admin -n monitoring \
  --from-literal=admin-user=admin \
  --from-literal=admin-password="$(aws ssm get-parameter --name /llm-tollgate/dev/grafana-admin-password --with-decryption --query Parameter.Value --output text)"
kubectl create secret generic alertmanager-discord -n monitoring \
  --from-literal=webhook-url="$(aws ssm get-parameter --name /llm-tollgate/dev/discord-webhook-alert --with-decryption --query Parameter.Value --output text)"

# 2. 차트 설치 (버전 고정)
helm repo add prometheus-community https://prometheus-community.github.io/helm-charts
helm install mon prometheus-community/kube-prometheus-stack \
  -n monitoring -f k8s/monitoring/values.yaml --version 93.11.1

# 3. 우리 설정
kubectl apply -f k8s/monitoring/gateway-podmonitor.yaml \
              -f k8s/monitoring/dashboard-gateway.yaml \
              -f k8s/monitoring/alert-rules.yaml
```

설정을 바꿨을 때: `values.yaml` → 위 `helm install`을 `helm upgrade`로, 나머지는 `kubectl apply`.

## 화면 보기 (밖에 공개 안 함, port-forward로만)

```bash
kubectl port-forward -n monitoring svc/mon-grafana 13000:80        # http://localhost:13000 (admin / SSM 값)
kubectl port-forward -n monitoring svc/mon-prometheus 19090:9090   # http://localhost:19090/alerts
```

Grafana 비밀번호: `aws ssm get-parameter --name /llm-tollgate/dev/grafana-admin-password --with-decryption --query Parameter.Value --output text`

## 알림 규칙 (alert-rules.yaml)

| 알림 | 조건 | 등급 |
| --- | --- | --- |
| GatewayDown | 게이트웨이 지표를 3분째 못 긁음 | critical |
| GatewayHigh5xxRate | /v1 요청 중 5xx 5분째 5% 초과 | critical |
| LlmCallsFailing | 모델별 LLM 실패(outcome≠success) 분당 3회 초과, 5분 | warning |
| LlmFirstTokenSlow | 첫 글자 평균 5초 초과(앱 타임아웃 10초의 절반), 5분 | warning |
| RedisSilentlyMissing | 요청은 있는데 Redis 명령 0, 5분 (11/23 REDIS_HOST 사고) | warning |

- Watchdog(항상 켜진 생존 신호)·info 등급은 Discord로 안 보냄. 해결되면 ✅도 보냄, 안 고쳐지면 4시간마다 다시.
- 서버를 끄면 Prometheus도 꺼지므로 꺼진 동안 헛알림 없음.
- 확인 방법(장애 훈련, 2026-11-26): `kubectl scale deployment fake-llm -n tollgate --replicas=0` 후 호출 반복 → 게이트웨이 503(`all_models_unavailable`, 환불) → 약 6분 뒤 GatewayHigh5xxRate·LlmCallsFailing 🚨 → `--replicas=1`로 복구 → ✅

## 자원

서버 8GB 중 앱+DB+모니터링 합쳐 약 42% 사용(2026-11-26). Grafana는 256Mi에서 첫 시작 때 OOMKilled → 512Mi.
