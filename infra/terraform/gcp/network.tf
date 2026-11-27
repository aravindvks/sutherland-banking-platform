# Network (HLD section 8): GKE nodes in a private subnet with Cloud NAT on one reserved address (one
# egress IP for the vendors' allow-lists). Cloud SQL and Memorystore sit on private services access;
# Managed Kafka places its endpoints in the workers subnet. The global HTTPS load balancer with Cloud
# Armor is the only public entry.

locals {
  name   = "pbp-${var.env}"
  suffix = random_string.suffix.result

  workers_cidr  = cidrsubnet(var.vpc_cidr, 4, 0) # 10.50.0.0/20
  services_cidr = cidrsubnet(var.vpc_cidr, 4, 1) # 10.50.16.0/20
  psa_cidr      = cidrsubnet(var.vpc_cidr, 4, 2) # 10.50.32.0/20: Cloud SQL and Memorystore
}

# Bucket, Kafka cluster and key ring names are global or cannot be reused for a while after deletion.
resource "random_string" "suffix" {
  length  = 6
  special = false
  upper   = false
}

data "google_project" "current" {}

resource "google_project_service" "api" {
  for_each = toset([
    "compute.googleapis.com",
    "container.googleapis.com",
    "sqladmin.googleapis.com",
    "servicenetworking.googleapis.com",
    "redis.googleapis.com",
    "managedkafka.googleapis.com",
    "secretmanager.googleapis.com",
    "artifactregistry.googleapis.com",
    "cloudkms.googleapis.com",
    "iam.googleapis.com",
    "logging.googleapis.com",
    "monitoring.googleapis.com",
    "cloudtrace.googleapis.com",
  ])
  service            = each.value
  disable_on_destroy = false
}

resource "google_compute_network" "main" {
  name                    = local.name
  auto_create_subnetworks = false
  routing_mode            = "REGIONAL"
  depends_on              = [google_project_service.api]
}

resource "google_compute_subnetwork" "workers" {
  name                     = "${local.name}-workers"
  region                   = var.region
  network                  = google_compute_network.main.id
  ip_cidr_range            = local.workers_cidr
  private_ip_google_access = true # Secret Manager, Artifact Registry and Cloud Storage without the NAT

  secondary_ip_range {
    range_name    = "pods"
    ip_cidr_range = var.pods_cidr
  }

  secondary_ip_range {
    range_name    = "services"
    ip_cidr_range = local.services_cidr
  }

  log_config {
    aggregation_interval = "INTERVAL_5_SEC"
    flow_sampling        = 0.5
  }
}

# ---------- Fixed egress address for the vendors ----------

resource "google_compute_address" "nat" {
  name   = "${local.name}-egress"
  region = var.region
}

resource "google_compute_router" "router" {
  name    = "${local.name}-router"
  region  = var.region
  network = google_compute_network.main.id
}

resource "google_compute_router_nat" "nat" {
  name                               = "${local.name}-nat"
  router                             = google_compute_router.router.name
  region                             = var.region
  nat_ip_allocate_option             = "MANUAL_ONLY"
  nat_ips                            = [google_compute_address.nat.self_link]
  source_subnetwork_ip_ranges_to_nat = "ALL_SUBNETWORKS_ALL_IP_RANGES"

  log_config {
    enable = true
    filter = "ERRORS_ONLY"
  }
}

# ---------- Private services access: Cloud SQL and Memorystore get addresses in the VPC ----------

resource "google_compute_global_address" "psa" {
  name          = "${local.name}-psa"
  purpose       = "VPC_PEERING"
  address_type  = "INTERNAL"
  address       = cidrhost(local.psa_cidr, 0)
  prefix_length = 20
  network       = google_compute_network.main.id
}

resource "google_service_networking_connection" "psa" {
  network                 = google_compute_network.main.id
  service                 = "servicenetworking.googleapis.com"
  reserved_peering_ranges = [google_compute_global_address.psa.name]
}

# ---------- Firewall: inside the VPC only, plus Google's load balancer health checks ----------

resource "google_compute_firewall" "internal" {
  name      = "${local.name}-internal"
  network   = google_compute_network.main.id
  direction = "INGRESS"
  priority  = 1000

  allow {
    protocol = "all"
  }

  source_ranges = [var.vpc_cidr, var.pods_cidr]
}

resource "google_compute_firewall" "health_checks" {
  name      = "${local.name}-lb-health-checks"
  network   = google_compute_network.main.id
  direction = "INGRESS"
  priority  = 1000

  allow {
    protocol = "tcp"
  }

  # Google Front End and health-check ranges (container-native load balancing reaches the pods directly).
  source_ranges = ["35.191.0.0/16", "130.211.0.0/22"]
}
