.PHONY: build run test clean install analyze shell models status clear voice-setup help

# ── Core targets ────────────────────────────────────────────────────────────────

## Build the project
build:
	./gradlew build

## Run with arguments: make run ARGS="shell /path/to/repo"
run:
	./gradlew run --args="$(ARGS)"

## Run all tests
test:
	./gradlew test

## Clean build artifacts
clean:
	./gradlew clean

## Build and install the distribution (creates bin/forge launcher)
install:
	./gradlew installDist

# ── Convenience shortcuts (require install first) ───────────────────────────────

FORGE_BIN = ./build/install/forge/bin/forge

## Full repository analysis: make analyze REPO=/path/to/repo
analyze:
	$(FORGE_BIN) analyze $(REPO)

## Interactive REPL shell: make shell REPO=/path/to/repo
shell:
	$(FORGE_BIN) shell $(REPO)

## List configured and available models
models:
	$(FORGE_BIN) models

## Show Forge status (Ollama, workspaces, config)
status:
	$(FORGE_BIN) status

## Clear workspace: make clear REPO=/path/to/repo (or just 'make clear' for all)
clear:
ifdef REPO
	$(FORGE_BIN) clear $(REPO)
else
	$(FORGE_BIN) clear
endif

## Download Whisper model for voice input: make voice-setup MODEL=base
voice-setup:
ifdef MODEL
	$(FORGE_BIN) voice-setup --model $(MODEL)
else
	$(FORGE_BIN) voice-setup
endif

## Ask a question: make ask REPO=/path/to/repo Q="your question"
ask:
	$(FORGE_BIN) ask $(REPO) "$(Q)"

## Show this help
help:
	@echo "Forge Makefile targets:"
	@echo ""
	@echo "  build          Build the project"
	@echo "  run            Run with ARGS, e.g. make run ARGS=\"shell .\""
	@echo "  test           Run all tests"
	@echo "  clean          Clean build artifacts"
	@echo "  install        Build and install distribution"
	@echo ""
	@echo "  analyze        Full analysis:  make analyze REPO=/path"
	@echo "  ask            Ask a question: make ask REPO=/path Q=\"question\""
	@echo "  shell          Interactive REPL: make shell REPO=/path"
	@echo "  models         List models"
	@echo "  status         Show Forge status"
	@echo "  clear          Clear workspace: make clear REPO=/path"
	@echo "  voice-setup    Download Whisper model: make voice-setup MODEL=base"
	@echo "  help           Show this help"
