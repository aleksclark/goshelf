// Authoritative Nomad jobspec for GoShelf (project-owned).
// Deploy via reviewed CAS from this file after release image digest pin.
// Secrets: Nomad Variable nomad/jobs/goshelf key readarr_api_key (workload identity).
//
// Image pin is updated after each release to an immutable tag@digest matching
// deploy/nomad/images.lock.hcl. Do not embed API keys or media titles/paths
// beyond mount classes.
//
// SINGLE-WRITER / SQLITE:
// NEVER run two allocations concurrent against the same SQLite DB on
// moosefs-configs (/configs/goshelf/goshelf.db + WAL/SHM). Enforcement:
//   1) group count = 1
//   2) update max_parallel = 1, canary = 0 (no dual-alloc canary)
//   3) operator update policy: stop-before-start (drain writers to zero, then start)
// max_parallel=1 alone is NOT dual-writer safe on shared SQLite.

job "goshelf" {
  datacenters = ["home"]
  type        = "service"

  # Provenance meta for pull reconciler (plan 03 §3.1 / plan 02 normalization).
  # source_revision is a placeholder until the reconciler injects the exact SHA.
  meta {
    managed_by                 = "fleet-reconciler"
    source_repo                = "https://github.com/aleksclark/goshelf"
    source_path                = "deploy/nomad/jobs/goshelf.nomad.hcl"
    source_revision            = "0000000000000000000000000000000000000000"
    deployment_owner           = "aleks-clark"
    release_set                = "goshelf"
    update_policy              = "stop-before-start"
    single_writer              = "required"
    single_writer_stop_before_start = "true"
    sqlite_db_path             = "/configs/goshelf/goshelf.db"
  }

  # Job-level update mirrors group policy for serial singleton gates.
  update {
    max_parallel      = 1
    health_check      = "checks"
    min_healthy_time  = "10s"
    healthy_deadline  = "5m"
    progress_deadline = "10m"
    auto_revert       = true
    auto_promote      = false
    canary            = 0
  }

  group "goshelf" {
    count = 1

    # Drain service registration before SIGTERM so Traefik stops new traffic
    # prior to SQLite writer exit (supports stop-before-start).
    shutdown_delay = "10s"

    network {
      port "http" {
        static = 8580
      }
    }

    volume "moosefs-media" {
      type      = "host"
      source    = "moosefs-media"
      read_only = true
    }

    volume "moosefs-configs" {
      type      = "host"
      source    = "moosefs-configs"
      read_only = false
    }

    update {
      max_parallel      = 1
      health_check      = "checks"
      min_healthy_time  = "10s"
      healthy_deadline  = "5m"
      progress_deadline = "10m"
      auto_revert       = true
      auto_promote      = false
      canary            = 0
    }

    restart {
      attempts = 3
      delay    = "15s"
      interval = "5m"
      mode     = "fail"
    }

    task "goshelf" {
      driver = "docker"

      kill_timeout = "30s"

      # Workload identity for nomadVar access to nomad/jobs/goshelf.
      identity {
        name = "default"
        aud  = ["nomadproject.io"]
      }

      config {
        # Pin updated post-release to exact linux/amd64 digest.
        # Must match deploy/nomad/images.lock.hcl image_goshelf digest authority.
        # Digest-only production authority; human tag is traceability only.
        image = "ghcr.io/aleksclark/goshelf:v2026.8.2@sha256:ade4fdfb1a61e1eee618e468e869881a34e613cc950950178f0ca2b949583548"
        ports = ["http"]
      }

      env {
        LISTEN_ADDR = ":${NOMAD_PORT_http}"
        DB_PATH     = "/configs/goshelf/goshelf.db"
        # Local mount root of moosefs-media (hosts both ebooks/ and audiobooks/).
        MEDIA_PATH = "/audiobooks"
        # Readarr absolute path root rewritten onto MEDIA_PATH (strict; no segment strip).
        READARR_MEDIA_ROOT = "/media"
        # Durable Readarr fleet hostname (never node IP or alternate product ports).
        READARR_URL = "https://readarr.fleet.clark.team"
      }

      # API key from Nomad Variable — never inline.
      template {
        destination = "secrets/goshelf.env"
        env         = true
        change_mode = "restart"
        data        = <<-EOF
READARR_API_KEY={{ with nomadVar "nomad/jobs/goshelf" }}{{ .readarr_api_key }}{{ end }}
        EOF
      }

      volume_mount {
        volume      = "moosefs-media"
        destination = "/audiobooks"
        read_only   = true
      }

      volume_mount {
        volume      = "moosefs-configs"
        destination = "/configs"
        read_only   = false
      }

      resources {
        cpu    = 200
        memory = 128
      }

      service {
        name     = "goshelf"
        port     = "http"
        provider = "nomad"
        tags = [
          "traefik.enable=true",
          "traefik.http.routers.goshelf.rule=Host(`books.fleet.clark.team`) || Host(`books.clark.team`)",
          "traefik.http.routers.goshelf.entrypoints=websecure",
          "traefik.http.routers.goshelf.tls.certresolver=letsencrypt",
        ]

        # Liveness: process HTTP shell.
        check {
          name     = "goshelf-liveness"
          type     = "http"
          path     = "/healthz"
          interval = "15s"
          timeout  = "3s"
        }

        # Readiness: Readarr durable /ping via app /readyz.
        check {
          name     = "goshelf-readiness"
          type     = "http"
          path     = "/readyz"
          interval = "15s"
          timeout  = "5s"
        }
      }
    }
  }
}
