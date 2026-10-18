# Entry points for running the whole system locally. See docs/running-locally.md for what each
# step does and how to run a single service from the IDE instead.
SHELL := /bin/bash

# Load test: k6 virtual users per journey (the script's default is 15). Lower it on a small host.
VUS ?=
# Chaos experiment to run: a script in chaos/ and its arguments.
CHAOS ?= 02_service_kill.sh search-service 30

.PHONY: help up up-all wait down clean ps logs warmup smoke load chaos

help: ## List the targets
	@grep -E '^[a-z-]+:.*## ' $(MAKEFILE_LIST) | awk -F':.*## ' '{printf "  %-9s %s\n", $$1, $$2}'

up: ## Build and start everything, then wait until every service is UP
	@echo "first start: loads the sample_mflix dataset (~400 MB download), which takes a few minutes"
	docker compose up -d --build
	@scripts/wait-healthy.sh

up-all: ## Like up, plus config-server (no service reads from it yet, ADR-0009)
	@COMPOSE_PROFILES=config $(MAKE) --no-print-directory up

wait: ## Wait until every service is UP
	@scripts/wait-healthy.sh

down: ## Stop everything, keeping the data volumes
	docker compose --profile config down

clean: ## Stop everything and delete the data volumes (the downloaded dataset is kept)
	docker compose --profile config down
	@# The project name comes from the directory, so a second clone never touches this one's volumes.
	project=$$(docker compose config --format json | jq -r .name); \
	docker volume ls -q --filter label=com.docker.compose.project=$$project | grep -v '_mflix-dump$$' \
		| xargs -r docker volume rm

ps: ## Show container status
	docker compose ps -a

logs: ## Follow the app services' logs
	docker compose logs -f --tail=50 api-gateway catalog-service search-service review-service \
		recommendation-service user-service assistant-service

warmup: ## Warm up the read paths before a measured run
	@scripts/warmup.sh

smoke: warmup ## Warm up, then one k6 iteration per journey to check the system end to end
	k6 run -e SMOKE=1 loadtest/user-journey.js

load: warmup ## Warm up, then the k6 load test (3 minutes); VUS=5 for a smaller host
	k6 run $(if $(VUS),-e STRESS_VUS=$(VUS)) loadtest/user-journey.js

chaos: ## Run a chaos experiment, e.g. make chaos CHAOS="03_network_fault.sh delay search-service 8000 30"
	chaos/$(CHAOS)
