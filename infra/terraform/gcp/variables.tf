# Sutherland Banking Platform — GCP UK cell. Same shape as the OCI cell (infra/terraform/oci):
# the application images and Helm chart do not change; only this module and values-gcp.yaml do.

variable "project_id" {
  description = "Google Cloud project for this environment (one project per environment)."
  type        = string
}

variable "region" {
  description = "Google Cloud region. The UK cell runs in London (europe-west2)."
  type        = string
  default     = "europe-west2"
}

variable "env" {
  description = "Environment name: dev, test or demo. Used in names and the Temporal namespace (pbp-<env>)."
  type        = string
  default     = "dev"
}

variable "modules" {
  description = "The bank's modules, the same list as Helm's. Without cards or creditcards there is no EHI host name, so no EHI rule in Cloud Armor."
  type        = list(string)
  default     = ["onboarding", "payments", "compliance", "cards", "creditcards", "recon"]
}

variable "vpc_cidr" {
  description = "Primary range of the VPC. Workers, GKE services and private services access are carved from it."
  type        = string
  default     = "10.50.0.0/16"
}

variable "pods_cidr" {
  description = "Secondary range for GKE pods (VPC-native). Must not overlap the VPC or the bank's networks."
  type        = string
  default     = "10.56.0.0/14"
}

variable "master_cidr" {
  description = "/28 for the GKE control plane's private endpoint."
  type        = string
  default     = "172.16.0.32/28"
}

variable "node_machine_type" {
  description = "4 vCPU / 16 GB, the same size as the OCI E4.Flex workers."
  type        = string
  default     = "n2-standard-4"
}

variable "node_count" {
  description = "Worker nodes in total, spread over three zones (3 for dev and demo, 6 for production). Must be a multiple of 3."
  type        = number
  default     = 3

  validation {
    condition     = var.node_count >= 3 && var.node_count % 3 == 0
    error_message = "node_count must be a multiple of 3 (one or more nodes in each of three zones)."
  }
}

variable "postgres_tier" {
  description = "Cloud SQL machine type (Enterprise edition): 2 vCPU / 8 GB for dev and demo."
  type        = string
  default     = "db-custom-2-8192"
}

variable "postgres_password" {
  description = "Password for the pbpadmin user. Stored in Secret Manager; never commit it."
  type        = string
  sensitive   = true
}

variable "webhook_secret" {
  description = "HMAC secret for vendor webhooks (LLD section 8)."
  type        = string
  sensitive   = true
}

variable "thredd_ehi_source_cidrs" {
  description = "Thredd's published egress ranges; Cloud Armor admits only these to the EHI host name."
  type        = list(string)
  default     = []
}

variable "web_hostname" {
  description = "Public host name of the web app and BFF, e.g. bank-dev.example.co.uk."
  type        = string
  default     = "bank-dev.example.co.uk"
}

variable "ehi_hostname" {
  description = "Host name registered with Thredd for EHI calls, e.g. ehi-dev.example.co.uk."
  type        = string
  default     = "ehi-dev.example.co.uk"
}

variable "topics" {
  description = "Kafka topics (LLD section 9): name → partitions and retention days. A .DLT topic is created for each."
  type = map(object({
    partitions = number
    retention  = number
  }))
  default = {
    "payment-commands"    = { partitions = 12, retention = 7 }
    "onboarding-commands" = { partitions = 6, retention = 7 }
    "card-commands"       = { partitions = 6, retention = 7 }
    "customer-commands"   = { partitions = 6, retention = 7 }
    "kyc-events"          = { partitions = 6, retention = 30 }
    "card-events"         = { partitions = 12, retention = 30 }
    "payment-events"      = { partitions = 12, retention = 30 }
    "onboarding-events"   = { partitions = 6, retention = 30 }
  }
}

variable "compacted_topics" {
  description = "Compacted topics: mapping-events (latest mapping per key) and Kafka Connect's own topics."
  type        = map(number)
  default = {
    "mapping-events"       = 6
    "pbp-connect-configs"  = 1
    "pbp-connect-offsets"  = 25
    "pbp-connect-statuses" = 5
  }
}
