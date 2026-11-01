# Developer entry points. `make setup` once, then `make dev`. `make help` lists the targets.

# The server needs JDK 21+, which may not be the default `java`. On macOS pick one with java_home;
# elsewhere, or to choose another, pass JAVA_HOME=... on the command line.
JDK21 := $(shell /usr/libexec/java_home -v 21+ 2>/dev/null)
ifneq ($(JDK21),)
JAVA_HOME := $(JDK21)
endif
export JAVA_HOME

GRADLE_SRC := cd src && ./gradlew
GRADLE_APP := cd apps/server && ./gradlew
PNPM := pnpm -C apps/web

.PHONY: help setup build test lint check format generate dev up down smoke clean

help: ## List the targets
	@grep -E '^[a-z-]+:.*## ' $(MAKEFILE_LIST) | awk -F':.*## ' '{ printf "  %-10s %s\n", $$1, $$2 }'

setup: ## Check the toolchain, install web deps, fetch server deps and compile
	@test -x "$(JAVA_HOME)/bin/java" || { echo "JDK 21+ not found; install one or pass JAVA_HOME=..." >&2; exit 1; }
	@"$(JAVA_HOME)/bin/java" -version 2>&1 | awk -F'"' '/version/ { split($$2, v, "."); if (v[1] < 21) { print "JDK at $(JAVA_HOME) is " $$2 ", need 21+" > "/dev/stderr"; exit 1 } }'
	@for t in node pnpm curl python3; do \
	  command -v $$t >/dev/null || { echo "missing $$t (needed by setup, dev or smoke)" >&2; exit 1; }; \
	done
	@command -v docker >/dev/null || echo "note: docker not found; only needed for make up/down"
	$(PNPM) install --frozen-lockfile
	$(GRADLE_SRC) assemble
	$(GRADLE_APP) assemble
	@echo "setup done; next: make dev"

build: ## Compile the server and build the web apps (no tests)
	$(GRADLE_SRC) assemble
	$(GRADLE_APP) assemble
	$(PNPM) build

test: ## Server unit and conformance tests, web tests
	$(GRADLE_SRC) test
	$(GRADLE_APP) test
	$(PNPM) -r test

lint: ## Server format and Checkstyle, web typecheck and lint
	$(GRADLE_SRC) spotlessCheck checkstyleMain checkstyleTest checkstyleTestFixtures
	$(GRADLE_APP) spotlessCheck checkstyleMain checkstyleTest
	$(PNPM) typecheck
	$(PNPM) lint

check: ## What CI runs, minus the compose e2e: full server build, contract diff, web checks
	$(GRADLE_SRC) build
	$(GRADLE_APP) build
	$(PNPM) generate
	git diff --exit-code -- apps/web/packages/contract/src
	$(PNPM) typecheck
	$(PNPM) lint
	$(PNPM) build

format: ## Apply the Java and Gradle formatting
	$(GRADLE_SRC) spotlessApply
	$(GRADLE_APP) spotlessApply

generate: ## Regenerate the TS contract types from src/schema/screen.schema.json
	$(PNPM) generate

dev: ## Run the stack locally without Docker (Hydra, Faye, Login UI, demo client, Admin UI)
	deploy/dev.sh

up: ## Run the stack in Docker (deploy/compose.yaml)
	docker compose -f deploy/compose.yaml up -d --build --remove-orphans

down: ## Stop the Docker stack
	docker compose -f deploy/compose.yaml down

smoke: ## Scripted sign ins against a freshly started stack (make dev or make up)
	deploy/smoke.sh

clean: ## Remove server and web build outputs (keeps node_modules and deploy/.bin)
	$(GRADLE_SRC) clean
	$(GRADLE_APP) clean
	rm -rf apps/web/apps/*/.next apps/web/apps/*/*.tsbuildinfo apps/web/packages/*/*.tsbuildinfo
