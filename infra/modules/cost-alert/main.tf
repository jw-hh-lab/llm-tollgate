# AWS 비용 알림: 예산(Budgets)이 기준을 넘으면 → SNS 주제 → Lambda → Discord "비용" 채널
# 왜 Lambda가 끼나: Budgets는 이메일/SNS로만 알림을 보낼 수 있고, Discord는 정해진 JSON 모양만 받기 때문에
#                  중간에서 모양을 바꿔 주는 작은 함수가 필요하다
# 비용: Budgets(알림만 쓰는 예산) 무료, SNS·Lambda는 프리 티어 안에서 0원

data "aws_caller_identity" "current" {}

# 1) 월 예산. include_credit=false가 핵심: 크레딧을 빼고 보면 항상 $0이라 알림이 절대 안 온다
resource "aws_budgets_budget" "monthly" {
  name         = "${var.name}-monthly"
  budget_type  = "COST"
  limit_amount = var.monthly_limit_usd
  limit_unit   = "USD"
  time_unit    = "MONTHLY"

  cost_types {
    include_credit = false
    include_refund = false
  }

  # 실제 쓴 돈이 50%, 80%, 100%를 넘을 때
  dynamic "notification" {
    for_each = [50, 80, 100]
    content {
      comparison_operator       = "GREATER_THAN"
      threshold                 = notification.value
      threshold_type            = "PERCENTAGE"
      notification_type         = "ACTUAL"
      subscriber_sns_topic_arns = [aws_sns_topic.cost.arn]
    }
  }

  # 이 속도면 이번 달 예산을 넘을 것 같을 때 (미리 경고)
  notification {
    comparison_operator       = "GREATER_THAN"
    threshold                 = 100
    threshold_type            = "PERCENTAGE"
    notification_type         = "FORECASTED"
    subscriber_sns_topic_arns = [aws_sns_topic.cost.arn]
  }
}

# 2) SNS 주제: Budgets가 메시지를 던지는 우체통
resource "aws_sns_topic" "cost" {
  name = "${var.name}-cost-alert"
}

# Budgets 서비스가 이 우체통에 넣을 수 있게 허용 (우리 계정의 예산만)
resource "aws_sns_topic_policy" "cost" {
  arn = aws_sns_topic.cost.arn
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect    = "Allow"
      Principal = { Service = "budgets.amazonaws.com" }
      Action    = "SNS:Publish"
      Resource  = aws_sns_topic.cost.arn
      Condition = {
        StringEquals = { "aws:SourceAccount" = data.aws_caller_identity.current.account_id }
      }
    }]
  })
}

# 3) Lambda: SNS 메시지를 Discord 모양으로 바꿔 보냄. 웹후크 주소는 코드에 안 넣고 SSM에서 꺼내 씀
data "archive_file" "notifier" {
  type        = "zip"
  source_file = "${path.module}/lambda/notifier.py"
  output_path = "${path.module}/lambda/notifier.zip"
}

resource "aws_iam_role" "notifier" {
  name = "${var.name}-cost-notifier"
  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect    = "Allow"
      Principal = { Service = "lambda.amazonaws.com" }
      Action    = "sts:AssumeRole"
    }]
  })
}

# 최소 권한: 로그 쓰기 + 웹후크 SSM 값 하나 읽기
resource "aws_iam_role_policy" "notifier" {
  role = aws_iam_role.notifier.id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Effect   = "Allow"
        Action   = ["logs:CreateLogStream", "logs:PutLogEvents"]
        Resource = "${aws_cloudwatch_log_group.notifier.arn}:*"
      },
      {
        Effect   = "Allow"
        Action   = "ssm:GetParameter"
        Resource = "arn:aws:ssm:*:${data.aws_caller_identity.current.account_id}:parameter${var.webhook_ssm_name}"
      }
    ]
  })
}

# 로그는 7일만 보관 (오래 두면 저장 요금)
resource "aws_cloudwatch_log_group" "notifier" {
  name              = "/aws/lambda/${var.name}-cost-notifier"
  retention_in_days = 7
}

resource "aws_lambda_function" "notifier" {
  function_name    = "${var.name}-cost-notifier"
  role             = aws_iam_role.notifier.arn
  runtime          = "python3.13"
  handler          = "notifier.handler"
  filename         = data.archive_file.notifier.output_path
  source_code_hash = data.archive_file.notifier.output_base64sha256
  timeout          = 10
  memory_size      = 128
  environment {
    variables = { WEBHOOK_SSM_NAME = var.webhook_ssm_name }
  }
  depends_on = [aws_cloudwatch_log_group.notifier]
}

# SNS → Lambda 연결 + SNS가 Lambda를 부를 수 있게 허용
resource "aws_sns_topic_subscription" "notifier" {
  topic_arn = aws_sns_topic.cost.arn
  protocol  = "lambda"
  endpoint  = aws_lambda_function.notifier.arn
}

resource "aws_lambda_permission" "sns" {
  statement_id  = "AllowSNSInvoke"
  action        = "lambda:InvokeFunction"
  function_name = aws_lambda_function.notifier.function_name
  principal     = "sns.amazonaws.com"
  source_arn    = aws_sns_topic.cost.arn
}
