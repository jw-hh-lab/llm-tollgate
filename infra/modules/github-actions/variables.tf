variable "name" {
  type = string
}

variable "instance_id" {
  description = "Actions가 명령을 보내고 켜고 끌 EC2"
  type        = string
}

variable "github_repo" {
  description = "역할을 빌릴 수 있는 저장소 (조직/이름)"
  type        = string
  default     = "jw-hh-lab/llm-tollgate"
}
