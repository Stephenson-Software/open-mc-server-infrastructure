# Kubernetes Deployment — Potential Improvements

This document captures issues, limitations, and improvement opportunities surfaced during the initial Kubernetes/Helm work in [PR #128](https://github.com/dmccoystephenson/open-mc-server-infrastructure/pull/128). Each item is a candidate for a follow-up issue or PR.

---

## 1. Backup Manager — Docker CLI Dependency ✅ Resolved

**Problem:** The `backup-manager` created backups by shelling out to `docker run` to spin up an Ubuntu container that tars the mcserver volume. In Kubernetes, there is no Docker daemon on the nodes (or the pod doesn't have access to it), so this backup mechanism silently failed.

**Resolution:** The backup-manager now uses `tar` directly on the mounted filesystem. The mcserver PVC is already mounted at `/mcserver` inside the pod (in both Docker Compose and Kubernetes), so the service runs `tar czf` against that directory without requiring Docker CLI. The Docker socket mount has been removed from `compose.yml`, `docker.io` has been removed from the `Dockerfile`, and the Helm chart backup-manager is now fully enabled by default.

**Changes made:**
- `BackupService.java`: replaced `checkVolumeExists()` / `ensureUbuntuImageAvailable()` / `docker run` with a direct `tar` invocation on the `source.directory` mount point.
- `application.properties`: replaced `volume.name` and `host.backup.directory` with `source.directory`.
- `Dockerfile`: removed `docker.io` dependency.
- `compose.yml`: removed Docker socket mount, `HOST_BACKUP_DIRECTORY`, and `VOLUME_NAME` env vars.
- `helm/omcsi/values.yaml`: enabled scheduled backups (`SPRING_TASK_SCHEDULING_ENABLED: "true"`, `BACKUP_SCHEDULE: "0 0 2 * * ?"`), enabled alerts, replaced `VOLUME_NAME` with `SOURCE_DIRECTORY`.
- `helm/omcsi/templates/backup-manager.yaml`: replaced `VOLUME_NAME` env entry with `SOURCE_DIRECTORY`.

**Follow-up:** `SPRING_TASK_SCHEDULING_ENABLED` was later removed — `spring.task.scheduling.enabled` is not a Spring Boot property, so the knob never disabled anything. Scheduled backups are switched off by setting `BACKUP_SCHEDULE` to `-` (Spring's `Scheduled.CRON_DISABLED` marker) instead.

---

## 2. Health Probes — Missing for Several Services ✅ Resolved

**Problem:** Only `alert-manager`, `webapp`, and `nginx` had health probes defined. `minecraft-wrapper`, `backup-manager`, and `agent-manager` had no liveness, readiness, or startup probes.

**Resolution:** Health probes added to all services in [PR #153](https://github.com/dmccoystephenson/open-mc-server-infrastructure/pull/153):
- `minecraft-wrapper`: `startupProbe` with `failureThreshold: 60 × periodSeconds: 10` = 10 minutes (accommodates Spigot's build-on-first-run compilation), plus `livenessProbe` and `readinessProbe` on the wrapper API port.
- `backup-manager`: `livenessProbe` and `readinessProbe` on `/actuator/health`.
- `agent-manager`: `livenessProbe` and `readinessProbe` on `/actuator/health` via the dedicated management port (8094). The management port address restriction (`127.0.0.1`) was removed so the kubelet can reach the probe endpoint.

---

## 3. Security Contexts ✅ Resolved

**Problem:** No Deployment in the Helm chart sets `securityContext`, `runAsNonRoot`, or `readOnlyRootFilesystem`. All containers run as root by default, which is a security concern in multi-tenant clusters.

**Resolution:** Security contexts added to all Deployments across [PR #155](https://github.com/dmccoystephenson/open-mc-server-infrastructure/pull/155) and [PR #156](https://github.com/dmccoystephenson/open-mc-server-infrastructure/pull/156):
- **Pod-level** (`spec.securityContext`): `seccompProfile: RuntimeDefault` and `fsGroup: 1000` on every Deployment.
- **Container-level** (`spec.containers[].securityContext`): `allowPrivilegeEscalation: false`, `capabilities.drop: [ALL]`, `readOnlyRootFilesystem: true`, `runAsNonRoot: true`, and `runAsUser: 1000` on every container and init container.
- **nginx exception**: the nginx main container adds only `NET_BIND_SERVICE` (to bind ports 80/443 as UID 1000). `CHOWN`, `SETUID`, and `SETGID` are not needed because the container starts as a non-root user. The nginx binary has `CAP_NET_BIND_SERVICE` set at the file level (`setcap`) for Docker Compose compatibility.
- All Java services mount a `/tmp` emptyDir for Spring Boot's embedded Tomcat temp directory; nginx additionally mounts `/var/cache/nginx`. These are the only writable paths at runtime.
- All values are configurable via `podSecurityContext` and `containerSecurityContext` in `values.yaml`; nginx overrides via `nginx.containerSecurityContext`.

---

## 4. Ingress Controller Support

**Problem:** The chart currently exposes services via `NodePort` (Minecraft) and `LoadBalancer` (nginx). There is no Ingress resource, which means operators who prefer an ingress controller (e.g., nginx-ingress, Traefik, ALB Ingress Controller) must manually create their own routing.

**Suggested improvements:**
- Add an optional `Ingress` template (gated by `ingress.enabled`) with configurable annotations, TLS, and host rules.
- Support cert-manager annotations for automatic TLS certificate provisioning (replacing the self-signed init container).
- Document common ingress setups for each supported provider.

**Priority:** Medium — already identified as out of scope in the original issue; natural follow-up.

---

## 5. TLS Certificate Management

**Problem:** The nginx service uses a self-signed TLS certificate generated by an init container. This is fine for development but triggers browser warnings and is not suitable for production.

**Suggested improvements:**
- Document integration with [cert-manager](https://cert-manager.io/) for automatic Let's Encrypt certificates.
- When ingress support is added (item 4), TLS termination can move to the ingress controller, simplifying the nginx configuration.
- Consider adding a `Certificate` CRD template or documented annotations for cert-manager when `tls.selfSigned: false`.

**Priority:** Low — self-signed works for private/development servers; production setups typically have their own TLS strategy.

---

## 6. Shared PVC Scheduling Constraints ✅ Resolved (documentation)

**Problem:** The mcserver PVC uses `ReadWriteOnce` (RWO), which means all pods mounting it must run on the same node. The chart uses `preferredDuringSchedulingIgnoredDuringExecution` pod affinity to encourage co-location, but this can fail on multi-node clusters if the node with the PVC has insufficient resources.

**Resolution:** `persistence.mcserver.accessMode` was already a plain Helm value (no code change required) — switching to `ReadWriteMany` only needed documenting. The [Multi-Node Scheduling & RWX StorageClasses](../README.md#multi-node-scheduling--rwx-storageclasses) section in the README now covers this: setting `accessMode: ReadWriteMany` with an RWX-capable StorageClass (NFS, AWS EFS via the EFS CSI driver, Longhorn) removes the co-location requirement entirely, since all pods can then mount the PVC regardless of which node they land on.

**Evaluation — does `webapp` / `backup-manager` need direct filesystem access?** Yes, for both, today:
- `webapp`'s `WorldService` and `PluginService` list, upload, and delete files directly under `/mcserver` (world directories, plugin jars) — see `web-app/src/main/java/com/openmc/webapp/service/WorldService.java` and `PluginService.java`. Routing this through `minecraft-wrapper`'s REST API instead would mean adding streaming file-transfer and directory-listing endpoints to the wrapper for every operation webapp currently does with `java.nio.file` calls — a much larger change than the low priority of this issue justifies.
- `backup-manager`'s `BackupService` runs `tar` directly against the whole `sourceDirectory` (`/mcserver`) to produce `mcserver-backup.tar.gz` — see `backup-manager/src/main/java/com/openmc/backupmanager/service/BackupService.java`. Proxying a full-directory tar through an HTTP API on the wrapper would add complexity (streaming, timeouts, partial-failure handling) without removing the need for shared storage, since the wrapper itself still has to read every file to stream it.

Given that, making `minecraft-wrapper` the sole PVC owner (sidecar/init-container pattern) was evaluated and rejected for now — it doesn't eliminate the shared-storage requirement, it just relocates it behind an API that would need to support arbitrary file listing, upload, and bulk-download. RWX StorageClasses solve the actual problem (multi-node scheduling) directly and are already supported by the existing `accessMode` value.

**Priority:** Low — the current affinity rules work for typical 2-node clusters; RWX is documented as the option for larger or more constrained environments.

---

## 7. Horizontal Pod Autoscaler (HPA) ✅ Resolved

**Problem:** No HPA is configured for any service. The Minecraft server is intentionally limited to a single replica, but supporting services (webapp, nginx, alert-manager) could benefit from autoscaling under load.

**Resolution:** Optional HPA templates were added to `helm/omcsi/templates/hpa.yaml` for `nginx`, `webapp`, and `alert-manager`, each independently gated by its own `<service>.autoscaling.enabled` in `values.yaml` (default `false`), targeting CPU utilization. Each Deployment's `replicas` field is omitted once its autoscaling is enabled so Helm upgrades don't fight the HPA's chosen replica count.

`webapp` and `alert-manager` each mount a `ReadWriteOnce` PVC by default, so their Deployment templates `fail` if autoscaling is enabled with `maxReplicas > 1` (or `replicaCount > 1`) while the associated PVC(s) are still RWO. Scaling either service beyond 1 replica requires switching its PVC(s) to a `ReadWriteMany` (RWX) StorageClass first — see [Multi-Node Scheduling & RWX StorageClasses](../README.md#multi-node-scheduling--rwx-storageclasses) — a config change users can already make today, resolving [#147](https://github.com/dmccoystephenson/open-mc-server-infrastructure/issues/147). `minecraft-wrapper` remains single-instance by design (owns world data + RCON) and is not a candidate for HPA.

**Priority:** Low — OMCSI is typically a small-scale deployment; autoscaling adds value for larger communities.

---

## 8. Network Policies ✅ Resolved

**Problem:** No `NetworkPolicy` resources are defined, so all pods can communicate with each other and with external endpoints without restriction.

**Resolution:** Per-service `NetworkPolicy` resources added to `helm/omcsi/templates/networkpolicies.yaml` in [PR #155](https://github.com/dmccoystephenson/open-mc-server-infrastructure/pull/155):
- Each service has a policy with `policyTypes: [Ingress, Egress]`, which implicitly denies any traffic not matched by an explicit allow rule.
- Ingress allow rules use `podSelector` wherever possible. Ports shared between internal callers and kubelet health probes include both a `podSelector` for each known caller and an `ipBlock` using the configurable `networkPolicy.kubeNodeCIDR` value (default `0.0.0.0/0`); set this to your cluster's node CIDR for tighter control. Purely internal ports (RCON 25575) are restricted to specific pod selectors only with no `ipBlock`.
- `alert-manager` ingress is restricted to minecraft-wrapper, webapp, backup-manager, and agent-manager pod selectors (plus `kubeNodeCIDR` for probes). `backup-manager` ingress is restricted to agent-manager (plus `kubeNodeCIDR`).
- `minecraft-wrapper` egress includes webapp (for `WEBAPP_URL` deployment-history notifications) in addition to alert-manager and DNS.
- DNS egress is scoped to CoreDNS pods in `kube-system` via configurable `networkPolicy.dnsNamespaceSelector` and `networkPolicy.dnsPodSelector` (defaults work for kubeadm, GKE, EKS, LKE). Falls back to unrestricted `0.0.0.0/0` when values are absent.
- External HTTPS egress (port 443) is allowed only for services that call Discord or the Anthropic API (alert-manager, agent-manager).
- The `agent-manager` policy is gated behind `agentManager.enabled` (same condition as the Deployment).
- Gated behind `networkPolicy.enabled` (default `true`); set `false` on clusters whose CNI does not support NetworkPolicy (e.g. Flannel without a policy controller).

---

## 9. Pod Disruption Budgets (PDB) ✅ Resolved

**Problem:** No `PodDisruptionBudget` resources were defined. During node maintenance or cluster upgrades, all pods (including the Minecraft server) could be evicted simultaneously.

**Resolution:** PDBs added to `helm/omcsi/templates/pdb.yaml` in [PR #153](https://github.com/dmccoystephenson/open-mc-server-infrastructure/pull/153):
- `minecraft-wrapper`: `minAvailable: 1` — prevents voluntary eviction of the live server.
- `webapp`: `minAvailable: 1` — keeps the admin dashboard available during drains.
- `alert-manager`: `minAvailable: 1` — prevents monitoring from going silent during maintenance.

All three PDBs use `policy/v1` (stable since Kubernetes 1.21).

---

## 10. ~~Helm Chart Publishing~~ ✅ Resolved

**Problem:** The chart is only available from the Git repository. Operators must clone the repo or reference the chart path directly.

**Implemented in PR #224:**
- Added `.github/workflows/helm-publish.yml`, which lints, packages, and pushes the chart to `oci://ghcr.io/stephenson-software/charts/omcsi` (the repository owner, lowercased because GHCR rejects uppercase paths) whenever `helm/omcsi/Chart.yaml` changes on `main` (or via manual `workflow_dispatch`).
- README documents `helm install omcsi oci://ghcr.io/stephenson-software/charts/omcsi --version <chart-version>` as an alternative to cloning the repo.
- The local-path install (`./helm/omcsi`) continues to work unchanged for all deployment flows.
- The chart is only republished when `Chart.yaml`'s `version` is bumped, so template changes must be accompanied by a version bump to reach the registry.

---

## 11. ~~Monitoring and Observability~~ ✅ Resolved

**Problem:** No Prometheus `ServiceMonitor` or Grafana dashboard templates were included. Operators had to manually configure monitoring.

**Implemented in PR #157:**
- Added `monitoring.enabled` gate (defaults to `false`) in `values.yaml`.
- When enabled, one `ServiceMonitor` is created per service targeting Spring Boot Actuator's `/actuator/prometheus` endpoint:
  - `minecraft-wrapper` — internal service, `wrapper` port (8092)
  - `webapp` — `http` port (8080)
  - `alert-manager` — `http` port (8090)
  - `backup-manager` — `http` port (8091)
  - `agent-manager` — `management` port (8094), only when `agentManager.enabled: true`
- Each scraped service gains an `omcsi.io/metrics: "true"` label so the `ServiceMonitor` selector avoids the external minecraft-wrapper NodePort (game port only).
- Agent-manager's management port 8094 is now also exposed in its `Service` spec.
- A sample Grafana dashboard ConfigMap (`monitoring.grafanaDashboard.enabled`, default `true` when monitoring is on) is created with panels for service uptime, JVM heap, HTTP request rate/latency, CPU usage, and GC pause time.
- `monitoring.serviceMonitor.labels` and `monitoring.grafanaDashboard.labels` allow operators to match their Prometheus Operator's `serviceMonitorSelector` and Grafana sidecar label, e.g.:
  ```yaml
  monitoring:
    enabled: true
    serviceMonitor:
      labels:
        release: kube-prometheus-stack
    grafanaDashboard:
      labels:
        grafana_dashboard: "1"
  ```
- Requires the Prometheus Operator (e.g., `kube-prometheus-stack`) to be installed in the cluster.

---

## Summary

| # | Improvement | Priority | Effort |
|---|---|---|---|
| 1 | ~~Backup manager — native filesystem mode~~ ✅ Resolved | ~~High~~ | ~~Medium~~ |
| 2 | ~~Health probes for all services~~ ✅ Resolved | ~~Medium~~ | ~~Low~~ |
| 3 | ~~Security contexts~~ ✅ Resolved | ~~Medium~~ | ~~Low~~ |
| 4 | Ingress controller support | Medium | Medium |
| 5 | TLS certificate management (cert-manager) | Low | Medium |
| 6 | Shared PVC scheduling improvements | Low | Medium |
| 7 | Horizontal Pod Autoscaler | Low | Low |
| 8 | ~~Network policies~~ ✅ Resolved | ~~Low~~ | ~~Medium~~ |
| 9 | ~~Pod Disruption Budgets~~ ✅ Resolved | ~~Low~~ | ~~Low~~ |
| 10 | ~~Helm chart publishing~~ ✅ Resolved | ~~Low~~ | ~~Low~~ |
| 11 | ~~Monitoring and observability~~ ✅ Resolved | ~~Low~~ | ~~Medium~~ |
