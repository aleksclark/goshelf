# Non-secret home-fleet overlay for goshelf (plan 03 §3.2).
# Secrets NEVER belong here — use Nomad Variable nomad/jobs/goshelf.
#
# These values document site policy for operators and future reconciler
# var-file injection. The jobspec currently embeds matching defaults so
# standalone plan/run remains valid before full variable wiring.

datacenter = "home"

# Nomad host volume aliases (fleet-defined interfaces)
host_volume_media   = "moosefs-media"
host_volume_configs = "moosefs-configs"

# Container mount destinations
media_mount_destination   = "/audiobooks"
configs_mount_destination = "/configs"

# Paths / endpoints (non-secret)
db_path            = "/configs/goshelf/goshelf.db"
media_path         = "/audiobooks"
readarr_media_root = "/media"
readarr_url        = "https://readarr.fleet.clark.team"

# Network / routes
http_port_static = 8580
route_hosts = [
  "books.fleet.clark.team",
  "books.clark.team",
]
traefik_entrypoint   = "websecure"
traefik_certresolver = "letsencrypt"

# Resources
cpu_mhz    = 200
memory_mb  = 128

# Rollout class markers (documentation for operators / contract tests)
rollout_class           = "singleton-stateful"
update_policy           = "stop-before-start"
max_parallel            = 1
canary                  = 0
sqlite_single_writer    = true
