# infra

Terraform으로 만드는 AWS 자원. 실행 위치는 `infra/envs/dev`, state는 S3(`use_lockfile`).

| 모듈 | 하는 일 |
| --- | --- |
| network | VPC, 퍼블릭 서브넷, 보안 그룹(CloudFront에서 오는 80번만) |
| compute | EC2 m7i-flex.large 1대(k3s), SSM 접속 |
| scheduler | 매일 밤 12시 EC2 자동 정지 (EventBridge Scheduler) |
| cdn | CloudFront (캐시 끔, 오리진 타임아웃 30초) |
| github-actions | Actions용 OIDC 역할 (키 없이 배포·서버 켜고 끄기) |
| cost-alert | 월 예산 $24 → SNS → Lambda → Discord `비용` 채널 |

## cost-alert (AWS 비용 알림)

- 예산: 매달 $24, **크레딧 차감 전 금액** 기준(`include_credit = false`). 크레딧을 빼면 늘 $0이라 알림이 안 오기 때문
- 알림: 실제 50% / 80% / 100% 초과, 예측 100% 초과
- Discord 웹후크 주소는 코드에 없음 → SSM `/llm-tollgate/dev/discord-webhook-cost`(SecureString)에서 Lambda가 읽음
- 비용: 0원 (알림만 쓰는 예산 무료, SNS·Lambda 프리 티어)
- AWS 비용을 Grafana에 직접 그리지 않는 이유: Cost Explorer API가 호출당 $0.01. 대신 Grafana `Tollgate / Cost`에서 EC2 요금을 "서버 켜진 시간 × 단가"로 추정

처음 한 번: Discord `비용` 채널 웹후크를 SSM에 넣기
```bash
read -rs HOOK            # 붙여넣고 Enter (화면에 안 보임)
echo ${HOOK:0:33}        # https://discord.com/api/webhooks/ 가 나와야 함
aws ssm put-parameter --name /llm-tollgate/dev/discord-webhook-cost --type SecureString --value "$HOOK" && unset HOOK
```

알림 경로 테스트 (Budgets와 같은 SNS → Lambda → Discord 길)
```bash
ARN=$(aws sns list-topics --query "Topics[?contains(TopicArn,'cost-alert')].TopicArn" --output text)
aws sns publish --topic-arn "$ARN" --subject "테스트: 예산 50% 초과" --message "테스트 메시지"
# Discord에 안 오면
aws logs tail /aws/lambda/llm-tollgate-cost-notifier --since 5m
```

## 로컬에서 서버 접속 (단축 명령)

`~/.bashrc`에 한 번 넣어 두면 터미널마다 한 단어로 접속. 이미 열린 터미널은 `source ~/.bashrc` 필요
(안 하면 kubectl이 로컬 k3d를 봄 → `kubectl get nodes`로 `ip-10-…` 노드인지 먼저 확인)

```bash
export KUBECONFIG=~/.kube/tollgate.yaml
TG_ID=<EC2 인스턴스 ID>
# k3s API(6443) → 내 PC 16443. SSM은 20분 놀면 끊겨서 자동 재접속
tg-tunnel() {
  while true; do
    aws ssm start-session --target $TG_ID \
      --document-name AWS-StartPortForwardingSession \
      --parameters '{"portNumber":["6443"],"localPortNumber":["16443"]}'
    echo "터널 끊김 → 3초 뒤 재접속"; sleep 3
  done
}
tg-grafana() { while true; do kubectl port-forward -n monitoring svc/mon-grafana 13000:80; sleep 2; done; }
tg-prom()    { while true; do kubectl port-forward -n monitoring svc/mon-prometheus 19090:9090; sleep 2; done; }
```

터미널 1 `tg-tunnel` → 터미널 2 `tg-grafana`(http://localhost:13000) → 터미널 3에서 kubectl·git 작업
