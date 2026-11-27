# The same outputs as the OCI, AWS and Azure cells, so values-<cloud>.yaml is filled the same way.

output "nat_egress_ip" {
  description = "Give this to Finastra, Thredd and Atfinity for their allow-lists."
  value       = google_compute_address.nat.address
}

output "cluster_name" {
  description = "gcloud container clusters get-credentials <this> --region <region> --internal-ip (from inside the VPC: the API endpoint is private)."
  value       = google_container_cluster.gke.name
}

output "postgres_endpoint" {
  description = "values-gcp.yaml config.DB_HOST"
  value       = google_sql_database_instance.pg.private_ip_address
}

output "postgres_replica_endpoint" {
  value = google_sql_database_instance.pg_replica.private_ip_address
}

output "kafka_bootstrap" {
  description = "values-gcp.yaml config.KAFKA_BOOTSTRAP (SASL_SSL, PLAIN)."
  value       = "bootstrap.${google_managed_kafka_cluster.kafka.cluster_id}.${var.region}.managedkafka.${var.project_id}.cloud.goog:9092"
}

output "cache_endpoint" {
  description = "values-gcp.yaml config.REDIS_HOST (TLS on port 6378)."
  value       = google_redis_instance.cache.host
}

output "cache_port" {
  value = google_redis_instance.cache.port
}

output "registry" {
  description = "Image prefix for the release pipeline (values-gcp.yaml image.registry)."
  value       = "${var.region}-docker.pkg.dev/${var.project_id}/${google_artifact_registry_repository.pbp.repository_id}"
}

output "external_secrets_service_account" {
  description = "Annotate the external-secrets Kubernetes service account: iam.gke.io/gcp-service-account."
  value       = google_service_account.external_secrets.email
}

output "web_ip" {
  description = "Point web_hostname (and ehi_hostname, with a card module) at this address."
  value       = google_compute_global_address.web.address
}

output "web_ip_name" {
  description = "values-gcp.yaml gcp.staticIpName"
  value       = google_compute_global_address.web.name
}

output "certificate_name" {
  description = "values-gcp.yaml gcp.certificateName"
  value       = google_compute_managed_ssl_certificate.web.name
}

output "security_policy_name" {
  description = "values-gcp.yaml gcp.securityPolicy (Cloud Armor)"
  value       = google_compute_security_policy.edge.name
}

output "ssl_policy_name" {
  description = "values-gcp.yaml gcp.sslPolicy"
  value       = google_compute_ssl_policy.tls.name
}
