# Kubernetes Cost Analysis: Hetzner vs LKE (Linode) vs EKS (AWS)

This document summarizes the monthly cost difference between the supported Terraform deployment targets: a **self-managed single node on Hetzner Cloud** (`terraform/hetzner/`), Linode Kubernetes Engine (LKE), and AWS Elastic Kubernetes Service (EKS).

## TL;DR

OMCSI is a **single Minecraft server plus a handful of small Spring Boot services** — its real footprint is roughly 5 GB of RAM, not the 16 GB the managed-cluster defaults provision. Most of the cost on managed Kubernetes is *infrastructure tax* that the workload never asked for: a control-plane fee, a cloud LoadBalancer, a NAT gateway, and oversized nodes.

If you are willing to self-manage the cluster (kubeadm), a **single Hetzner CAX31 (the module's default) runs the whole stack for ~$28/month — ~4–9× cheaper than the managed options. A CAX21 (~$14/month, 8 GB) is the type that fits under the $20 target**, with a smaller JVM heap. Either one carries none of the managed-Kubernetes line items: no control-plane fee, no cloud LoadBalancer (services are exposed via NodePorts on the node's public IP), and no NAT gateway.

For equivalent *managed* workloads, **LKE is roughly 2–3× cheaper than EKS**, driven mainly by EKS's mandatory $73/month control-plane fee per cluster, which LKE does not charge.

| Deployment target | Control plane | What you manage | Est. total |
|---|---|---|---|
| **Hetzner CAX31 (self-managed kubeadm)** | $0 (on the node) | The cluster (CKA-style) | **~$28/mo** (CAX21: ~$14/mo) |
| LKE (managed) | $0 | Apps only | ~$109/mo |
| EKS (managed) | $73 | Apps only | ~$248/mo |

---

## Self-Managed Single Node (Hetzner) — Cheapest

The `terraform/hetzner/` module provisions one Hetzner Cloud server, bootstraps a single-node Kubernetes cluster with `kubeadm` (containerd, Calico CNI, `local-path` default StorageClass, control-plane untainted), and deploys the same OMCSI Helm chart all targets share.

| Component | Cost | Notes |
|---|---|---|
| Control plane | **$0** | Runs on the same node (untainted control plane) |
| Server (1× cax31) | **~€24.99 (~$28)** | Ampere ARM64, 8 vCPU / 16 GB / 160 GB NVMe |
| Load balancer | **$0** | NodePorts pinned to 25565/80/443 on the node's public IP (apiserver `--service-node-port-range` widened to `80-32767`) |
| NAT gateway | **$0** | Node has a public IP directly |
| Egress | **$0** | Hetzner bundles 20 TB/mo |
| Block storage | **$0** | `local-path` provisioner uses the node's included NVMe |
| **Estimated total** | **~$28/mo** | ~$14/mo with `server_type = "cax21"` (~€12.49, 4 vCPU / 8 GB) |

**Cheaper / alternative hosts** (the module's `server_type` / `location` cover Hetzner; the same kubeadm approach applies elsewhere):

| Host | Specs | Cost | Notes |
|---|---|---|---|
| Oracle Cloud Always Free (Ampere A1) | 4 OCPU / 24 GB | **$0** | Free forever, but ARM + frequent "Out of Capacity"; needs multi-arch images |
| Hetzner CAX31 (default) | 8 vCPU / 16 GB | ~$28 | Most headroom; ARM |
| Hetzner CAX21 | 4 vCPU / 8 GB | ~$14 | Under the $20 target; ARM; lower `java_opts` (e.g. `-Xmx4G -Xms2G`) |
| Hetzner CPX31 | 4 vCPU / 8 GB | ~$9–18 | x86; tighter RAM but sufficient |
| Contabo VPS 10 | 4 vCPU / 8 GB | ~$5–7 | Cheapest; weaker CPU/disk (fine for 5–10 players) |

> **ARM note:** The cheapest Hetzner (CAX) and the free Oracle tier are ARM64. The project's published images are multi-arch (`linux/amd64,linux/arm64`), so they run on ARM out of the box. If you fork and publish your own images, keep the `platforms:` flag in `.github/workflows/docker-publish.yml`.

> **Trade-off:** You own cluster operations — upgrades, etcd backups, node maintenance, and single-node availability (no HA). This is the explicit deal for the lower price, and it maps cleanly onto CKA-style skills (kubeadm, CNI, taints, NodePort/`service-node-port-range`, StorageClasses).

---

## OMCSI Default Configuration

The Terraform defaults in this project deploy the following cluster configuration. These are the costs you can expect when running `terraform apply` without overriding `node_type` or `node_count`.

| Component | LKE (Linode) | EKS (AWS) |
|---|---|---|
| Control plane | **$0** (free) | **$73** ($0.10/hr) |
| Worker nodes | ~$96 (2× g6-standard-4 @ $48/mo — 8 GB, 4 vCPU each) | ~$122 (2× t3.large @ $61/mo — 8 GB, 2 vCPU each) |
| Load balancer | $10 (NodeBalancer) | ~$17 (ALB) |
| NAT Gateway | — | ~$33 (1× NAT @ $0.045/hr) |
| Storage (block) | ~$3 (32 Gi @ $0.10/GB) | ~$3 (32 Gi gp2 @ $0.10/GB) |
| **Estimated total** | **~$109/mo** | **~$248/mo** |

> **Resource allocation**: The Helm chart allocates 8 Gi memory (limit) to the Minecraft server and reduces supporting services (webapp, nginx, alert-manager, backup-manager) to minimal footprints (64–256 Mi). This allows the game server to use most of the available node capacity.

## Scenario 1: Small Dev/Staging Cluster (3 nodes, smaller instances)

| Component | LKE (Linode) | EKS (AWS) |
|---|---|---|
| Control plane | **$0** (free) | **$73** ($0.10/hr) |
| 3× worker nodes (4 GB RAM, 2 vCPU) | ~$72 (3× g6-standard-2 @ $24/mo) | ~$90 (3× t3.medium @ $30/mo) |
| Load balancer | $10 (NodeBalancer) | ~$17 (ALB) |
| NAT Gateway | — | ~$33 (1× NAT @ $0.045/hr) |
| **Estimated total** | **~$82/mo** | **~$213/mo** |

## Scenario 2: Small Production Cluster (10 nodes, HA)

| Component | LKE (Linode) | EKS (AWS) |
|---|---|---|
| Control plane | **$60** (HA upgrade) | **$73** |
| 10× worker nodes | ~$240 (10× $24/mo) | ~$560 (10× m5.large) |
| Load balancer | $10 | ~$17 |
| NAT Gateways (multi-AZ) | — | ~$99 (3×) |
| Storage (EBS/block) | ~$20 | ~$30 |
| **Estimated total** | **~$330/mo** | **~$779/mo** |

---

## Key Cost Differences

### Control Plane Fee
- **LKE**: Free by default. HA upgrade is $60/month per cluster.
- **EKS**: $0.10/hour ($73/month) per cluster, regardless of workload. If a cluster falls behind on Kubernetes version upgrades and enters extended support, this jumps to $0.60/hour ($438/month) per cluster.

### Hidden AWS Costs
AWS clusters accrue several additional costs that have no Linode equivalent:

- **NAT Gateway**: Required for worker nodes in private subnets. Each gateway costs ~$33/month plus $0.045/GB of data processed. Multi-AZ setups multiply this cost.
- **Data transfer**: AWS charges $0.09/GB for outbound internet traffic (first 100 GB/month free). Linode includes generous transfer pools (e.g., 2 TB/month per node).
- **EBS volumes**: GP2/GP3 storage is ~$0.08–0.10/GB/month. Linode block storage is $0.10/GB/month but includes more baseline IOPS.
- **CloudWatch / logging**: Monitoring and log ingestion on AWS incur per-GB charges. Linode does not charge separately for basic monitoring.

### Worker Node Pricing
Linode shared instances are significantly cheaper than AWS on-demand instances for equivalent specs. AWS Reserved Instances or Spot can close the gap but add commitment or reliability trade-offs.

| Spec | Linode (Shared) | AWS (On-Demand) |
|---|---|---|
| 2 vCPU / 4 GB | $24/mo | ~$30/mo (t3.medium) |
| 4 vCPU / 8 GB | $48/mo | ~$61/mo (t3.large) |
| 8 vCPU / 16 GB | $96/mo | ~$122/mo (t3.xlarge) |

---

## When to Choose Each Provider

### Choose Hetzner self-managed (`terraform/hetzner/`) when:
- Cost is the top priority (under ~$20/month on `cax21`, ~$28/month on the default `cax31`)
- You're comfortable operating the cluster yourself (kubeadm, upgrades, backups)
- A single node without HA is acceptable for your community size
- You want the cheapest path that still uses the standard OMCSI Helm chart

### Choose LKE (Linode) when:
- Cost is the primary concern
- Running a single game server or small community deployment
- You want a simpler networking model (no NAT Gateway management)
- Predictable monthly billing is important

### Choose EKS (AWS) when:
- You need deep integration with the AWS ecosystem (IAM, RDS, S3, CloudFront, etc.)
- Multi-region or multi-AZ high availability is required
- Your organization already has AWS infrastructure and expertise
- You need fine-grained IAM policies (IRSA) for workload identity

---

## Notes

- All prices are approximate and based on publicly listed rates as of 2025. Actual costs may vary by region and usage.
- Hetzner CAX prices were re-checked against the Hetzner Cloud API on 2026-09-06 (cax21 €12.49, cax31 €24.99). USD figures use ~1.12 USD/EUR. The `estimated_monthly_cost` Terraform output deliberately quotes no price, since committed prices go stale.
- AWS costs can be reduced with Reserved Instances, Savings Plans, or Spot Instances — but these require upfront commitment or accept interruption risk.
- Linode pricing is generally flat and predictable; there are no per-GB data processing fees for NAT or load balancers.
- This analysis covers infrastructure costs only. Operational costs (staff time, tooling) are not included.
