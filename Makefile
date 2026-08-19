# GoShelf local lifecycle.
# Host path (`make dev`) is `go run .` and does not require Docker.
# Compose path is Stacklane-compatible: scripts/compose-dev.sh.

.PHONY: help dev check up status endpoints logs down destroy

help:
	@echo "Host (no Docker):"
	@echo "  make dev          go run .   (LISTEN_ADDR=:8080; READARR_* optional)"
	@echo "Compose (Stacklane):"
	@echo "  make check        fail-closed compose contract validation"
	@echo "  make up           check + build + start + print endpoints"
	@echo "  make status       compose ps + stacklane status"
	@echo "  make endpoints    FQDNs + direct 127.0.0.1 ephemeral URLs"
	@echo "  make logs         follow logs (Ctrl-C leaves stack up)"
	@echo "  make down         stop stack (volumes preserved; never -v)"
	@echo "  make destroy      down -v (requires CONFIRM=goshelf-<instance>-destroy)"

dev:
	go run .

check:
	bash scripts/compose-dev.sh check

up:
	bash scripts/compose-dev.sh up

status:
	bash scripts/compose-dev.sh status

endpoints:
	bash scripts/compose-dev.sh endpoints

logs:
	bash scripts/compose-dev.sh logs

down:
	bash scripts/compose-dev.sh down

destroy:
	bash scripts/compose-dev.sh destroy
