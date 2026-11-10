# Sutherland Banking Platform — OCI UK cell (HLD section 8, LLD section 13).

variable "region" {
  description = "OCI region. The UK cell runs in UK South (London)."
  type        = string
  default     = "uk-london-1"
}

variable "compartment_id" {
  description = "Compartment for this environment (from the CIS Landing Zone)."
  type        = string
}

variable "env" {
  description = "Environment name: dev, test or demo. Used in names and the Temporal namespace (pbp-<env>)."
  type        = string
  default     = "dev"
}

variable "vcn_cidr" {
  type    = string
  default = "10.20.0.0/16"
}

variable "kubernetes_version" {
  type    = string
  default = "v1.30.1"
}

variable "node_shape" {
  type    = string
  default = "VM.Standard.E4.Flex"
}

variable "node_ocpus" {
  type    = number
  default = 2
}

variable "node_memory_gbs" {
  type    = number
  default = 16
}

variable "node_count" {
  description = "Worker nodes, spread across fault domains (minimum 3 for the EHI responder's anti-affinity)."
  type        = number
  default     = 3
}

variable "node_image_id" {
  description = "Oracle Linux OKE worker image OCID for the region and Kubernetes version."
  type        = string
}

variable "postgres_password" {
  description = "Admin password for OCI Database with PostgreSQL. Stored in Vault; never commit it."
  type        = string
  sensitive   = true
}

variable "thredd_ehi_source_cidrs" {
  description = "Thredd's published egress ranges; only these may reach the EHI listener."
  type        = list(string)
  default     = []
}

variable "bff_backend_url" {
  description = "Internal URL of the BFF behind the ingress load balancer, used by API Gateway (set after the first Helm deploy)."
  type        = string
  default     = "http://10.20.10.10"
}

variable "ehi_load_balancer_id" {
  description = "OCID of the EHI load balancer created by the ehi-responder Service (set after the first Helm deploy) to attach WAF. Empty: no WAF yet."
  type        = string
  default     = ""
}

variable "services" {
  description = "Container image repositories in OCIR."
  type        = list(string)
  default = [
    "bff", "orchestrator", "mapping-service", "ehi-responder", "essence-adapter", "thredd-adapter",
    "atfinity-adapter", "credit-adapter", "recon-service", "vendor-simulator", "web",
  ]
}

variable "topics" {
  description = "Kafka topics (LLD section 9): name → partitions and retention hours. A .DLT stream is created for each."
  type = map(object({
    partitions = number
    retention  = number
  }))
  default = {
    "payment-commands"    = { partitions = 12, retention = 168 }
    "onboarding-commands" = { partitions = 6, retention = 168 }
    "card-commands"       = { partitions = 6, retention = 168 }
    "customer-commands"   = { partitions = 6, retention = 168 }
    "kyc-events"          = { partitions = 6, retention = 168 }
    "card-events"         = { partitions = 12, retention = 168 }
    "payment-events"      = { partitions = 12, retention = 168 }
    "onboarding-events"   = { partitions = 6, retention = 168 }
    "mapping-events"      = { partitions = 6, retention = 168 }
  }
}

variable "tenancy_ocid" {
  description = "Tenancy OCID. Dynamic groups live at the tenancy level (the External Secrets Operator's access to Vault)."
  type        = string
}

variable "modules" {
  description = "The bank's modules, the same list as Helm's modules. Without cards and creditcards nothing is created for Thredd's EHI."
  type        = list(string)
  default     = ["onboarding", "payments", "compliance", "cards", "creditcards", "recon"]
}

variable "webhook_secret" {
  description = "HMAC secret for vendor webhooks (LLD section 12). Stored in Vault; never commit it."
  type        = string
  sensitive   = true
}

variable "streaming_user" {
  description = "The OCI user the services log in to Streaming as, as <tenancy name>/<user name> (or <tenancy name>/<domain>/<user name>)."
  type        = string
}

variable "streaming_auth_token" {
  description = "An auth token of streaming_user (Identity → Users → Auth tokens). Stored in Vault; never commit it."
  type        = string
  sensitive   = true
}

variable "streaming_group" {
  description = "IAM group that streaming_user belongs to; it is allowed to use the stream pool."
  type        = string
  default     = "pbp-streaming"
}
