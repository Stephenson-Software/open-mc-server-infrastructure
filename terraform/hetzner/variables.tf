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
  description = "CIDR allowed to reach SSH (22). Also the fallback for the Kubernetes API when allowed_api_cidrs is empty, which is how this behaved before the two were separable. Defaults to 0.0.0.0/0 for convenience; STRONGLY recommended to restrict to your own IP (e.g. 203.0.113.4/32). The Minecraft (25565) and web (80/443) ports are always open to the internet."
  type        = string
  default     = "0.0.0.0/0"
}

variable "allowed_api_cidrs" {
  description = "CIDRs allowed to reach the Kubernetes API (6443). Empty means fall back to allowed_ssh_cidr, preserving the previous behaviour where one value governed both. Set it to grant API access without also granting SSH -- a monitoring host needs kubectl, not a shell on the node -- and to allow more than one operator, which a single CIDR cannot express."
  type        = list(string)
  default     = []

  validation {
    condition = alltrue([
      for cidr in var.allowed_api_cidrs : can(cidrhost(cidr, 0))
    ])
    error_message = "Every entry in allowed_api_cidrs must be a valid CIDR, e.g. 203.0.113.4/32."
  }
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

variable "image_tag" {
  description = "Image tag for the Minecraft server image (open-mc-server) only. Defaults to 'latest'. Pin it to a published tag (e.g. '26.2') for reproducible deployments: the Spigot jar is compiled into this image at build time, so a moving 'latest' also moves the Minecraft version. CI publishes this image as both 'latest' and the Minecraft version it was built with. Keep it consistent with minecraft_version — the entrypoint selects the jar by that name and exits if the image does not contain it."
  type        = string
  default     = "latest"

  validation {
    condition     = length(trimspace(var.image_tag)) > 0 && length(regexall("\\s", var.image_tag)) == 0
    error_message = "image_tag must be a non-empty tag with no whitespace."
  }
}

variable "supporting_image_tag" {
  description = "Image tag for the five supporting images (webapp, nginx, backup-manager, alert-manager, agent-manager). Defaults to 'latest', which moves whenever the publish workflow runs. For a reproducible deployment set it to a commit SHA: CI publishes each supporting image as both 'latest' and the SHA of the commit that built it. These images carry no version tag, which is why this is separate from image_tag -- the Minecraft image is pinned by Minecraft version, the supporting images by commit. Note that the publish workflow is path-filtered, so a SHA tag exists only for commits that actually rebuilt the images."
  type        = string
  default     = "latest"

  validation {
    condition     = length(trimspace(var.supporting_image_tag)) > 0 && length(regexall("\\s", var.supporting_image_tag)) == 0
    error_message = "supporting_image_tag must be a non-empty tag with no whitespace."
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
  default     = "26.2"
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

variable "whitelist_enabled" {
  description = "Turn the whitelist on, so only players listed in whitelist.json may join. Defaults to false (open server)."
  type        = bool
  default     = false
}

variable "enforce_whitelist" {
  description = "Also kick already-connected players who are not on the whitelist whenever the list is reloaded. No effect unless whitelist_enabled is true."
  type        = bool
  default     = false
}

variable "java_opts" {
  description = "JVM options for the Minecraft server. Defaults size the heap for a 16 GB node (cax31). Reduce for smaller server types (e.g. '-Xmx2G -Xms1G' on cax21)."
  type        = string
  default     = "-Xmx6G -Xms4G"
}

variable "difficulty" {
  description = "Minecraft world difficulty (server.properties `difficulty`)."
  type        = string
  default     = "normal"

  validation {
    condition     = contains(["peaceful", "easy", "normal", "hard"], var.difficulty)
    error_message = "difficulty must be one of: peaceful, easy, normal, hard."
  }
}

variable "gamemode" {
  description = "Default gamemode for joining players (server.properties `gamemode`)."
  type        = string
  default     = "survival"

  validation {
    condition     = contains(["survival", "creative", "adventure", "spectator"], var.gamemode)
    error_message = "gamemode must be one of: survival, creative, adventure, spectator."
  }
}

variable "pvp_enabled" {
  description = "Allow players to damage each other (server.properties `pvp`)."
  type        = bool
  default     = true
}

variable "online_mode" {
  description = "Verify players against Mojang's authentication servers (server.properties `online-mode`). Leave enabled: with it off, anyone can join under any username."
  type        = bool
  default     = true
}

variable "default_plugins" {
  description = "Comma-separated list of direct download URLs to plugin JARs, installed automatically on server setup (e.g. \"https://example.com/A.jar,https://example.com/B.jar\"). A plugin already present in the plugins directory (matched by filename) is left untouched. Empty by default."
  type        = string
  default     = ""

  validation {
    condition = var.default_plugins == "" || alltrue([
      for url in split(",", var.default_plugins) :
      length(regexall("^https?://[^\\s\"]+$", trimspace(url))) == 1
    ])
    error_message = "default_plugins must be a comma-separated list of http(s) URLs, with no whitespace or double quotes inside a URL."
  }
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

variable "rbac_enabled" {
  description = "Create namespace-scoped ServiceAccounts (omcsi-viewer, and omcsi-operator when rbac_operator_enabled) for operating this release. Off by default because enabling it mints credentials. The point is that day-to-day work -- monitoring especially -- need not use the cluster-admin kubeconfig Terraform writes, which cannot be revoked. Pair it with allowed_api_cidrs to let a monitoring host reach the API without also getting SSH."
  type        = bool
  default     = false
}

variable "rbac_operator_enabled" {
  description = "Also create the omcsi-operator ServiceAccount. Requires rbac_enabled. This account is effectively namespace-admin: it can exec into containers and read Secrets, the latter unavoidably, because Helm stores each release revision as a Secret and that includes the one holding the RCON and admin passwords. A large reduction from cluster-admin, but not a low-privilege credential."
  type        = bool
  default     = false
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

variable "backup_max_size_mb" {
  description = "Cap on the total size of the backups directory, in MB. Set this to a few times the world size: the newest backup is never deleted to satisfy the cap, so a value below one backup's size means only ever holding that single backup. Sized together with backups_storage_size."
  type        = number
  default     = 10240

  validation {
    condition     = var.backup_max_size_mb > 0
    error_message = "backup_max_size_mb must be greater than 0."
  }
}

variable "backups_storage_size" {
  description = "Size of the backups PersistentVolumeClaim. Must comfortably exceed backup_max_size_mb, since the cap is enforced after a backup is written rather than before."
  type        = string
  default     = "20Gi"
}

variable "mcserver_storage_size" {
  description = "Size of the mcserver PersistentVolumeClaim, which holds the world, plugins, and the staging directory an upload extracts into. Needs room for the old world and the extracted new one at the same time."
  type        = string
  default     = "10Gi"
}

# --- BlueMap ------------------------------------------------------------------

variable "bluemap_enabled" {
  description = "Serve BlueMap's webapp through the proxy. Requires the BlueMap plugin to be installed in the server's plugins directory — nothing listens on the wrapper's bluemap port until it is, and the route would return 502."
  type        = bool
  default     = false
}

variable "bluemap_path" {
  description = "Path BlueMap's webapp is served at, including both slashes (e.g. \"/map/\")."
  type        = string
  default     = "/map/"
}
