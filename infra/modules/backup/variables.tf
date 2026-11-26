variable "name" {
  type = string
}

variable "ec2_role_name" {
  description = "백업 권한을 붙일 EC2 역할 이름 (compute 모듈이 만든 것)"
  type        = string
}

variable "bucket_suffix" {
  description = "S3 버킷 이름은 전 세계에서 하나뿐이어야 해서 붙이는 꼬리표 (tfstate 버킷과 같은 값)"
  type        = string
  default     = "c3b2ea"
}

variable "retention_days" {
  description = "백업 보관 일수"
  type        = number
  default     = 14
}
