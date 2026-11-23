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

variable "github_repo_with_ids" {
  description = "GitHub가 증명서(sub)에 넣는 ID 포함 형식. 이름을 바꿔도 ID는 안 바뀜"
  type        = string
  default     = "jw-hh-lab@353662139/llm-tollgate@1449159549"
}