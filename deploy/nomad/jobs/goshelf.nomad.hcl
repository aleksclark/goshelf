// Authoritative Nomad jobspec for GoShelf (project-owned).
// Deploy via reviewed CAS from this file after release image digest pin.
// Secrets: Nomad Variable nomad/jobs/goshelf key readarr_api_key (workload identity).
//
// Image pin is updated after each release to an immutable tag@digest.
// Do not embed API keys or media titles/paths beyond mount classes.

job "goshelf" {
  datacenters = ["home"]
  type        = "service"

  group "goshelf" {
    count = 1

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

      # Workload identity for nomadVar access to nomad/jobs/goshelf.
      identity {
        name = "default"
        aud  = ["nomadproject.io"]
      }

      config {
        # Pin updated post-release to exact linux/amd64 digest.
        # Placeholder tag matches last known good until release workflow lands new digest.
        image = "ghcr.io/aleksclark/goshelf:v2026.8.0@sha256:26bdd62797af2fe4ca8cadd4af2b0da9cf8e646509719b963bd2df8e30ff52cb"
        ports = ["http"]
      }

      env {
        LISTEN_ADDR = ":${NOMAD_PORT_http}"
        DB_PATH     = "/configs/goshelf/goshelf.db"
        MEDIA_PATH  = "/audiobooks/audiobooks"
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
