output "nat_egress_ip" {
  description = "Give this to Finastra, Thredd and Atfinity for their allow-lists."
  value       = oci_core_public_ip.nat.ip_address
}

output "oke_cluster_id" {
  value = oci_containerengine_cluster.oke.id
}

output "postgres_endpoint" {
  value = oci_psql_db_system.pg.network_details[0].primary_db_endpoint_private_ip
}

output "kafka_bootstrap" {
  description = "Kafka bootstrap servers for the services (SASL_SSL with an auth token)."
  value       = oci_streaming_stream_pool.kafka.kafka_settings[0].bootstrap_servers
}

output "cache_endpoint" {
  value = oci_redis_redis_cluster.cache.primary_fqdn
}

output "api_gateway_hostname" {
  value = oci_apigateway_gateway.api.hostname
}

output "registry" {
  description = "Image prefix for the release pipeline."
  value       = "${var.region}.ocir.io/${data.oci_objectstorage_namespace.ns.namespace}/pbp"
}

output "vault_id" {
  value = oci_kms_vault.vault.id
}

output "apm_data_upload_endpoint" {
  value = oci_apm_apm_domain.apm.data_upload_endpoint
}

output "connect_harness_id" {
  description = "Kafka Connect storage for the outbox relay: Helm value connect.ociConnectHarnessId."
  value       = oci_streaming_connect_harness.connect.id
}
