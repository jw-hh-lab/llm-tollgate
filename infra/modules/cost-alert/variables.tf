variable "name" {
  type = string
}

variable "monthly_limit_usd" {
  description = "월 예산(USD). 크레딧 차감 전 금액 기준"
  type        = string
  default     = "24"
}

variable "webhook_ssm_name" {
  description = "Discord 비용 채널 웹후크 주소가 든 SSM 파라미터 이름"
  type        = string
  default     = "/llm-tollgate/dev/discord-webhook-cost"
}
