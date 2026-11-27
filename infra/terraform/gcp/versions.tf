terraform {
  required_version = ">= 1.6"
  required_providers {
    google = {
      source  = "hashicorp/google"
      version = "~> 6.20"
    }
    google-beta = {
      source  = "hashicorp/google-beta"
      version = "~> 6.20"
    }
    random = {
      source  = "hashicorp/random"
      version = "~> 3.6"
    }
  }
  # State in a Cloud Storage bucket. Fill in backend.hcl and run:
  #   terraform init -backend-config=backend.hcl
  backend "gcs" {}
}

provider "google" {
  project = var.project_id
  region  = var.region
  default_labels = {
    app = "sutherland-banking-platform"
    env = var.env
  }
}

provider "google-beta" {
  project = var.project_id
  region  = var.region
}
