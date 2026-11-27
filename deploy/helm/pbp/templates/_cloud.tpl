{{/*
Cloud-specific load balancer annotations (values: cloud = oci | aws | azure | gcp). Everything else in the chart
is the same on every cloud; addresses and credentials come from values-<cloud>.yaml.
*/}}

{{/* Thredd's EHI listener: public with TLS on OCI and AWS; on Azure an internal load balancer behind the
Application Gateway, which terminates TLS and applies the WAF. */}}
{{- define "pbp.ehiLbAnnotations" -}}
{{- if eq .Values.cloud "aws" }}
service.beta.kubernetes.io/aws-load-balancer-type: external
service.beta.kubernetes.io/aws-load-balancer-nlb-target-type: ip
service.beta.kubernetes.io/aws-load-balancer-scheme: internet-facing
service.beta.kubernetes.io/aws-load-balancer-ssl-cert: {{ .Values.aws.ehiCertificateArn | quote }}
service.beta.kubernetes.io/aws-load-balancer-ssl-ports: "443"
service.beta.kubernetes.io/aws-load-balancer-backend-protocol: tcp
{{- else if eq .Values.cloud "azure" }}
service.beta.kubernetes.io/azure-load-balancer-internal: "true"
service.beta.kubernetes.io/azure-load-balancer-ipv4: {{ .Values.azure.ehiPrivateIp | quote }}
{{- else }}
service.beta.kubernetes.io/oci-load-balancer-shape: flexible
service.beta.kubernetes.io/oci-load-balancer-shape-flex-min: {{ .Values.ehiLoadBalancer.shapeMinMbps | quote }}
service.beta.kubernetes.io/oci-load-balancer-shape-flex-max: {{ .Values.ehiLoadBalancer.shapeMaxMbps | quote }}
service.beta.kubernetes.io/oci-load-balancer-ssl-ports: "443"
service.beta.kubernetes.io/oci-load-balancer-tls-secret: {{ .Values.ehiLoadBalancer.tlsSecret | quote }}
service.beta.kubernetes.io/oci-load-balancer-backend-protocol: HTTP
{{- end }}
{{- end }}

{{/* The web app's private load balancer, in front of which sits the cloud's public entry
(OCI API Gateway, Azure Application Gateway). On AWS the web app is exposed by an ALB Ingress instead. */}}
{{- define "pbp.webLbAnnotations" -}}
{{- if eq .Values.cloud "azure" }}
service.beta.kubernetes.io/azure-load-balancer-internal: "true"
service.beta.kubernetes.io/azure-load-balancer-ipv4: {{ .Values.azure.webPrivateIp | quote }}
{{- else }}
service.beta.kubernetes.io/oci-load-balancer-internal: "true"
service.beta.kubernetes.io/oci-load-balancer-shape: flexible
service.beta.kubernetes.io/oci-load-balancer-shape-flex-min: "10"
service.beta.kubernetes.io/oci-load-balancer-shape-flex-max: "100"
{{- end }}
{{- end }}

{{- define "pbp.webLoadBalancer" -}}
{{- if and .Values.webInternalLoadBalancer (not (has .Values.cloud (list "aws" "gcp"))) }}true{{ end }}
{{- end }}

{{/* GCP: the web app and the EHI responder sit behind the GKE Ingress (global HTTPS load balancer) as
network endpoint groups, each with a BackendConfig for Cloud Armor, health check and timeout. */}}
{{- define "pbp.gcpBackend" -}}
{{- $name := index . 0 -}}
{{- $root := index . 1 -}}
{{- if and (eq $root.Values.cloud "gcp") (has $name (list "web" "ehi-responder")) }}true{{ end }}
{{- end }}
