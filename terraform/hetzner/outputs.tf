# =============================================================================
# Outputs
# =============================================================================

output "server_ipv4" {
  description = "Public IPv4 address of the OMCSI server."
  value       = hcloud_server.omcsi.ipv4_address
}

output "server_ipv6" {
  description = "Public IPv6 address of the OMCSI server."
  value       = hcloud_server.omcsi.ipv6_address
}

output "minecraft_address" {
  description = "Connect your Minecraft client to this address."
  value       = "${hcloud_server.omcsi.ipv4_address}:${var.minecraft_node_port}"
}

output "dashboard_url" {
  description = "OMCSI web dashboard URL (self-signed cert by default — expect a browser warning)."
  value       = var.https_node_port == 443 ? "https://${hcloud_server.omcsi.ipv4_address}" : "https://${hcloud_server.omcsi.ipv4_address}:${var.https_node_port}"
}

output "ssh_command" {
  description = "SSH into the server."
  value       = "ssh -i ${var.ssh_private_key_path} root@${hcloud_server.omcsi.ipv4_address}"
}

output "kubeconfig_path" {
  description = "Path to the generated kubeconfig (API access is restricted to allowed_ssh_cidr)."
  value       = "${path.module}/kubeconfig.yaml"
}

output "kubectl_hint" {
  description = "Use the generated kubeconfig with kubectl."
  value       = "export KUBECONFIG=${path.module}/kubeconfig.yaml && kubectl get pods -n ${var.helm_namespace}"
}

output "estimated_monthly_cost" {
  description = "What the monthly bill consists of. No price is quoted: Hetzner pricing changes, so check the current rate for the chosen server_type."
  value       = "Server type '${var.server_type}' in '${var.location}'. Check Hetzner's current price for this type (see terraform/COST_ANALYSIS.md). No control-plane, LoadBalancer, or NAT charges."
}
