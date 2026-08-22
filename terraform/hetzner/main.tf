# =============================================================================
# OMCSI on a single self-managed Hetzner Cloud server (kubeadm + Helm)
# =============================================================================
# Design note: unlike the LKE/EKS modules — which deploy the chart through the
# Helm provider because the managed cluster exposes API credentials as resource
# attributes — a freshly kubeadm-bootstrapped node has no such attributes at
# plan time. To keep this a reliable single `terraform apply`, the chart is
# deployed over SSH from the node itself (upload chart + rendered values, run
# `helm upgrade --install`). The OMCSI Helm chart is the same one all targets
# share; only the delivery mechanism differs.
# =============================================================================

provider "hcloud" {
  token = var.hcloud_token
}

locals {
  discord_enabled = var.discord_webhook_url != "" ? "true" : "false"

  cloud_init = templatefile("${path.module}/cloud-init.sh.tftpl", {
    kubernetes_version             = var.kubernetes_version
    pod_cidr                       = var.pod_cidr
    service_node_port_range        = var.service_node_port_range
    calico_version                 = var.calico_version
    local_path_provisioner_version = var.local_path_provisioner_version
  })

  helm_values = templatefile("${path.module}/values.yaml.tftpl", {
    rcon_password            = var.rcon_password
    admin_password           = var.admin_password
    image_registry           = var.image_registry
    minecraft_nodeport       = var.minecraft_node_port
    http_nodeport            = var.http_node_port
    https_nodeport           = var.https_node_port
    storage_class            = var.storage_class
    minecraft_version        = var.minecraft_version
    operator_uuid            = var.operator_uuid
    operator_name            = var.operator_name
    server_motd              = var.server_motd
    max_players              = var.max_players
    java_opts                = var.java_opts
    discord_enabled          = local.discord_enabled
    discord_webhook_url      = var.discord_webhook_url
    deploy_auth_token        = var.deploy_auth_token
    agent_manager_enabled    = var.agent_manager_enabled
    agent_discord_bot_token  = var.agent_discord_bot_token
    agent_discord_channel_id = var.agent_discord_channel_id
    agent_anthropic_api_key  = var.agent_anthropic_api_key

    world_upload_max_size_mb      = var.world_upload_max_size_mb
    world_upload_max_extracted_mb = var.world_upload_max_extracted_mb
    world_upload_max_entries      = var.world_upload_max_entries
    world_upload_timeout_seconds  = var.world_upload_timeout_seconds
    multipart_temp_dir            = var.multipart_temp_dir
    mcserver_storage_size         = var.mcserver_storage_size
  })
}

# --- SSH key ------------------------------------------------------------------
resource "hcloud_ssh_key" "omcsi" {
  name       = "${var.server_name}-key"
  public_key = var.ssh_public_key
}

# --- Firewall -----------------------------------------------------------------
# SSH and the Kubernetes API are restricted to allowed_ssh_cidr; the game and
# dashboard ports are public. Egress is unrestricted (no "out" rules).
resource "hcloud_firewall" "omcsi" {
  name = "${var.server_name}-fw"

  rule {
    description = "SSH"
    direction   = "in"
    protocol    = "tcp"
    port        = "22"
    source_ips  = [var.allowed_ssh_cidr]
  }

  rule {
    description = "Kubernetes API"
    direction   = "in"
    protocol    = "tcp"
    port        = "6443"
    source_ips  = [var.allowed_ssh_cidr]
  }

  rule {
    description = "Minecraft game port"
    direction   = "in"
    protocol    = "tcp"
    port        = tostring(var.minecraft_node_port)
    source_ips  = ["0.0.0.0/0", "::/0"]
  }

  rule {
    description = "Dashboard HTTP"
    direction   = "in"
    protocol    = "tcp"
    port        = tostring(var.http_node_port)
    source_ips  = ["0.0.0.0/0", "::/0"]
  }

  rule {
    description = "Dashboard HTTPS"
    direction   = "in"
    protocol    = "tcp"
    port        = tostring(var.https_node_port)
    source_ips  = ["0.0.0.0/0", "::/0"]
  }
}

# --- Server -------------------------------------------------------------------
resource "hcloud_server" "omcsi" {
  name         = var.server_name
  server_type  = var.server_type
  image        = var.server_image
  location     = var.location
  user_data    = local.cloud_init
  ssh_keys     = [hcloud_ssh_key.omcsi.id]
  firewall_ids = [hcloud_firewall.omcsi.id]

  labels = {
    app = "omcsi"
  }

  public_net {
    ipv4_enabled = true
    ipv6_enabled = true
  }
}

# --- Deploy OMCSI via Helm from the node --------------------------------------
resource "null_resource" "deploy" {
  triggers = {
    server_id    = hcloud_server.omcsi.id
    values_sha   = sha256(local.helm_values)
    release_name = var.helm_release_name
    namespace    = var.helm_namespace
  }

  lifecycle {
    precondition {
      condition     = !var.agent_manager_enabled || var.agent_discord_bot_token != ""
      error_message = "agent_discord_bot_token is required when agent_manager_enabled is true."
    }
    precondition {
      condition     = !var.agent_manager_enabled || var.agent_discord_channel_id != ""
      error_message = "agent_discord_channel_id is required when agent_manager_enabled is true."
    }
    precondition {
      condition     = !var.agent_manager_enabled || var.agent_anthropic_api_key != ""
      error_message = "agent_anthropic_api_key is required when agent_manager_enabled is true."
    }
  }

  connection {
    type        = "ssh"
    host        = hcloud_server.omcsi.ipv4_address
    user        = "root"
    private_key = file(pathexpand(var.ssh_private_key_path))
    timeout     = "10m"
  }

  # Wait for the kubeadm bootstrap (cloud-init) to finish.
  provisioner "remote-exec" {
    inline = [
      "echo 'Waiting for Kubernetes bootstrap to complete...'",
      "for i in $(seq 1 120); do [ -f /var/lib/omcsi-bootstrap-complete ] && break; sleep 15; done",
      "test -f /var/lib/omcsi-bootstrap-complete || { echo 'Bootstrap did not complete in time; see /var/log/omcsi-bootstrap.log'; exit 1; }",
      "mkdir -p /opt/omcsi/chart",
    ]
  }

  # Upload the OMCSI Helm chart (contents) and the rendered values file.
  provisioner "file" {
    source      = "${path.module}/../../helm/omcsi/"
    destination = "/opt/omcsi/chart"
  }

  provisioner "file" {
    content     = local.helm_values
    destination = "/opt/omcsi/values.yaml"
  }

  # Install / upgrade the release.
  provisioner "remote-exec" {
    inline = [
      "chmod 600 /opt/omcsi/values.yaml",
      "export KUBECONFIG=/etc/kubernetes/admin.conf",
      "helm upgrade --install ${var.helm_release_name} /opt/omcsi/chart --namespace ${var.helm_namespace} --create-namespace -f /opt/omcsi/values.yaml --wait --timeout 15m",
    ]
  }
}

# --- Fetch a kubeconfig pointing at the public IP -----------------------------
resource "null_resource" "kubeconfig" {
  depends_on = [null_resource.deploy]

  triggers = {
    server_id = hcloud_server.omcsi.id
  }

  provisioner "local-exec" {
    interpreter = ["/bin/bash", "-c"]
    command     = <<-EOT
      ssh -i '${pathexpand(var.ssh_private_key_path)}' -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null \
        root@${hcloud_server.omcsi.ipv4_address} 'cat /etc/kubernetes/admin.conf' \
        | sed 's#server: https://[^:]*:6443#server: https://${hcloud_server.omcsi.ipv4_address}:6443#' \
        > '${path.module}/kubeconfig.yaml'
      chmod 600 '${path.module}/kubeconfig.yaml'
    EOT
  }
}
