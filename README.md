# Open Minecraft Server Infrastructure

[![CI Pipeline](https://github.com/dmccoystephenson/open-mc-server-infrastructure/workflows/CI%20Pipeline/badge.svg?branch=main)](https://github.com/dmccoystephenson/open-mc-server-infrastructure/actions)

An open, community-agnostic, Docker-based Minecraft server infrastructure running the latest version of Minecraft (26.1) with Spigot for enhanced plugin support and performance. Highly configurable and customizable for any use case.

## Features

- **Latest Minecraft Version**: Running Minecraft 26.1 with Spigot
- **Docker Containerized**: Easy deployment and management
- **Web Dashboard**: Built-in Spring Boot web application for server management
- **Automated Backups**: Scheduled backups with automatic cleanup and size management
- **Alert Notifications**: Discord notifications for server events and admin alerts
- **Configurable**: Environment-based configuration
- **Persistent Data**: Server data persists across container restarts
- **Easy Management**: Simple scripts for starting and stopping the server
- **RCON Support**: Send commands to the server remotely via web interface

## Prerequisites

- [Docker](https://docs.docker.com/get-docker/)
- [Docker Compose](https://docs.docker.com/compose/install/)
- [Git](https://git-scm.com/downloads)

For Kubernetes deployments, you will also need:
- A Kubernetes cluster (v1.24+)
- [Helm](https://helm.sh/docs/intro/install/) 3.x

## Deployment Options

### Local Development
Follow the Quick Start guide below for running the server on your local machine.

### Self-Hosting at Home
For secure home deployment with proper firewall configuration, network setup, and security best practices, see the **[Self-Hosting Guide](SELF-HOSTING.md)**.

The Self-Hosting Guide covers:
- Hardware and network requirements
- Router and port forwarding configuration
- Firewall setup (UFW, iptables, OPNsense, pfSense)
- DDoS protection and rate limiting
- Dynamic DNS configuration
- SSL certificate setup for public access
- Monitoring and maintenance
- Advanced security configurations

### Cheapest Cloud Hosting (Hetzner, ~$14/month)
If you want to run OMCSI in the cloud for the lowest possible cost, the **[Hetzner single-node Terraform module](#deploying-to-hetzner-cloud-cheapest-single-node)** provisions one server, self-manages a `kubeadm` Kubernetes cluster on it, and deploys the Helm chart — all under the $20/month target with no managed-control-plane fee, cloud LoadBalancer, or NAT gateway. See the [Cost Analysis](terraform/COST_ANALYSIS.md) for how this compares to managed Kubernetes (LKE ~$109/mo, EKS ~$248/mo).

### Kubernetes (Helm)
OMCSI ships with a Helm chart in [`helm/omcsi/`](helm/omcsi/) for deploying to any Kubernetes cluster (k3s, kind, EKS, GKE, etc.).

**Prerequisites**
- A running Kubernetes cluster (v1.24+)
- [Helm](https://helm.sh/docs/intro/install/) 3.x
- Container images available on Docker Hub under `dmccoystephenson` (the default). If using custom images, see [Building and Pushing Images](#building-and-pushing-images) below

**Quick Start**
```bash
# Lint the chart
helm lint helm/omcsi --set secrets.rconPassword=x --set secrets.adminPassword=x

# Install (rconPassword and adminPassword are required)
# Images default to Docker Hub under 'dmccoystephenson'.
# See "Storage Classes" section below for cloud deployments.
helm install omcsi ./helm/omcsi --namespace omcsi --create-namespace \
  --set secrets.rconPassword=changeme \
  --set secrets.adminPassword=strongpassword

# Or install with the optional agent-manager enabled
helm install omcsi ./helm/omcsi --namespace omcsi --create-namespace \
  --set secrets.rconPassword=changeme \
  --set secrets.adminPassword=strongpassword \
  --set agentManager.enabled=true \
  --set secrets.agentDiscordBotToken=BOT_TOKEN \
  --set secrets.agentDiscordChannelId=CHANNEL_ID \
  --set secrets.agentAnthropicApiKey=API_KEY

# Enable agent-manager on an existing release
helm upgrade omcsi ./helm/omcsi --namespace omcsi \
  --set secrets.rconPassword=changeme \
  --set secrets.adminPassword=strongpassword \
  --set agentManager.enabled=true \
  --set secrets.agentDiscordBotToken=BOT_TOKEN \
  --set secrets.agentDiscordChannelId=CHANNEL_ID \
  --set secrets.agentAnthropicApiKey=API_KEY

# Upgrade an existing release
helm upgrade omcsi ./helm/omcsi --namespace omcsi --reuse-values

# Uninstall
helm uninstall omcsi --namespace omcsi
```

**Installing without cloning the repo**

The chart is also published as an OCI artifact on every `helm/omcsi/Chart.yaml` version bump (see [`.github/workflows/helm-publish.yml`](.github/workflows/helm-publish.yml)), so it can be installed directly without a local checkout:
```bash
helm install omcsi oci://ghcr.io/dmccoystephenson/charts/omcsi --version 0.1.0 \
  --namespace omcsi --create-namespace \
  --set secrets.rconPassword=changeme \
  --set secrets.adminPassword=strongpassword
```

Note: GHCR packages are private on first publish. The package's visibility must be switched to public once (repo → Packages → `omcsi` → Package settings → Change visibility) before anonymous `helm install`/`helm pull` from the `oci://` URL will work; until then, run `helm registry login ghcr.io` with a token that has `read:packages`.

The chart exposes most application-level `sample.env` variables through `values.yaml`. Some values are intentionally computed or fixed in the templates (e.g., internal service URLs are derived from Helm helpers, RCON port references are sourced from `minecraftWrapper.internalService.rconPort`, and `DATA_STORAGE_PATH` is hardcoded to match the PVC mount). Docker Compose-only variables like container names and host port mappings are excluded — Kubernetes manages those natively. See [`helm/omcsi/values.yaml`](helm/omcsi/values.yaml) for the full list of configurable values including image tags, replica counts, resource requests/limits, storage classes, service types, and feature flags.

**Key design notes:**
- `secrets.rconPassword` and `secrets.adminPassword` are **required** — the chart will refuse to install without them
- World data and service data persist across pod restarts via `PersistentVolumeClaim` resources
- Sensitive values (passwords, tokens, API keys) are stored in a Kubernetes `Secret`
- Internal service discovery uses Kubernetes `Service` DNS names (e.g., `omcsi-minecraft-wrapper`, `omcsi-alert-manager`)
- The Minecraft game port is exposed via a configurable Service (default `NodePort`); RCON, BlueMap, and the wrapper API are on a separate internal `ClusterIP` Service
- The nginx config is managed via a `ConfigMap` and points to the webapp service automatically
- The agent-manager is disabled by default and can be enabled via `agentManager.enabled=true`
- Pods sharing the mcserver PVC use pod affinity to prefer co-locating on the same node (recommended for `ReadWriteOnce` volumes)
- The backup-manager uses `tar` directly on the mounted `/mcserver` PVC — no Docker socket or Docker CLI required; scheduled backups are enabled by default

#### Building and Pushing Images

The default image repositories in `values.yaml` point to `dmccoystephenson/open-mc-server-*` on Docker Hub. If those images are already published, no additional setup is needed.

To use **custom** images (e.g., a private fork or registry), build, tag, push, and override:

**1. Build all images**

```bash
docker build -t open-mc-server .
docker build -t open-mc-server-webapp ./web-app
docker build -t open-mc-server-nginx ./nginx
docker build -t open-mc-server-backup-manager ./backup-manager
docker build -t open-mc-server-alert-manager ./alert-manager
docker build -t open-mc-server-agent-manager ./agent-manager
```

> **Note:** The `open-mc-server` image builds Spigot from source, which can take 10–15 minutes on the first run.

**2. Tag and push to your registry**

Replace `YOUR_REGISTRY` with your Docker Hub username, ECR URI, or other registry:

```bash
REGISTRY=YOUR_REGISTRY

for img in open-mc-server open-mc-server-webapp open-mc-server-nginx \
           open-mc-server-backup-manager open-mc-server-alert-manager \
           open-mc-server-agent-manager; do
  docker tag "$img:latest" "$REGISTRY/$img:latest"
  docker push "$REGISTRY/$img:latest"
done
```

**3. Override image repositories at install time**

```bash
REGISTRY=YOUR_REGISTRY

helm install omcsi ./helm/omcsi --namespace omcsi --create-namespace \
  --set secrets.rconPassword=changeme \
  --set secrets.adminPassword=strongpassword \
  --set minecraftWrapper.image.repository=$REGISTRY/open-mc-server \
  --set webapp.image.repository=$REGISTRY/open-mc-server-webapp \
  --set nginx.image.repository=$REGISTRY/open-mc-server-nginx \
  --set backupManager.image.repository=$REGISTRY/open-mc-server-backup-manager \
  --set alertManager.image.repository=$REGISTRY/open-mc-server-alert-manager
```

When using Terraform, set the `image_registry` variable instead:

```hcl
image_registry = "YOUR_REGISTRY"
```

#### Storage Classes

The Helm chart defaults all PVC `storageClass` values to an empty string, which uses the cluster's default StorageClass. If your cluster does **not** have a default StorageClass configured, PVCs will remain in `Pending` state and pods will fail to schedule.

**Common StorageClass values by provider:**

| Provider | StorageClass | Notes |
|---|---|---|
| AWS EKS | `gp2` or `gp3` | EBS volumes; `gp2` is the most common default |
| Linode LKE | `linode-block-storage-retain` | Linode Block Storage |
| Minikube | `standard` | Pre-configured as default |
| GKE | `standard` or `premium-rwo` | Usually configured as default |

**Override at install time:**

```bash
helm install omcsi ./helm/omcsi --namespace omcsi --create-namespace \
  --set secrets.rconPassword=changeme \
  --set secrets.adminPassword=strongpassword \
  --set persistence.mcserver.storageClass=gp2 \
  --set persistence.webappData.storageClass=gp2 \
  --set persistence.alertManagerData.storageClass=gp2 \
  --set persistence.backups.storageClass=gp2
```

When using Terraform, the `storage_class` variable is set automatically (defaults to `gp2` on AWS, `linode-block-storage-retain` on Linode).

##### Multi-Node Scheduling & RWX StorageClasses

By default, all four PVCs (`mcserver`, `webappData`, `alertManagerData`, `backups`) use `ReadWriteOnce` (RWO). An RWO volume can only be mounted read-write by pods on a single node at a time, so the chart uses pod affinity (`preferredDuringSchedulingIgnoredDuringExecution`) to encourage `minecraft-wrapper`, `webapp`, and `backup-manager` to co-locate on the same node as the `mcserver` PVC. This is a *preference*, not a requirement — on a resource-constrained multi-node cluster, the scheduler can still place these pods on different nodes, which will leave the non-co-located pod's volume mount stuck.

If you run a multi-node cluster and want to remove this constraint entirely, switch the relevant PVC(s) to `ReadWriteMany` (RWX) with a StorageClass that supports it:

```bash
helm install omcsi ./helm/omcsi --namespace omcsi --create-namespace \
  --set secrets.rconPassword=changeme \
  --set secrets.adminPassword=strongpassword \
  --set persistence.mcserver.accessMode=ReadWriteMany \
  --set persistence.mcserver.storageClass=nfs-client
```

**RWX-capable StorageClass options:**

| Option | Notes |
|---|---|
| NFS (e.g. [`nfs-subdir-external-provisioner`](https://github.com/kubernetes-sigs/nfs-subdir-external-provisioner)) | Simplest self-hosted option; works on any cluster with an NFS server reachable from all nodes |
| AWS EFS (via the [`aws-efs-csi-driver`](https://github.com/kubernetes-sigs/aws-efs-csi-driver)) | Managed RWX storage on EKS; requires the EFS CSI driver add-on and an EFS file system |
| [Longhorn](https://longhorn.io/) | Self-hosted distributed block storage with RWX support; works on any cluster (bare metal, Hetzner, etc.) |

This is a Kubernetes-only concern — Docker Compose runs all services on a single host, so there is no equivalent scheduling constraint to document there.

RWX also unblocks horizontal autoscaling for `webapp` and `alert-manager` (see `webapp.autoscaling` / `alertManager.autoscaling` in `values.yaml`): their Deployment templates `fail` if autoscaling is enabled with more than 1 replica while their PVC(s) are still `ReadWriteOnce`.

**Troubleshooting**

| Symptom | Likely cause | Fix |
|---|---|---|
| Pods stuck in `ImagePullBackOff` | Images not pushed to a registry the cluster can access | Build and push images to your container registry; set image repository overrides via `--set` or `image_registry` in Terraform (see [Building and Pushing Images](#building-and-pushing-images)) |
| PVC stuck in `Pending` / "unbound immediate PersistentVolumeClaims" | No default StorageClass on the cluster | Set `persistence.*.storageClass` to a valid StorageClass for your provider (see [Storage Classes](#storage-classes)) |
| `VolumeBinding: context deadline exceeded` | EBS CSI driver add-on not installed (required on EKS 1.23+) | The AWS Terraform module installs the EBS CSI driver automatically; if deploying manually, install the `aws-ebs-csi-driver` EKS add-on |
| Pods stuck in `Pending` (resources) | Insufficient CPU/memory on cluster nodes | Scale up worker nodes or reduce resource requests in `values.yaml` |
| Pods `CrashLoopBackOff` | Application startup failure | Check logs with `kubectl logs -n omcsi <pod-name>` |

#### Port Forwarding

Use `kubectl port-forward` to access OMCSI services on `localhost` without exposing them via a `LoadBalancer` or `NodePort`. This is useful for quick local testing or when your cluster doesn't have external access configured.

```bash
# Minecraft server – forward localhost:25565 to the minecraft-wrapper service
kubectl port-forward svc/omcsi-minecraft-wrapper -n omcsi 25565:25565
# Connect your Minecraft client to localhost:25565

# Web dashboard via nginx – forward localhost:8443 to the nginx HTTPS port
kubectl port-forward svc/omcsi-nginx -n omcsi 8443:443
# Open https://localhost:8443 in your browser

# Run port-forwards in the background by appending &
kubectl port-forward svc/omcsi-minecraft-wrapper -n omcsi 25565:25565 &
kubectl port-forward svc/omcsi-nginx -n omcsi 8443:443 &
```

> **Tip:** If port 25565 is already in use on your machine, pick a different local port: `kubectl port-forward svc/omcsi-minecraft-wrapper -n omcsi 25566:25565` and connect to `localhost:25566`.

#### Testing with Minikube

[Minikube](https://minikube.sigs.k8s.io/) provides a single-node Kubernetes cluster on your local machine, ideal for testing the Helm chart before deploying to a production cluster.

**1. Install prerequisites**

- [Minikube](https://minikube.sigs.k8s.io/docs/start/) (v1.30+)
- [kubectl](https://kubernetes.io/docs/tasks/tools/)
- [Helm](https://helm.sh/docs/intro/install/) 3.x
- [Docker](https://docs.docker.com/get-docker/) (used as the minikube driver and for building images)

**2. Start minikube**

```bash
# Start a minikube cluster with enough resources for OMCSI
minikube start --cpus=4 --memory=8192 --driver=docker
```

**3. Build images inside minikube**

Minikube runs its own Docker daemon. To make locally built images available without a registry, point your shell's Docker client at minikube's daemon:

```bash
# Configure your shell to use minikube's Docker daemon
eval $(minikube docker-env)

# Build all OMCSI images (these will be available inside the cluster)
docker build -t open-mc-server .
docker build -t open-mc-server-webapp ./web-app
docker build -t open-mc-server-nginx ./nginx
docker build -t open-mc-server-backup-manager ./backup-manager
docker build -t open-mc-server-alert-manager ./alert-manager
docker build -t open-mc-server-agent-manager ./agent-manager
```

> **Note:** The `open-mc-server` image builds Spigot from source, which can take 10–15 minutes on the first run.

**4. Install the Helm chart**

```bash
# Lint the chart first
helm lint helm/omcsi --set secrets.rconPassword=test --set secrets.adminPassword=test

# Install with imagePullPolicy=Never so Kubernetes uses the local images
helm install omcsi ./helm/omcsi --namespace omcsi --create-namespace \
  --set secrets.rconPassword=changeme \
  --set secrets.adminPassword=strongpassword \
  --set minecraftWrapper.image.pullPolicy=Never \
  --set webapp.image.pullPolicy=Never \
  --set nginx.image.pullPolicy=Never \
  --set backupManager.image.pullPolicy=Never \
  --set alertManager.image.pullPolicy=Never
```

**5. Verify the deployment**

```bash
# Watch pods come up
kubectl get pods -n omcsi -w

# Check all services
kubectl get svc -n omcsi

# View logs for a specific service
kubectl logs -n omcsi -l app.kubernetes.io/component=minecraft-wrapper -f

# Check PVCs are bound
kubectl get pvc -n omcsi
```

**6. Access services**

```bash
# Minecraft game port – get the NodePort URL
minikube service omcsi-minecraft-wrapper -n omcsi --url

# Web dashboard via nginx – get the LoadBalancer URL
# (minikube tunnel is required for LoadBalancer services)
minikube tunnel &
kubectl get svc -n omcsi omcsi-nginx
# Connect to the EXTERNAL-IP shown for the nginx service

# Alternatively, use port-forwarding for quick access
kubectl port-forward svc/omcsi-minecraft-wrapper -n omcsi 25565:25565 &
# Connect your Minecraft client to localhost:25565

kubectl port-forward svc/omcsi-nginx -n omcsi 8443:443 &
# Dashboard at https://localhost:8443
```

**7. Test persistence**

```bash
# Verify world data survives a pod restart
kubectl delete pod -n omcsi -l app.kubernetes.io/component=minecraft-wrapper
# Wait for the pod to restart, then check that the world data is still present
kubectl get pods -n omcsi -w
```

**8. Clean up**

```bash
# Uninstall the release
helm uninstall omcsi --namespace omcsi

# Delete PVCs (optional – removes all persistent data)
kubectl delete pvc --all -n omcsi

# Delete the namespace
kubectl delete namespace omcsi

# Stop minikube
minikube stop

# (Optional) Delete the minikube cluster entirely
minikube delete
```

**Troubleshooting**

| Symptom | Likely cause | Fix |
|---|---|---|
| Pods stuck in `ImagePullBackOff` | Images not built in minikube's Docker | Re-run `eval $(minikube docker-env)` and rebuild images; ensure `imagePullPolicy` is `Never` or `IfNotPresent` |
| Pods stuck in `Pending` | Insufficient CPU/memory in minikube | Restart minikube with more resources: `minikube start --cpus=4 --memory=8192` |
| PVC stuck in `Pending` | No default storage class | Minikube ships with a default `StorageClass`; verify with `kubectl get sc` |
| Cannot reach services | Minikube tunnel not running | Run `minikube tunnel` for `LoadBalancer` services, or use `minikube service <name> -n omcsi` for `NodePort` |
| Pods `CrashLoopBackOff` | Application startup failure | Check logs with `kubectl logs -n omcsi <pod-name>` |

#### Deploying to Hetzner Cloud (cheapest, single node)

The [`terraform/hetzner/`](terraform/hetzner/) directory contains Terraform configuration that provisions a **single Hetzner Cloud server**, bootstraps a self-managed single-node Kubernetes cluster on it with `kubeadm`, and deploys the OMCSI Helm chart — in one `terraform apply`. It is the **lowest-cost cloud option (~$14/month on the default `cax31`)** because it carries none of the managed-Kubernetes charges:

- **No control-plane fee** — the control plane runs on the same node (untainted so workloads schedule on it)
- **No cloud LoadBalancer** — services are exposed via fixed NodePorts (25565/80/443) on the node's public IP (the API server's `--service-node-port-range` is widened to `80-32767`)
- **No NAT gateway** — the node has a public IP directly
- **No separate block storage** — the [Rancher local-path provisioner](https://github.com/rancher/local-path-provisioner) backs PVCs with the node's included NVMe

The cluster uses **containerd**, **Calico** (so the chart's NetworkPolicies are enforced), and Helm. See [Cost Analysis](terraform/COST_ANALYSIS.md) for the full comparison.

> **Trade-off:** You self-manage the cluster (upgrades, etcd backups, node maintenance) and there is no HA — this is the explicit deal for the lower price. The setup maps directly onto CKA-style skills (kubeadm, CNI, taints, NodePort ranges, StorageClasses).

**Prerequisites**
- [Terraform](https://developer.hashicorp.com/terraform/install) >= 1.3
- A [Hetzner Cloud API token](https://docs.hetzner.com/cloud/api/getting-started/generating-api-token/) (Read & Write)
- An SSH key pair (Terraform bootstraps and deploys over SSH)

**Quick Start**

```bash
cd terraform/hetzner

# Copy the example tfvars and fill in your values
cp terraform.tfvars.example terraform.tfvars
# Edit terraform.tfvars – set hcloud_token, ssh_public_key, ssh_private_key_path,
# rcon_password, and admin_password at minimum

terraform init
terraform plan
terraform apply
```

`terraform apply` prints the connection details when it finishes:

```bash
terraform output minecraft_address   # e.g. 203.0.113.10:25565 – connect your client here
terraform output dashboard_url       # https://203.0.113.10 – self-signed cert (expect a warning)
terraform output kubectl_hint        # export KUBECONFIG=... && kubectl get pods -n omcsi
```

> **First-boot time:** The server spends several minutes on first boot bootstrapping Kubernetes (cloud-init) before Helm deploys. Terraform waits for this automatically. Watch progress with `ssh -i <key> root@<ip> 'tail -f /var/log/omcsi-bootstrap.log'`.

**Key Variables**

| Variable | Description | Default |
|---|---|---|
| `hcloud_token` | Hetzner Cloud API token | *(required)* |
| `ssh_public_key` | SSH public key contents added to the server | *(required)* |
| `ssh_private_key_path` | Path to the matching private key (used for provisioning) | *(required)* |
| `rcon_password` / `admin_password` | OMCSI secrets | *(required)* |
| `server_type` | Hetzner server type | `cax31` (ARM, 16 GB) |
| `location` | Hetzner location (CAX/ARM is EU-only: `fsn1`/`nbg1`/`hel1`) | `fsn1` |
| `allowed_ssh_cidr` | CIDR allowed to reach SSH (22) and the K8s API (6443) | `0.0.0.0/0` *(restrict this)* |
| `kubernetes_version` | Kubernetes minor version | `1.34` |
| `java_opts` | Minecraft JVM heap (sized for 16 GB) | `-Xmx6G -Xms4G` |
| `image_registry` | Container image registry prefix | `dmccoystephenson` |

See [`terraform/hetzner/variables.tf`](terraform/hetzner/variables.tf) for the full list (operator identity, MOTD, Discord, agent-manager, NodePort overrides, etc.).

> **ARM images:** `cax31` is ARM64. The default `dmccoystephenson` images are published multi-arch (amd64 + arm64), so they run as-is. For an x86 server, set `server_type = "cpx31"` and `location` to any region.

**Tear Down**

```bash
terraform destroy
```

This removes the server, firewall, and SSH key. **All world data on the node is destroyed** — back up first (the backup-manager writes to a PVC on the node; copy it off before destroying).

#### Deploying to Linode with Terraform

The [`terraform/linode/`](terraform/linode/) directory contains Terraform configuration to provision a [Linode Kubernetes Engine (LKE)](https://www.linode.com/products/kubernetes/) cluster and deploy the OMCSI Helm chart in a single `terraform apply`.

**Prerequisites**
- [Terraform](https://developer.hashicorp.com/terraform/install) >= 1.3
- A [Linode API token](https://cloud.linode.com/profile/tokens) with read/write access to Kubernetes

**Quick Start**

```bash
cd terraform/linode

# Copy the example tfvars and fill in your values
cp terraform.tfvars.example terraform.tfvars
# Edit terraform.tfvars – set linode_token, rcon_password, admin_password at minimum

terraform init
terraform plan
terraform apply
```

After `apply` completes, a `kubeconfig.yaml` is written to the `terraform/linode/` directory. Use it to interact with the cluster:

```bash
export KUBECONFIG=$(pwd)/kubeconfig.yaml
kubectl get pods -n omcsi
```

**Variables**

| Variable | Description | Default |
|---|---|---|
| `linode_token` | Linode API personal access token | *(required)* |
| `cluster_label` | Label for the LKE cluster | `omcsi` |
| `region` | Linode region | `us-east` |
| `k8s_version` | Kubernetes version | `1.34` |
| `node_type` | Linode instance type for workers | `g6-standard-4` |
| `node_count` | Number of worker nodes | `2` |
| `rcon_password` | RCON password for Minecraft server | *(required)* |
| `admin_password` | Admin password for web dashboard | *(required)* |
| `image_registry` | Container image registry prefix (e.g., `your-dockerhub-user`) | `dmccoystephenson` |
| `storage_class` | Kubernetes StorageClass for PVCs | `linode-block-storage-retain` |
| `node_pool_autoscaler` | Autoscaler config object (`enabled`, `min`, `max`) | `{enabled=false, min=2, max=4}` |
| `minecraft_service_type` | Kubernetes Service type for the Minecraft port (`NodePort`, `LoadBalancer`, or `ClusterIP`) | `NodePort` |
| `nginx_service_type` | Kubernetes Service type for nginx (`LoadBalancer` or `ClusterIP` when using Traefik) | `LoadBalancer` |
| `enable_traefik` | Deploy Traefik as a single-IP ingress for Minecraft, HTTP, and HTTPS | `false` |
| `discord_webhook_url` | Discord webhook URL for alert-manager (auto-enables Discord when set) | `""` |
| `deploy_auth_token` | Bearer token for the plugin hot-deploy endpoint | `""` |
| `agent_manager_enabled` | Enable the Discord AI bot | `false` |
| `helm_values_file` | Path to additional Helm values file | `""` |

See [`terraform/linode/variables.tf`](terraform/linode/variables.tf) for the full list including agent-manager options.

> **Note:** Images default to Docker Hub under `dmccoystephenson`. Override `image_registry` if using a custom registry (see [Building and Pushing Images](#building-and-pushing-images)).

**Tear Down**

```bash
terraform destroy
```

This removes the LKE cluster, all Kubernetes resources, and associated Linode infrastructure.

#### Deploying to AWS with Terraform

The [`terraform/aws/`](terraform/aws/) directory contains Terraform configuration to provision an [Amazon EKS](https://aws.amazon.com/eks/) cluster (with VPC, subnets, and managed node group) and deploy the OMCSI Helm chart.

**Prerequisites**
- [Terraform](https://developer.hashicorp.com/terraform/install) >= 1.3
- [AWS CLI v2](https://docs.aws.amazon.com/cli/latest/userguide/getting-started-install.html) configured (`aws configure`)
- An AWS account with permissions to create EKS clusters, VPCs, IAM roles, and EC2 instances

**Quick Start**

```bash
cd terraform/aws

# Copy the example tfvars and fill in your values
cp terraform.tfvars.example terraform.tfvars
# Edit terraform.tfvars – set rcon_password, admin_password at minimum

terraform init
terraform plan
terraform apply
```

After `apply` completes, configure `kubectl` to talk to the new cluster:

```bash
aws eks update-kubeconfig --region us-east-1 --name omcsi
kubectl get pods -n omcsi
```

**Variables**

| Variable | Description | Default |
|---|---|---|
| `aws_region` | AWS region for the EKS cluster | `us-east-1` |
| `cluster_name` | Name for the EKS cluster | `omcsi` |
| `cluster_version` | Kubernetes version | `1.34` |
| `node_instance_type` | EC2 instance type for workers | `t3.large` |
| `node_desired_count` | Desired number of worker nodes | `2` |
| `node_min_count` | Minimum workers (autoscaling) | `1` |
| `node_max_count` | Maximum workers (autoscaling) | `4` |
| `rcon_password` | RCON password for Minecraft server | *(required)* |
| `admin_password` | Admin password for web dashboard | *(required)* |
| `image_registry` | Container image registry prefix (e.g., `your-dockerhub-user`) | `dmccoystephenson` |
| `storage_class` | Kubernetes StorageClass for PVCs | `gp2` |
| `minecraft_service_type` | Kubernetes Service type for the Minecraft port (`NodePort`, `LoadBalancer`, or `ClusterIP`) | `NodePort` |
| `nginx_service_type` | Kubernetes Service type for nginx (`LoadBalancer` or `ClusterIP` when using Traefik) | `LoadBalancer` |
| `enable_traefik` | Deploy Traefik as a single-IP ingress for Minecraft, HTTP, and HTTPS | `false` |
| `discord_webhook_url` | Discord webhook URL for alert-manager (auto-enables Discord when set) | `""` |
| `deploy_auth_token` | Bearer token for the plugin hot-deploy endpoint | `""` |
| `agent_manager_enabled` | Enable the Discord AI bot | `false` |
| `helm_values_file` | Path to additional Helm values file | `""` |

See [`terraform/aws/variables.tf`](terraform/aws/variables.tf) for the full list including agent-manager options.

> **Note:** Images default to Docker Hub under `dmccoystephenson`. Override `image_registry` if using a custom registry (see [Building and Pushing Images](#building-and-pushing-images)).

**Tear Down**

```bash
terraform destroy
```

This removes the EKS cluster, node group, VPC, IAM roles, and all Kubernetes resources.

#### Deploying to an Existing Cluster with Terraform

The [`terraform/existing-cluster/`](terraform/existing-cluster/) directory contains Terraform configuration to deploy the OMCSI Helm chart to **any existing Kubernetes cluster** — no cluster provisioning required. This is useful when you already have a cluster (e.g., k3s, minikube, GKE, on-premise) and just want to deploy the application.

**Prerequisites**
- [Terraform](https://developer.hashicorp.com/terraform/install) >= 1.3
- A kubeconfig file with access to the target cluster (default: `~/.kube/config`)

**Quick Start**

```bash
cd terraform/existing-cluster

# Copy the example tfvars and fill in your values
cp terraform.tfvars.example terraform.tfvars
# Edit terraform.tfvars – set rcon_password, admin_password at minimum

terraform init
terraform plan
terraform apply
```

By default the module uses `~/.kube/config` with the current context. To target a specific kubeconfig or context:

```bash
terraform apply \
  -var kubeconfig_path="/path/to/kubeconfig.yaml" \
  -var kubeconfig_context="my-cluster-context" \
  -var rcon_password=changeme \
  -var admin_password=strongpass
```

After `apply` completes, verify the deployment:

```bash
kubectl get pods -n omcsi
```

**Variables**

| Variable | Description | Default |
|---|---|---|
| `kubeconfig_path` | Path to the kubeconfig file | `~/.kube/config` |
| `kubeconfig_context` | Context to use (empty = current-context) | `""` |
| `rcon_password` | RCON password for Minecraft server | *(required)* |
| `admin_password` | Admin password for web dashboard | *(required)* |
| `image_registry` | Container image registry prefix (e.g., `your-dockerhub-user`) | `dmccoystephenson` |
| `storage_class` | Kubernetes StorageClass for PVCs (empty = cluster default) | `""` |
| `minecraft_service_type` | Kubernetes Service type for the Minecraft port (`NodePort`, `LoadBalancer`, or `ClusterIP`) | `NodePort` |
| `nginx_service_type` | Kubernetes Service type for nginx (`LoadBalancer` or `ClusterIP` when using Traefik) | `LoadBalancer` |
| `enable_traefik` | Deploy Traefik as a single-IP ingress for Minecraft, HTTP, and HTTPS | `false` |
| `discord_webhook_url` | Discord webhook URL for alert-manager (auto-enables Discord when set) | `""` |
| `deploy_auth_token` | Bearer token for the plugin hot-deploy endpoint | `""` |
| `agent_manager_enabled` | Enable the Discord AI bot | `false` |
| `helm_values_file` | Path to additional Helm values file | `""` |

See [`terraform/existing-cluster/variables.tf`](terraform/existing-cluster/variables.tf) for the full list including agent-manager options.

> **Note:** The `storage_class` variable defaults to empty (cluster default). If your cluster doesn't have a default StorageClass, set it explicitly (e.g., `local-path`, `gp2`, `standard`).

**Tear Down**

```bash
terraform destroy
```

This removes only the OMCSI Helm release and namespace — the cluster itself is not affected.

## Quick Start

1. **Clone the repository**
   ```bash
   git clone <your-repo-url>
   cd open-mc-server-infrastructure
   ```

2. **Configure the server**
   ```bash
   cp sample.env .env
   # Edit .env with your settings (see Configuration section)
   ```

3. **Start the server**
   ```bash
   chmod +x up.sh down.sh
   ./up.sh
   ```
   
   **Note**: The first build will take 10-15 minutes as it downloads and compiles Spigot from source. The JARs for all services will be built automatically during the Docker build process.

4. **Connect to your server**
   - Server address: `localhost:25565` (or your server's IP)
   - Web Dashboard: `https://localhost:8443` (or your server's IP with port 8443)
   - The server will take a few minutes to build on first run
   - **Note**: You'll see a security warning for the self-signed certificate. This is expected for development. See the Security section for production setup.

## Web Dashboard

The server includes a built-in web dashboard that provides:

- **Server Status**: Real-time view of server status, player count, and MOTD
- **Admin Console**: Send commands to the server using RCON
- **External Links**: Quick access to Dynmap, BlueMap, Accordion Chat, and other services
- **Activity Tracker Integration**: View player statistics and leaderboards (optional)
- **Secure Access**: HTTPS encryption with reverse proxy to protect credentials

Access the dashboard at `https://localhost:8443` (or your configured `WEB_HTTPS_PORT`). HTTP requests to port 8080 (or `WEB_HTTP_PORT`) will automatically redirect to HTTPS.

### SSL Certificates

The server uses self-signed SSL certificates by default for development. When you first access the web dashboard, your browser will show a security warning. This is expected and safe for local development.

**For production use**, replace the self-signed certificates with certificates from a trusted Certificate Authority. Certificates live in the `nginx-ssl` named Docker volume (mounted at `/etc/nginx/ssl` inside the `nginx` container), not a host directory, so copy them in with `docker cp` after the stack is running:

1. Obtain SSL certificates (e.g., from [Let's Encrypt](https://letsencrypt.org/))
2. Start the stack so the `nginx-ssl` volume exists: `./up.sh`
3. Copy the certificate and key into the running container:
   ```bash
   docker cp fullchain.pem open-mc-nginx:/etc/nginx/ssl/cert.pem
   docker cp privkey.pem open-mc-nginx:/etc/nginx/ssl/key.pem
   ```
4. Restart nginx to pick up the new certificate: `docker compose restart nginx`

Alternatively, you can generate new self-signed certificates locally and copy them in the same way:
```bash
./scripts/generate-ssl-certs.sh
docker cp nginx/ssl/cert.pem open-mc-nginx:/etc/nginx/ssl/cert.pem
docker cp nginx/ssl/key.pem open-mc-nginx:/etc/nginx/ssl/key.pem
docker compose restart nginx
```

### Activity Tracker Integration

The web dashboard can optionally integrate with the [Activity Tracker plugin](https://github.com/Dans-Plugins/Activity-Tracker) to display player statistics and leaderboards. When enabled, the dashboard will show:

- **Server Statistics**: Number of unique players and total logins
- **Player Leaderboard**: Top 10 players ranked by hours played, with total logins

To enable Activity Tracker integration:

1. Install the Activity Tracker plugin on your Minecraft server
2. Configure the plugin to enable its REST API (see plugin documentation)
3. Set the following environment variables in your `.env` file:
   ```bash
   ACTIVITY_TRACKER_ENABLED=true
   ACTIVITY_TRACKER_URL=http://localhost:8080
   ```
4. Restart the web application with `./up.sh`

The Activity Tracker data will automatically refresh with the server status updates. If the Activity Tracker API is not available, the sections will be hidden without affecting other dashboard functionality.

### Accordion Chat Integration

The infrastructure supports integration with [Accordion Chat](https://github.com/Stephenson-Software/accordion), a real-time web-based chat application. When configured, players and administrators can communicate through a modern web interface accessible from the dashboard.

**Accordion Chat runs as a separate application** to avoid duplication and ensure updates can be made to Accordion independently of the infrastructure project.

To enable Accordion Chat integration:

1. **Initialize and run the Accordion Chat submodule**:
   ```bash
   # Initialize the accordion-chat submodule
   git submodule update --init accordion-chat
   cd accordion-chat
   # Follow the Accordion setup instructions in its README
   docker compose up -d
   ```

2. **Configure the web dashboard** to link to your running Accordion instance by setting the following in your `.env` file:
   ```bash
   ACCORDION_CHAT_URL=http://localhost:3000
   ```
   
   For accessing from other machines on your network, use your server's IP address:
   ```bash
   ACCORDION_CHAT_URL=http://192.168.1.100:3000
   ```

3. **Restart the infrastructure services** to apply the configuration:
   ```bash
   ./up.sh
   ```

Once configured, a "Chat" link will appear in the web dashboard's External Services section pointing to your Accordion Chat instance.

**Note**: For production use with persistent storage and other configuration options, refer to the [Accordion Chat documentation](https://github.com/Stephenson-Software/accordion).

## Configuration

Copy `sample.env` to `.env` and modify the following settings:

### Essential Settings
- `OPERATOR_UUID`: Your Minecraft player UUID (get from [mcuuid.net](https://mcuuid.net/))
- `OPERATOR_NAME`: Your Minecraft username
- `SERVER_MOTD`: Message displayed in the server list
- `MAX_PLAYERS`: Maximum number of players allowed

**Note**: If `OPERATOR_UUID` and `OPERATOR_NAME` are not properly configured, the server will still start but you'll need to manually add operators using the `op <username>` command in the server console.

### Server Settings
- `DIFFICULTY`: Server difficulty (peaceful, easy, normal, hard)
- `GAMEMODE`: Default game mode (survival, creative, adventure, spectator)
- `PVP_ENABLED`: Enable/disable player vs player combat
- `ONLINE_MODE`: Enable Mojang authentication (set to false for offline/cracked servers)
- `DEFAULT_PLUGINS`: Comma-separated list of direct download URLs to plugin JARs, installed automatically into `PLUGINS_DIRECTORY` on server setup. A plugin already present there (matched by filename) is left untouched, so it's safe to leave alongside manually or CI-deployed plugins. Leave empty to skip (default).

### Docker Configuration (for Parallel Servers)

These settings allow you to run multiple server instances in parallel without conflicts:

- `CONTAINER_NAME`: Docker container name (default: `open-mc-server`)
- `HOST_PORT`: Host port for Minecraft server (default: `25565`)
- `HOST_RCON_PORT`: Host port for RCON (default: `25575`)
- `HOST_BLUEMAP_PORT`: Host port for BlueMap (default: `8100`)
- `WRAPPER_PORT`: Host port for the minecraft-wrapper REST API (default: `8092`)
- `VOLUME_NAME`: Docker volume name for persistent data (default: `mcserver`)

To run multiple servers simultaneously (e.g., for testing different configurations), create separate `.env` files with unique values for `CONTAINER_NAME`, `HOST_PORT`, `HOST_RCON_PORT`, `HOST_BLUEMAP_PORT`, `WRAPPER_PORT`, `VOLUME_NAME`, `WEB_CONTAINER_NAME`, `NGINX_CONTAINER_NAME`, `BACKUP_CONTAINER_NAME`, `ALERT_CONTAINER_NAME`, `BACKUP_PORT`, `ALERT_PORT`, `AGENT_PORT`, `WEB_HTTP_PORT`, and `WEB_HTTPS_PORT`, then start each with:

```bash
docker compose --env-file .env.dev2 up -d --build
```

### Web Dashboard Configuration

- `WEB_CONTAINER_NAME`: Web application container name (default: `open-mc-webapp`)
- `NGINX_CONTAINER_NAME`: Nginx reverse proxy container name (default: `open-mc-nginx`)
- `WEB_HTTP_PORT`: HTTP port (redirects to HTTPS, default: `8080`)
- `WEB_HTTPS_PORT`: HTTPS port (default: `8443`)
- `RCON_PASSWORD`: Password for RCON authentication (default: `minecraft`)
- `ADMIN_USERNAME`: Username for admin console authentication (default: `admin`)
- `ADMIN_PASSWORD`: Password for admin console authentication (default: `admin`)
- `DYNMAP_URL`: URL to Dynmap web interface (optional)
- `BLUEMAP_URL`: URL to BlueMap web interface (optional)
- `ACCORDION_CHAT_URL`: URL to Accordion Chat web interface (optional, e.g., `http://localhost:3000`). Accordion runs separately - see Accordion Chat Integration section.
- `ACTIVITY_TRACKER_URL`: URL to Activity Tracker plugin REST API (optional, e.g., `http://localhost:8080`)
- `ACTIVITY_TRACKER_ENABLED`: Enable Activity Tracker integration (default: `false`)

**Note**: The RCON password must match between the server and web application for admin commands to work. Change the admin username and password from defaults in production for security. All connections to the web dashboard are encrypted using HTTPS to protect your credentials.

#### Upload Size Limits

Plugin JARs and world archives are uploaded through the nginx reverse proxy, so the proxy limit is the effective cap — a larger request is rejected with a bare `413 Request Entity Too Large` before the dashboard's own error handling runs.

A world upload passes four limits in series, and the smallest one is what a client actually hits:

| Setting | Applies to | Default |
|---|---|---|
| `NGINX_MAX_BODY_SIZE` | Request body accepted by the proxy | `100M` |
| `MAX_FILE_UPLOAD_SIZE` / `MAX_REQUEST_UPLOAD_SIZE` | Spring multipart limits, in **both** the web app and the wrapper | `2048MB` |
| `WORLD_UPLOAD_MAX_FILE_SIZE_MB` | The wrapper's own check on the archive | `2048` |
| `WORLD_UPLOAD_MAX_EXTRACTED_MB` | Total extracted size — zip bomb protection | `10240` |

`WORLD_UPLOAD_MAX_ENTRIES` (default `100000`) caps the entry count as further zip bomb protection.

To accept larger archives, raise all of them together. Note the suffixes differ: nginx uses `5G`, Spring uses `5120MB`, and the `WORLD_UPLOAD_*` values are plain MB. Raising only one silently leaves the effective cap where it was — and when `NGINX_MAX_BODY_SIZE` is the one that's too low, the rejection is a bare `413 Request Entity Too Large` with no dashboard error at all.

Plugin uploads are written directly by the web app and are subject only to the first two rows.

**Timeouts.** A large upload takes minutes to transfer, and the wrapper does not respond until it has also stopped the server, extracted the archive, swapped the world in and restarted — so the request outlives nginx's ordinary 60s proxy timeout. `NGINX_UPLOAD_TIMEOUT` (default `3600s`) is applied to the upload routes only, leaving the rest of the dashboard at 60s, and `WORLD_UPLOAD_READ_TIMEOUT_SECONDS` (default `600`) is how long the web app waits on the wrapper. Raise both alongside the size limits; keep the web app's value at or below nginx's.

**Buffering.** The upload routes run with `proxy_request_buffering off`, so nginx streams the body through instead of spooling the whole archive to disk first. Each service still buffers the multipart body itself: `MULTIPART_TEMP_DIR` (empty by default) selects where. Empty means the container's own writable layer, which a multi-GB archive can exhaust — point it at a path on the mcserver volume (e.g. `/mcserver/tmp`) before accepting uploads that large. The directory is created at startup if missing.

**A worked example — accepting a 5 GB world archive:**

```bash
NGINX_MAX_BODY_SIZE=5G
MAX_FILE_UPLOAD_SIZE=5120MB
MAX_REQUEST_UPLOAD_SIZE=5120MB
WORLD_UPLOAD_MAX_FILE_SIZE_MB=5120
WORLD_UPLOAD_MAX_EXTRACTED_MB=30720   # worlds expand several times over
MULTIPART_TEMP_DIR=/mcserver/tmp
NGINX_UPLOAD_TIMEOUT=7200s
WORLD_UPLOAD_READ_TIMEOUT_SECONDS=7200
```

On Kubernetes the same values are set through the Helm chart, or through the Terraform variables that drive it — see below.

On Kubernetes the equivalent chart values are `nginx.maxBodySize`, `nginx.uploadTimeout`, the `MAX_*_UPLOAD_SIZE` / `MULTIPART_TEMP_DIR` / `WORLD_UPLOAD_*` entries under `minecraftWrapper.env`, and `MAX_*_UPLOAD_SIZE` / `MULTIPART_TEMP_DIR` / `WORLD_UPLOAD_READ_TIMEOUT_SECONDS` under `webapp.env`, all in `helm/omcsi/values.yaml`.

The Hetzner Terraform module derives every one of them from a single variable so they cannot drift apart — set `TF_VAR_world_upload_max_size_mb` and nginx's cap, both services' multipart limits, and the wrapper's own check all move together. `world_upload_max_extracted_mb`, `world_upload_max_entries`, `world_upload_timeout_seconds`, `multipart_temp_dir`, and `mcserver_storage_size` are exposed alongside it. Note that the module aligns `nginx.maxBodySize` with the app-side limits rather than leaving it at the chart's `100M`, so a Hetzner deployment does not silently cap uploads below what the services advertise.

**Disk headroom for world uploads**: a world archive is extracted into a staging directory next to the world directory — on the same volume — before being renamed into place, because a rename cannot cross filesystems. The server volume therefore needs enough free space to hold the old world and the extracted new one at the same time, roughly twice the size of the larger of the two. Plan for this when sizing the volume (`persistence.mcserver.size` on Kubernetes, default `10Gi`); a world upload that runs out of space fails and leaves the existing world in place. The extracted size is capped at 10 GB regardless.

### Backup Manager Configuration

- `BACKUP_CONTAINER_NAME`: Backup manager container name (default: `open-mc-backup-manager`)
- `BACKUP_MAX_SIZE_MB`: Maximum size of backups directory in MB (default: `10240` = 10GB)
- `BACKUP_SCHEDULE`: Cron expression for backup schedule (default: `0 0 2 * * ?` = 2 AM daily). Set to `-` to disable scheduled backups entirely; manual backups via `POST /api/backups/trigger` and `trigger-backup.sh` keep working.

See [backup-manager/README.md](backup-manager/README.md) for detailed cron expression examples and configuration.

### Alert Manager Configuration

- `ALERT_CONTAINER_NAME`: Alert manager container name (default: `open-mc-alert-manager`)
- `ALERT_PORT`: Alert manager API port (default: `8090`)
- `ALERT_MANAGER_URL`: Alerts endpoint the other services POST to (default: `http://alert-manager:8090/api/alerts`). Docker Compose only — one `.env` value shared by minecraft-wrapper, the web app, backup-manager and agent-manager, all of which use it verbatim, so it must include the `/api/alerts` path. On Kubernetes the Helm chart derives it per service from the in-cluster alert-manager Service.
- `DISCORD_WEBHOOK_URL`: Discord webhook URL for sending notifications (optional)
- `DISCORD_ENABLED`: Enable/disable Discord notifications (default: `false`)

**Alert Toggles** - Fine-grained control over which events trigger alerts:
- `ALERTS_SERVER_START`: Alert when server starts (default: `true`)
- `ALERTS_SERVER_STOP`: Alert when server stops gracefully (default: `true`)
- `ALERTS_SERVER_CRASH`: Alert when server crashes unexpectedly (default: `true`)
- `ALERTS_BACKUP_SUCCESS`: Alert when backup completes successfully (default: `true`)
- `ALERTS_BACKUP_FAILURE`: Alert when backup fails (default: `true`)
- `ALERTS_PLUGIN_DEPLOY`: Alert on plugin deployment success or failure (default: `true`)
- `ALERTS_WORLD_UPLOAD`: Alert on world upload success or failure (default: `true`)
- `ALERTS_CONFIG_WARNING`: Alert when server starts with configuration warnings (default: `false`)

The following three toggles are read by `upgrade.sh`, which runs on the Docker host and drives
`docker compose` directly. They apply to the **Docker Compose deployment only** and have no
Kubernetes equivalent, so the Helm chart does not expose them:
- `ALERTS_UPGRADE_START`: Alert when upgrade process begins (default: `true`)
- `ALERTS_UPGRADE_COMPLETE`: Alert when the upgrade process finishes — either confirmed successful or with startup unverified from logs (default: `true`)
- `ALERTS_UPGRADE_FAILURE`: Alert when upgrade fails (default: `true`)

`rollback.sh` (also Docker Compose only) sends a completion alert on success and an `ERROR` alert if the restore fails partway through, both unconditionally — they aren't gated by a toggle, since a rollback is a rare, explicitly-confirmed action.

To enable Discord notifications:
1. Create a webhook in your Discord server (Server Settings → Integrations → Webhooks)
2. Copy the webhook URL and add it to your `.env` file
3. Set `DISCORD_ENABLED=true`

The alert manager API is accessible on the configured port (default: 8090) for testing and integration from the host machine.

See [alert-manager/README.md](alert-manager/README.md) for detailed configuration and usage examples.

### Agent Manager Configuration

- `AGENT_CONTAINER_NAME`: Agent manager container name (default: `open-mc-agent-manager`)
- `AGENT_PORT`: Agent manager API port (default: `8093`). The Spring Boot management/actuator endpoint also listens on `8094` inside the container but is not published to the host by default — see `agent-manager/README.md` for details.
- `AGENT_DISCORD_BOT_TOKEN`: Discord bot token (required for agent manager)
- `AGENT_DISCORD_CHANNEL_ID`: Discord channel ID to listen on (required for agent manager)
- `AGENT_ANTHROPIC_API_KEY`: Anthropic API key (required for agent manager)
- `AGENT_ANTHROPIC_MODEL`: Anthropic model used for every Messages API call (default: `claude-sonnet-4-20250514`)
- `AGENT_ANTHROPIC_MAX_TOKENS`: Maximum output tokens per Anthropic response (default: `1024`). Raise this if answers summarising `get_server_diagnostics` output are being cut short; lower it to cap per-response API spend.
- `AGENT_ENABLED`: Enable/disable the agent manager (default: `false`)
- `AGENT_START_SERVER_REQUIRES_CONFIRMATION`: Require confirmation to start server (default: `true`)
- `AGENT_STOP_SERVER_REQUIRES_CONFIRMATION`: Require confirmation to stop server (default: `true`)
- `AGENT_RESTART_SERVER_REQUIRES_CONFIRMATION`: Require confirmation to restart server (default: `true`)
- `AGENT_TRIGGER_BACKUP_REQUIRES_CONFIRMATION`: Require confirmation to trigger a backup (default: `true`)

See [agent-manager/README.md](agent-manager/README.md) for detailed configuration, Discord bot setup, and usage examples.

#### AI Diagnostic Feature

The agent uses the `get_server_diagnostics` tool to answer open-ended health questions by
gathering context from multiple sources in a single pass — server status, recent alerts, the
latest backup result, live server performance metrics (e.g. JVM heap usage and TPS), and,
when enabled, recent **sanitized** log snippets controlled via the `diagnostics.logs.*`
configuration toggles — then synthesising a natural language summary.

**Example interaction:**

> **User:** What happened while I was offline? Is everything okay?

> **Agent:** The server has been running for 6 hours with 3 players online. There was a crash alert at 2:14 AM, after which the server restarted automatically. The last backup completed successfully at 2:00 AM, just before the crash. Current performance metrics (TPS and heap usage) look healthy. If sanitized diagnostic logs are enabled via `diagnostics.logs.*`, I can also summarise any recent errors around 2:14 AM; otherwise, you may want to review the server logs directly to understand the crash cause.

This differs from a simple `/status` command because the agent **reasons** over the combined data rather than just returning a single API response. If any upstream source is unavailable the agent acknowledges the gap explicitly (e.g. "I wasn't able to reach the backup manager, but based on server status and recent alerts…"). Operators can control whether recent logs are included in diagnostics (and how they are anonymised) using the `diagnostics.logs.*` privacy toggles.


## Management

### Starting the Server
```bash
./up.sh
```
or
```bash
docker compose up -d --build
```

### Stopping the Server
```bash
./down.sh
```
or
```bash
docker compose down
```

**Note**: The server includes graceful shutdown handling that automatically warns players before stopping. When a shutdown is initiated, players will receive countdown warnings at 30, 20, 10, and 5 seconds before the server stops. The server then sends the "stop" command to Minecraft, ensuring that plugins save their data properly and preventing data loss that could occur with an abrupt termination. The Docker Compose configuration includes a 45-second grace period to allow sufficient time for the warning sequence and graceful shutdown to complete.

### Viewing Server Logs
```bash
docker logs -f open-mc-server
```

**Note**: Replace `open-mc-server` with your `CONTAINER_NAME` value if you've customized it.

## File Management

### Backup Server Data

#### Automated Scheduled Backups (Recommended)

The infrastructure includes a **backup-manager** service that automatically backs up the server data:

- **Automatic Scheduling**: Runs daily at 2 AM (configurable via `BACKUP_SCHEDULE` in `.env`)
- **Size Management**: Automatically removes old backups when the backup directory exceeds the configured size limit (default: 10GB)
- **Containerized**: Runs in its own container for isolation and reliability

To configure automated backups, set the following in your `.env` file:

```bash
# Maximum size of backups directory in MB (default: 10GB)
BACKUP_MAX_SIZE_MB=10240

# Backup schedule (cron expression, default: 2 AM daily)
BACKUP_SCHEDULE=0 0 2 * * ?
```

View backup-manager logs:
```bash
docker logs -f open-mc-backup-manager
```

See [backup-manager/README.md](backup-manager/README.md) for detailed configuration options.

#### Manual Backup

The backup-manager service automatically creates backups on a schedule (default: 2 AM daily). Backups are stored in the `backups` Docker volume (a named volume, not a host directory — see `BACKUPS_VOLUME_NAME` in `.env`) with timestamped names like `backup-20241211-020000`. List them with `./rollback.sh` (no arguments), or directly via `docker run --rm -v backups:/backups:ro alpine ls /backups`.

To trigger a manual backup immediately:

```bash
./trigger-backup.sh
```

This script uses the backup-manager REST API to create an immediate backup. Alternatively, you can restart the backup-manager container:

```bash
docker restart open-mc-backup-manager
```

Or use Docker commands to manually copy server data:

```bash
docker cp open-mc-server:/mcserver ./backup/
```

**Note**: Replace `open-mc-server` with your `CONTAINER_NAME` value if you've customized it.

### Restore Server Data
```bash
docker cp ./backup/ open-mc-server:/mcserver
docker compose restart
```

**Note**: Replace `open-mc-server` with your `CONTAINER_NAME` value if you've customized it.

### Deposit Box
The `deposit-box` directory is shared between your host system and the container at `/deposit-box`. Use it to transfer files to/from the server.

## Updating

### Automated Upgrade Script

The easiest way to upgrade your Minecraft server to a new version:

```bash
./upgrade.sh
```

This script automates the entire upgrade process:
- Stops the server gracefully
- Creates a timestamped backup automatically
- Prompts for the new version
- Updates configuration
- Rebuilds with the new version
- Starts the server

Preview an upgrade without making any changes with `./upgrade.sh --dry-run` (optionally `./upgrade.sh --dry-run <version>` to skip the version prompt). If an upgrade needs to be undone, `./rollback.sh` restores a previous backup and restarts the server — see [Rollback Procedure](UPGRADE-GUIDE.md#rollback-procedure) in the Upgrade Guide.

### Upgrade to a New Minecraft Version

For a comprehensive, step-by-step guide to upgrading your Minecraft server to a newer version with proper backup and rollback procedures, see the **[Upgrade Guide](UPGRADE-GUIDE.md)**.

The upgrade guide covers:
- Automated upgrade script usage (recommended)
- Manual step-by-step upgrade process
- Pre-upgrade backup procedures
- Rollback and restoration procedures
- Post-upgrade verification steps
- Troubleshooting common upgrade issues

### Quick Update (Without Version Change)

To update the container without changing the Minecraft version:

```bash
./down.sh
docker compose build --no-cache
./up.sh
```

## Troubleshooting

### Server Won't Start
- Check Docker logs: `docker logs open-mc-server` (use your `CONTAINER_NAME` value)
- Ensure all required environment variables are set
- Verify Docker and Docker Compose are installed

### Can't Connect to Server
- Ensure port 25565 is open/forwarded (or your custom `HOST_PORT` value)
- Check if `ONLINE_MODE` setting matches your client type
- Verify the server is running: `docker ps`

### Performance Issues
- Adjust memory allocation in `sample.env` by setting appropriate values
- Monitor system resources: `docker stats open-mc-server` (use your `CONTAINER_NAME` value)

## Security Notes

- **HTTPS Enabled**: All web dashboard connections are encrypted using HTTPS to protect admin credentials
- Change default operator settings in `.env`
- **Change default admin credentials**: Update `ADMIN_USERNAME` and `ADMIN_PASSWORD` in `.env`
- **Production SSL**: Replace self-signed certificates with trusted CA certificates (e.g., Let's Encrypt) for production
- Consider setting `ONLINE_MODE=true` for authentication
- Don't expose the server publicly without proper security measures
- Regularly backup your world data
- Keep `RCON_PASSWORD` secure and different from default values

**For comprehensive security guidance**, especially for public/home hosting, see the **[Self-Hosting Guide](SELF-HOSTING.md)** which covers:
- Firewall configuration (UFW, iptables, OPNsense, pfSense)
- DDoS protection and rate limiting
- Port forwarding best practices
- Network security hardening
- Advanced security configurations

## License

This project is licensed under the MIT License - see the [LICENSE](LICENSE) file for details.

## Development

### Modules

The infrastructure consists of several Spring Boot modules:

#### Alert Manager
Handles alert notifications to Discord and sends messages to Minecraft players via RCON.
- **Port**: 8090
- **Location**: `alert-manager/`
- **Documentation**: [alert-manager/README.md](alert-manager/README.md)

#### Backup Manager  
Automated backup service with scheduling and size management.
- **Port**: 8091
- **Location**: `backup-manager/`
- **Documentation**: [backup-manager/README.md](backup-manager/README.md)

#### Minecraft Wrapper
Spring Boot service providing testable, REST-accessible wrapper functionality for Minecraft server management. This module is integrated into the main Minecraft server container.
- **Location**: `minecraft-wrapper/`
- **Documentation**: [minecraft-wrapper/README.md](minecraft-wrapper/README.md)
- **Features**:
  - Unit-tested server lifecycle management
  - REST API for server status, commands, and messaging (port 8092)
  - Graceful shutdown with player warnings
  - Alert integration
  - Process management for Minecraft server

#### Web App
Spring Boot web dashboard for server management and monitoring.
- **Port**: 8080 (behind nginx proxy on 8443)
- **Location**: `web-app/`

#### Agent Manager
Discord-based server management agent powered by the Anthropic API. Users send natural language messages in a Discord channel to start, stop, or restart the Minecraft server.
- **Port**: 8093
- **Location**: `agent-manager/`
- **Documentation**: [agent-manager/README.md](agent-manager/README.md)
- **Features**:
  - Natural language Discord commands via Anthropic tool-use API
  - Start, stop, and restart server tools
  - Per-tool confirmation flow via Discord reactions
  - Requesting-user validation on confirmations
  - Disabled by default (`AGENT_ENABLED=false`)

### CI/CD Pipeline

This repository includes a comprehensive CI pipeline that automatically validates:

- **Shell Script Validation**: Syntax checking and ShellCheck linting for all bash scripts
- **Docker Configuration**: Validates Dockerfile and Docker Compose configurations
- **Environment Configuration**: Ensures all required environment variables are properly defined
- **Security Scanning**: Trivy security scanning for vulnerabilities
- **Server Run Testing**: Actually runs the Minecraft server to verify it starts, operates, and stops correctly
- **Integration Testing**: End-to-end validation of the complete setup

### Running Local CI Checks

Before submitting changes, you can run the same validation checks locally:

```bash
./scripts/ci-local.sh
```

This mirrors the CI pipeline to catch issues early: shell script syntax and
ShellCheck linting, Docker Compose configuration, environment and documentation
checks, `helm lint`, `helm unittest`, Terraform formatting and validation for
all four targets, and the Gradle test suite for every module. Checks whose tool
(ShellCheck, Helm, the helm-unittest plugin, Terraform) is not installed locally
are skipped with a warning and listed in the summary at the end of the run.

### CI Pipeline Status

The CI pipeline runs on:
- Every push to `main` and `develop` branches
- Every pull request to `main`

Check the [Actions tab](https://github.com/dmccoystephenson/open-mc-server-infrastructure/actions) for detailed CI results and logs.

## Contributing

Feel free to submit issues and enhancement requests!
