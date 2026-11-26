# SNS로 받은 예산 알림을 Discord 웹후크로 보낸다. 외부 라이브러리 없이 표준 라이브러리만 사용
import json
import os
import urllib.request

import boto3

_ssm = boto3.client("ssm")
_webhook = None


def _webhook_url():
    # 매번 SSM을 부르지 않게, 함수가 살아 있는 동안은 한 번 읽은 값을 재사용
    global _webhook
    if _webhook is None:
        _webhook = _ssm.get_parameter(
            Name=os.environ["WEBHOOK_SSM_NAME"], WithDecryption=True
        )["Parameter"]["Value"]
    return _webhook


def handler(event, context):
    for record in event.get("Records", []):
        sns = record["Sns"]
        subject = sns.get("Subject") or "AWS 비용 알림"
        body = sns.get("Message", "")[:1800]  # Discord 메시지 한도 2000자
        content = f"💸 **{subject}**\n```\n{body}\n```"
        req = urllib.request.Request(
            _webhook_url(),
            data=json.dumps({"content": content}).encode(),
            headers={"Content-Type": "application/json", "User-Agent": "tollgate-cost-notifier"},
        )
        urllib.request.urlopen(req, timeout=5)
    return {"ok": True}
