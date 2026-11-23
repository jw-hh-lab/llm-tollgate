# k8s

| 폴더 | 내용 | 반영 방법 |
| --- | --- | --- |
| `data/` | MySQL 8.4(StatefulSet), Redis 7.4 | 손으로 `kubectl apply -f k8s/data/` |
| `app/` | 게이트웨이, 가짜 LLM, Ingress | 처음과 YAML을 바꿨을 때만 손으로 apply. 앱 코드 배포는 Actions가 자동 |

## 이미지 태그

- Actions(`deploy` 워크플로)가 main merge 때마다 `:커밋ID`와 `:main` 두 태그로 GHCR에 올린다.
- 서버 배포는 `kubectl set image`로 **커밋 ID 태그**를 쓴다. 지금 서버에 어떤 코드가 떠 있는지 바로 알 수 있다.
- YAML에는 `:main` + `imagePullPolicy: Always`를 적어 둔다. YAML을 다시 apply해도 옛 이미지로 돌아가지 않고 최신 main이 뜬다.

## 처음 배포 (클러스터를 새로 만들었을 때 한 번)

kubectl 연결(SSM 포트 포워딩) 후 저장소 최상위에서:

```bash
# 1. 게이트웨이 비밀값: SSM 값을 바로 Secret으로 (값이 화면·파일에 남지 않음)
kubectl create secret generic gateway-secret -n tollgate \
  --from-literal=gemini-api-key="$(aws ssm get-parameter --name /llm-tollgate/dev/gemini-api-key --with-decryption --query Parameter.Value --output text)" \
  --from-literal=admin-token="$(aws ssm get-parameter --name /llm-tollgate/dev/admin-token --with-decryption --query Parameter.Value --output text)"
# DB 비밀번호는 k8s/data 때 만든 mysql-secret(app-password)을 같이 씀

# 2. 앱 올리기
kubectl apply -f k8s/app/
kubectl get pods -n tollgate -w   # gateway, fake-llm 둘 다 1/1 Running
```

- 비밀값을 바꿀 때: `kubectl delete secret gateway-secret -n tollgate` 후 1번 다시, 그리고 `kubectl rollout restart deployment/gateway -n tollgate`
- 담당: 종완(인프라). 새 환경 변수가 필요하면 형호가 이름을 알려 주고 종완이 YAML·Secret에 반영

## YAML을 바꿨을 때

`k8s/app/*.yaml` 변경은 Actions가 반영하지 않는다(`paths: app/**`만 감시). merge 후 손으로:

```bash
kubectl apply -f k8s/app/
```

## 되돌리기 (배포 후 문제가 생겼을 때)

rollout이 실패해도 자동으로 되돌리지 않는다. 바로 전 버전으로:

```bash
kubectl rollout undo deployment/gateway -n tollgate
kubectl rollout status deployment/gateway -n tollgate
```

특정 커밋으로 돌아가려면: `kubectl set image deployment/gateway gateway=ghcr.io/jw-hh-lab/llm-tollgate-gateway:<커밋ID> -n tollgate`

## 타임아웃 (스트리밍)

LLM이 멈췄을 때 앱이 가장 먼저 판단(환불·에러 이벤트)하도록 앱 값을 가장 짧게 잡았다 (11/9 회의).

| 위치 | 값 | 의미 |
| --- | --- | --- |
| 앱 | 첫 토큰 10초, 조각 사이 15초 | LLM이 멈추면 앱이 먼저 끊고 `event: error` + 환불 |
| CloudFront | 원본 응답 대기 30초 | 조각과 조각 사이 최대 대기. 앱 15초보다 길어서 먼저 안 끊김 |
| Nginx Ingress | `proxy-read-timeout` 60초 | CloudFront(30초)보다 길게 둬서 Nginx가 먼저 끊는 일이 없게. 기본값(60초)과 같지만 스트리밍 의도를 드러내려고 명시 |
