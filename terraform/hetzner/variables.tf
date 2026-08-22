# =============================================================================
# Hetzner Cloud / server variables
# =============================================================================

variable "hcloud_token" {
  description = "Hetzner Cloud API token. Provide via -var / tfvars, or export as TF_VAR_hcloud_token. Create one under Project → Security → API Tokens (Read & Write)."
  type        = string
  sensitive   = true
}

variable "server_name" {
  description = "Name/hostname for the Hetzner Cloud server."
  type        = string
  default     = "omcsi"
}

variable "server_type" {
  description = "Hetzner Cloud server type. Defaults to cax31 (Ampere ARM64, 8 vCPU / 16 GB / 160 GB NVMe, ~EUR 12.49/mo) which comfortably runs the full OMCSI stack under the $20/mo target. Other ARM options: cax21 (4 vCPU/8 GB), cax41 (16 vCPU/32 GB). For x86 use cpx31. NOTE: ARM types require multi-arch images (the project's CI publishes them)."
  type        = string
  default     = "cax31"
}

variable "location" {
  description = "Hetzner Cloud location. ARM (CAX) types are available in EU locations: fsn1 (Falkenstein), nbg1 (Nuremberg), hel1 (Helsinki). Use ash/hil for US (x86 only)."
  type        = string
  default     = "fsn1"
}

variable "server_image" {
  description = "Base OS image. The bootstrap script targets Ubuntu (apt/containerd/kubeadm)."
  type        = string
  default     = "ubuntu-24.04"
}

variable "ssh_public_key" {
  description = "SSH public key (contents, e.g. the line from ~/.ssh/id_ed25519.pub) added to the server for access."
  type        = string
}

variable "ssh_private_key_path" {
  description = "Path to the matching SSH private key. Terraform uses it to bootstrap and deploy over SSH (provisioners). Not uploaded to the server."
  type        = string
}

variable "allowed_ssh_cidr" {
  description = "CIDR allowed to reach SSH (22) and the Kubernetes API (6443). Defaults to 0.0.0.0/0 for convenience; STRONGLY recommended to restrict to your own IP (e.g. 203.0.113.4/32). The Minecraft (25565) and web (80/443) ports are always open to the internet."
  type        = string
  default     = "0.0.0.0/0"
}

# =============================================================================
# Kubernetes / cluster bootstrap
# =============================================================================

variable "kubernetes_version" {
  description = "Kubernetes minor version to install (matches the pkgs.k8s.io apt channel, e.g. '1.34')."
  type        = string
  default     = "1.34"
}

variable "pod_cidr" {
  description = "Pod network CIDR for Calico. Keep aligned with Calico's default IP pool (192.168.0.0/16) unless you have a conflict; the bootstrap rewrites the Calico manifest to match."
  type        = string
  default     = "192.168.0.0/16"
}

variable "service_node_port_range" {
  description = "kube-apiserver --service-node-port-range. Widened from the 30000-32767 default so NodePort Services can bind the standard 25565/80/443 directly on the node's public IP (no cloud LoadBalancer needed)."
  type        = string
  default     = "80-32767"
}

variable "calico_version" {
  description = "Calico release tag used for the CNI manifests."
  type        = string
  default     = "v3.28.2"
}

variable "local_path_provisioner_version" {
  description = "Rancher local-path-provisioner release tag, installed as the default StorageClass."
  type        = string
  default     = "v0.0.30"
}

# =============================================================================
# Public exposure (NodePorts pinned to standard ports on the node IP)
# =============================================================================

variable "minecraft_node_port" {
  description = "Fixed NodePort for the Minecraft game port. Must fall within service_node_port_range. Defaults to 25565 so players connect to <server-ip> with no port suffix."
  type        = number
  default     = 25565
}

variable "http_node_port" {
  description = "Fixed NodePort for the dashboard HTTP port (redirects to HTTPS). Must fall within service_node_port_range."
  type        = number
  default     = 80
}

variable "https_node_port" {
  description = "Fixed NodePort for the dashboard HTTPS port. Must fall within service_node_port_range."
  type        = number
  default     = 443
}

# =============================================================================
# OMCSI application configuration
# =============================================================================

variable "rcon_password" {
  description = "RCON password for the Minecraft server (required)."
  type        = string
  sensitive   = true
}

variable "admin_password" {
  description = "Admin password for the OMCSI web dashboard (required)."
  type        = string
  sensitive   = true
}

variable "image_registry" {
  description = "Container image registry/repository prefix for OMCSI images. Defaults to 'dmccoystephenson' (Docker Hub). Provide only the prefix; images resolve to <prefix>/open-mc-server-*."
  type        = string
  default     = "dmccoystephenson"

  validation {
    condition     = length(regexall("\\s", var.image_registry)) == 0 && !endswith(var.image_registry, "/")
    error_message = "image_registry must be a registry/repository prefix only, with no whitespace and no trailing '/'."
  }
}

variable "storage_class" {
  description = "StorageClass for PVCs. Defaults to 'local-path' (installed by the bootstrap and marked default)."
  type        = string
  default     = "local-path"
}

variable "helm_release_name" {
  description = "Helm release name for the OMCSI chart."
  type        = string
  default     = "omcsi"
}

variable "helm_namespace" {
  description = "Kubernetes namespace for the OMCSI deployment."
  type        = string
  default     = "omcsi"
}

variable "minecraft_version" {
  description = "Minecraft version tag for the server image."
  type        = string
  default     = "26.1"
}

variable "operator_uuid" {
  description = "Minecraft UUID of the server operator (op). Leave default to configure ops manually via the console."
  type        = string
  default     = "YOUR_UUID_HERE"
}

variable "operator_name" {
  description = "Minecraft username of the server operator (op)."
  type        = string
  default     = "YOUR_USERNAME_HERE"
}

variable "server_motd" {
  description = "Message of the day shown in the Minecraft server list."
  type        = string
  default     = "An Open Minecraft Server"
}

variable "max_players" {
  description = "Maximum number of concurrent players."
  type        = number
  default     = 20
}

variable "java_opts" {
  description = "JVM options for the Minecraft server. Defaults size the heap for a 16 GB node (cax31). Reduce for smaller server types (e.g. '-Xmx2G -Xms1G' on cax21)."
  type        = string
  default     = "-Xmx6G -Xms4G"
}

# =============================================================================
# Optional: notifications, plugin deploy, agent-manager
# =============================================================================

variable "discord_webhook_url" {
  description = "Discord webhook URL for alert-manager notifications. When set, Discord alerts are automatically enabled."
  type        = string
  sensitive   = true
  default     = ""
}

variable "deploy_auth_token" {
  description = "Bearer token for the plugin hot-deploy endpoint. Leave empty to disable the deploy endpoint."
  type        = string
  sensitive   = true
  default     = ""
}

variable "agent_manager_enabled" {
  description = "Enable the agent-manager (Discord AI bot) Deployment."
  type        = bool
  default     = false
}

variable "agent_discord_bot_token" {
  description = "Discord bot token (required when agent_manager_enabled = true)."
  type        = string
  sensitive   = true
  default     = ""
}

variable "agent_discord_channel_id" {
  description = "Discord channel ID (required when agent_manager_enabled = true)."
  type        = string
  sensitive   = true
  default     = ""
}

variable "agent_anthropic_api_key" {
  description = "Anthropic API key (required when agent_manager_enabled = true)."
  type        = string
  sensitive   = true
  default     = ""
}

# --- World upload limits ------------------------------------------------------
# A world upload passes through four limits in series: nginx's client_max_body_size,
# the web app's multipart limit, the wrapper's multipart limit, and the wrapper's own
# WORLD_UPLOAD_MAX_FILE_SIZE_MB check. Setting them independently is how you end up
# with a bare 413 from a limit you forgot, so the module derives all four from one
# variable — raise world_upload_max_size_mb and every layer moves together.

variable "world_upload_max_size_mb" {
  description = "Largest world archive accepted, in MB. Drives nginx's client_max_body_size, both services' multipart limits, and the wrapper's own size check. The wrapper caps a single upload at 2048 MB unless raised here."
  type        = number
  default     = 2048

  validation {
    condition     = var.world_upload_max_size_mb > 0
    error_message = "world_upload_max_size_mb must be greater than 0."
  }
}

variable "world_upload_max_extracted_mb" {
  description = "Cap on the total extracted size of a world archive, in MB (zip bomb protection). A world expands well past its compressed size, so keep this several times world_upload_max_size_mb, and size mcserver_storage_size to hold the old world and the extracted new one at once."
  type        = number
  default     = 10240

  validation {
    condition     = var.world_upload_max_extracted_mb >= var.world_upload_max_size_mb
    error_message = "world_upload_max_extracted_mb must be at least world_upload_max_size_mb — an archive cannot extract to less than its compressed size."
  }
}

variable "world_upload_max_entries" {
  description = "Cap on the number of entries in a world archive (zip bomb protection). A very large world can legitimately approach the default; raise it rather than trimming a real world."
  type        = number
  default     = 100000

  validation {
    condition     = var.world_upload_max_entries > 0
    error_message = "world_upload_max_entries must be greater than 0."
  }
}

variable "world_upload_timeout_seconds" {
  description = "How long the upload routes may run before nginx and the web app give up. Covers transfer plus extraction, the world swap, and the server restart — not just transfer time."
  type        = number
  default     = 3600
}

variable "multipart_temp_dir" {
  description = "Directory the services buffer an in-flight upload into. Empty uses each container's writable layer, which a multi-GB archive can exhaust. Set to a path on the mcserver volume (e.g. /mcserver/tmp) when raising world_upload_max_size_mb into GB territory."
  type        = string
  default     = ""
}

variable "mcserver_storage_size" {
  description = "Size of the mcserver PersistentVolumeClaim, which holds the world, plugins, and the staging directory an upload extracts into. Needs room for the old world and the extracted new one at the same time."
  type        = string
  default     = "10Gi"
}
