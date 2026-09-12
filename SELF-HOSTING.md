# Self-Hosting Guide for Home Deployment

This guide covers deploying the Open Minecraft Server Infrastructure securely on a home PC or server.

## Table of Contents

- [Prerequisites](#prerequisites)
- [Hardware Requirements](#hardware-requirements)
- [Network Configuration](#network-configuration)
- [Security Best Practices](#security-best-practices)
- [Firewall Configuration](#firewall-configuration)
- [DDoS Protection and Rate Limiting](#ddos-protection-and-rate-limiting)
- [Further Topics](#further-topics)

## Prerequisites

- A stable internet connection (minimum 5 Mbps upload per 10 concurrent players)
- [Docker](https://docs.docker.com/get-docker/) and [Docker Compose](https://docs.docker.com/compose/install/) installed
- Administrative access to your router for port forwarding

## Hardware Requirements

### Minimum
- **CPU**: 4-core (e.g., Intel i5 or AMD Ryzen 5)
- **RAM**: 8 GB
- **Storage**: 20 GB SSD
- **Network**: 100 Mbps (10 Mbps upload)

### Recommended
- **CPU**: 6+ cores
- **RAM**: 16 GB+
- **Storage**: 50+ GB NVMe SSD
- **Network**: 500 Mbps+ (50 Mbps+ upload)
- **OS**: Ubuntu Server 22.04 LTS or newer

## Network Configuration

### 1. Static Local IP Address

Assign a static IP to your server so port forwarding stays consistent.

**Linux (Ubuntu/Debian — netplan):** Edit `/etc/netplan/01-netcfg.yaml`:

```yaml
network:
  version: 2
  renderer: networkd
  ethernets:
    eth0:
      dhcp4: no
      addresses:
        - 192.168.1.100/24
      routes:
        - to: default
          via: 192.168.1.1
      nameservers:
        addresses: [8.8.8.8, 8.8.4.4]
```

Then run `sudo netplan apply`.

**Windows:** Set a static IP via Control Panel > Network Connections > IPv4 Properties.

### 2. Router Port Forwarding

Forward these ports to your server's static IP:

| Port  | Protocol | Service               | Required? |
|-------|----------|-----------------------|-----------|
| 25565 | TCP      | Minecraft Server      | Yes       |
| 8443  | TCP      | Web Dashboard (HTTPS) | Recommended |
| 8100  | TCP      | BlueMap               | Optional  |
| 25575 | TCP      | RCON                  | **Do not expose publicly** |

In your router's admin UI (typically `192.168.1.1`), add a port forwarding rule for each port you need pointing to your server's static IP.

Verify ports are open using [CanYouSeeMe.org](https://canyouseeme.org/) or [PortChecker.co](https://portchecker.co/).

## Security Best Practices

### Strong Passwords

Change all defaults in `.env`:

```bash
RCON_PASSWORD=YourStrongPassword123!@#
ADMIN_USERNAME=your_admin_user
ADMIN_PASSWORD=YourStrongAdminPassword456!@#
```

### Enable Online Mode

```bash
ONLINE_MODE=true
```

Prevents joining with unauthenticated accounts.

### Whitelist (private servers)

```bash
WHITELIST_ENABLED=true
ENFORCE_WHITELIST=true
```

Only players in `whitelist.json` may join; `ENFORCE_WHITELIST` also kicks
already-connected players who are not on the list when it reloads. Manage the
list with `/whitelist add <player>` — `whitelist.json` persists across restarts.
Note that `/whitelist on` alone does not, because `server.properties` is
regenerated from these variables on every start.

### SSL Certificates

Replace the self-signed certificate with one from [Let's Encrypt](https://letsencrypt.org/) for public access:

```bash
# Temporarily allow port 80 and forward it on your router first
sudo ufw allow 80/tcp
sudo certbot certonly --standalone -d yourdomain.com
sudo ufw delete allow 80/tcp

# Start the stack so the nginx-ssl named volume exists, then inject certs
./up.sh
docker cp /etc/letsencrypt/live/yourdomain.com/fullchain.pem open-mc-nginx:/etc/nginx/ssl/cert.pem
docker cp /etc/letsencrypt/live/yourdomain.com/privkey.pem open-mc-nginx:/etc/nginx/ssl/key.pem
docker compose restart nginx
```

> If you can't expose port 80, use `--preferred-challenges dns` (DNS-01 challenge) instead.

### Harden Docker Containers

Create a `compose.override.yml` in the project root to restrict capabilities and set resource limits:

```yaml
# Applies automatically when running 'docker compose up'
services:
  mcserver:
    cap_drop:
      - ALL
    security_opt:
      - no-new-privileges:true
    cpus: '4'
    mem_limit: 8G

  webapp:
    cap_drop:
      - ALL
    security_opt:
      - no-new-privileges:true
    cpus: '2'
    mem_limit: 2G

  nginx:
    cap_drop:
      - ALL
    cap_add:
      - NET_BIND_SERVICE
    security_opt:
      - no-new-privileges:true
    cpus: '1'
    mem_limit: 512M
```

### Other Essentials

- Keep system and Docker images updated: `sudo apt-get upgrade -y && docker compose pull && ./up.sh`
- Run backups regularly: `./trigger-backup.sh` (store copies offsite)
- Never expose RCON (port 25575) publicly; use the web dashboard or SSH tunnel instead
- Know what leaves your network: the wrapper sends one anonymous `startup` event (program name and version only) to the project's usage service on every start. Set `USAGE_REPORTING_ENABLED=false` in `.env` to turn it off — see "Usage Reporting" in the README

## Firewall Configuration

### UFW (Recommended for Ubuntu/Debian)

```bash
sudo apt-get install ufw
sudo ufw default deny incoming
sudo ufw default allow outgoing
sudo ufw allow 22/tcp    # SSH
sudo ufw allow 25565/tcp # Minecraft
sudo ufw allow 8443/tcp  # Web Dashboard
sudo ufw enable
sudo ufw status verbose
```

### iptables (Advanced)

```bash
# Backup current rules
sudo iptables-save > /var/backups/iptables-backup-$(date +%Y%m%d).rules

# Set ACCEPT policies first to avoid lockout during setup
sudo iptables -F
sudo iptables -P INPUT ACCEPT
sudo iptables -P FORWARD ACCEPT
sudo iptables -P OUTPUT ACCEPT

# Core rules
sudo iptables -A INPUT -i lo -j ACCEPT
sudo iptables -A INPUT -m conntrack --ctstate ESTABLISHED,RELATED -j ACCEPT

# SSH with rate limiting
sudo iptables -A INPUT -p tcp --dport 22 -m conntrack --ctstate NEW -m recent --name SSH --set
sudo iptables -A INPUT -p tcp --dport 22 -m conntrack --ctstate NEW -m recent --name SSH --update --seconds 60 --hitcount 5 -j DROP
sudo iptables -A INPUT -p tcp --dport 22 -j ACCEPT

# Services
sudo iptables -A INPUT -p tcp --dport 25565 -j ACCEPT
sudo iptables -A INPUT -p tcp --dport 8443 -j ACCEPT

# Set default DROP policies last
sudo iptables -P INPUT DROP
sudo iptables -P FORWARD DROP

# Persist rules
sudo apt-get install -y iptables-persistent
sudo netfilter-persistent save
```

**Windows:** Use Windows Defender Firewall with Advanced Security to add an inbound TCP rule for each required port.

## DDoS Protection and Rate Limiting

### Connection Rate Limiting (iptables)

```bash
# Use distinct --name per service to keep hitcounts isolated
sudo iptables -A INPUT -p tcp --dport 25565 -m conntrack --ctstate NEW -m recent --name MC --set
sudo iptables -A INPUT -p tcp --dport 25565 -m conntrack --ctstate NEW -m recent --name MC --update --seconds 60 --hitcount 10 -j DROP

sudo iptables -A INPUT -p tcp --dport 8443 -m conntrack --ctstate NEW -m recent --name WEB --set
sudo iptables -A INPUT -p tcp --dport 8443 -m conntrack --ctstate NEW -m recent --name WEB --update --seconds 60 --hitcount 20 -j DROP

sudo netfilter-persistent save
```

### Fail2Ban

Install Fail2Ban (`sudo apt-get install fail2ban`) and create a custom filter at `/etc/fail2ban/filter.d/minecraft.conf`. Use only patterns that match genuine abuse signals — do **not** use generic patterns like "lost connection" or "was kicked" which occur during normal play. Inspect `/var/lib/docker/volumes/mcserver/_data/logs/latest.log` (adjust `mcserver` to your `VOLUME_NAME`) to identify real abuse indicators for your server version.

See [Fail2Ban documentation](https://www.fail2ban.org/wiki/index.php/Main_Page) for configuration details.

### Other Options

- **Cloudflare** (free tier): Proxy your web dashboard domain through Cloudflare for DDoS protection and firewall rules. See [Cloudflare docs](https://developers.cloudflare.com/fundamentals/).
- **Nginx rate limiting**: Add `limit_req_zone`/`limit_conn_zone` directives to `nginx/nginx.conf`. See the [nginx rate limiting guide](https://www.nginx.com/blog/rate-limiting-nginx/).

## Further Topics

### Dynamic DNS

If your ISP assigns a dynamic public IP, use a DDNS service to keep a stable hostname:
- [DuckDNS](https://www.duckdns.org/) (free) — install `ddclient` and configure it with your token
- [No-IP](https://www.noip.com/) (free tier) — supports ddclient and many routers natively
- Most modern routers have built-in DDNS support under their WAN/DDNS settings

See each provider's setup guide for configuration steps.

### SSL Certificates with DDNS

Once you have a DDNS domain, obtain a Let's Encrypt certificate with `certbot` (see [SSL Certificates](#ssl-certificates) above) using your DDNS hostname. Set up a deploy hook to copy renewed certificates into the running container (the `nginx-ssl` named Docker volume, not a host directory — see [SSL Certificates](#ssl-certificates) above) and restart nginx:

```bash
# /etc/letsencrypt/renewal-hooks/deploy/01-copy-certs.sh
docker cp /etc/letsencrypt/live/yourdomain.duckdns.org/fullchain.pem open-mc-nginx:/etc/nginx/ssl/cert.pem
docker cp /etc/letsencrypt/live/yourdomain.duckdns.org/privkey.pem open-mc-nginx:/etc/nginx/ssl/key.pem
cd /path/to/open-mc-server-infrastructure && docker compose restart nginx
```

### Monitoring and Maintenance

- **Logs**: `docker logs -f open-mc-server`, `docker logs -f open-mc-nginx`
- **Resource usage**: `docker stats`, `htop`
- **Disk space**: `ncdu /var/lib/docker`
- **Schedule**: run `./trigger-backup.sh` weekly, `sudo apt-get upgrade` monthly, `./upgrade.sh` quarterly

### Troubleshooting

| Symptom | Check |
|---------|-------|
| Players can't connect | Port forwarding, `docker ps`, server logs |
| Web dashboard inaccessible | `docker logs open-mc-nginx`, SSL cert files via `docker exec open-mc-nginx ls -la /etc/nginx/ssl/` (the `nginx-ssl` named volume, not a host directory) |
| High latency | `docker stats`, increase `JAVA_OPTS` memory, reduce view-distance |
| Server crashes | `docker logs open-mc-server`, `dmesg | grep -i "out of memory"` |

### Advanced Firewall (OPNsense / pfSense)

For enterprise-grade security, use a dedicated firewall appliance:
- **[OPNsense](https://opnsense.org/)** (recommended): supports IDS/IPS (Suricata), traffic shaping, GeoIP blocking, and VPN. See the [OPNsense documentation](https://docs.opnsense.org/).
- **[pfSense](https://www.pfsense.org/)**: similar feature set with Snort IDS and pfBlockerNG for GeoIP. See the [pfSense documentation](https://docs.netgate.com/pfsense/en/latest/).

Key steps: install on dedicated hardware, configure WAN/LAN interfaces, add port forwarding rules (NAT) for ports 25565 and 8443, and restrict everything else by default.

> **Note on containerized firewalls**: A containerized iptables approach (using `privileged: true` and `network_mode: host`) is possible but risky — rules persist on the host if the container crashes. Prefer host-based UFW or a dedicated router firewall for home use.
