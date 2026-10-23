# Entry points for running the whole system locally. See docs/running-locally.md for what each
# step does and how to run a single service from the IDE instead.
SHELL := /bin/bash

# Load test: k6 virtual users per journey (the script's default is 15). Lower it on a small host.
VUS ?=
# Chaos experiment to run: a script in chaos/ and its arguments.
CHAOS ?= 02_service_kill.sh search-service 30

.PHONY: help up up-all assistant eval-up eval-metrics wait down clean ps logs warmup demo smoke load chaos eval eval-test

help: ## List the targets
	@grep -E '^[a-z-]+:.*## ' $(MAKEFILE_LIST) | awk -F':.*## ' '{printf "  %-9s %s\n", $$1, $$2}'

up: ## Build and start the stack (all but assistant-service and config-server), then wait until UP
	@echo "first start: loads the sample_mflix dataset (~400 MB download), which takes a few minutes"
	@# Build before starting anything: a build running alongside mflix-mongo's first boot left
	@# its replica set uninitialized for good (ADR-0010 Update 10).
	docker compose build
	docker compose up -d
	@scripts/wait-healthy.sh

up-all: ## Like up, plus assistant-service and config-server (needs more than a 4 GB Docker VM)
	@COMPOSE_PROFILES=assistant,config $(MAKE) --no-print-directory up

assistant: ## Add assistant-service to a running stack (pass OPENAI_API_KEY from the shell)
	docker compose --profile assistant up -d --build assistant-service
	@COMPOSE_PROFILES=assistant scripts/wait-healthy.sh

# What the assistant's tools reach, called on :8086 with an X-User-Id header: no gateway, Kafka,
# catalog/review/user services or metrics stack. Data comes from the volumes, so run `make up` once
# first: with an empty movies_search, search-service would wait on catalog-service for its backfill.
# docker-compose.eval.yml turns off what would wait on the services left out.
EVAL_APPS := assistant-service search-service recommendation-service
EVAL_SERVICES := $(EVAL_APPS) mflix-mongo mongo redis postgres jaeger
EVAL_COMPOSE := docker compose -f docker-compose.yml -f docker-compose.eval.yml --profile assistant

eval-up: ## Start only what the assistant evals need (after one `make up` has loaded the data)
	@# Build first, as in `up` (ADR-0010 Update 10).
	$(EVAL_COMPOSE) build $(EVAL_APPS)
	$(EVAL_COMPOSE) up -d --no-deps $(EVAL_SERVICES)
	@WAIT_SERVICES="search-service:8082 recommendation-service:8084 assistant-service:8086" scripts/wait-healthy.sh

eval-metrics: ## Add Prometheus and Grafana (:3001) to the eval stack, for the assistant dashboard
	$(EVAL_COMPOSE) up -d --no-deps prometheus grafana

wait: ## Wait until every service is UP
	@scripts/wait-healthy.sh

down: ## Stop everything, keeping the data volumes
	docker compose --profile assistant --profile config down

clean: ## Stop everything and delete the data volumes (the downloaded dataset is kept)
	docker compose --profile assistant --profile config down
	@# The project name comes from the directory, so a second clone never touches this one's volumes.
	project=$$(docker compose config --format json | jq -r .name); \
	docker volume ls -q --filter label=com.docker.compose.project=$$project | grep -v '_mflix-dump$$' \
		| xargs -r docker volume rm

ps: ## Show container status
	docker compose ps -a

logs: ## Follow the app services' logs
	docker compose --profile assistant logs -f --tail=50 api-gateway catalog-service search-service review-service \
		recommendation-service user-service assistant-service

warmup: ## Warm up the read paths before a measured run
	@scripts/warmup.sh

demo: warmup ## Warm up, seed a few fans' reviews, then walk a new user through the system
	@scripts/seed-demo-data.sh
	@scripts/demo.sh

smoke: warmup ## Warm up, then one k6 iteration per journey to check the system end to end
	k6 run -e SMOKE=1 loadtest/user-journey.js

load: warmup ## Warm up, then the k6 load test (3 minutes); VUS=5 for a smaller host
	k6 run $(if $(VUS),-e STRESS_VUS=$(VUS)) loadtest/user-journey.js

chaos: ## Run a chaos experiment, e.g. make chaos CHAOS="03_network_fault.sh delay search-service 12000 30"
	chaos/$(CHAOS)

# Scores assistant-service's answers against the catalog (evals/README.md). Needs assistant-,
# search- and recommendation-service running and real model calls: about $0.015 a run on gpt-6-luna.
eval: ## Run the assistant evals, e.g. make eval EVAL_ARGS="--tag filters --repeat 3"
	python3 evals/run.py $(EVAL_ARGS)

eval-test: ## Unit-test the eval scorers (offline, no services or model calls)
	cd evals && python3 -m unittest discover -s tests -t .
