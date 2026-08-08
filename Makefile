# Cove build orchestration.
#
#   make            # build everything (Go backend + Compose Desktop app)
#   make run        # build, then launch the app (it spawns ./cove itself)
#   make go|app     # build a single component
#   make test       # Go + Kotlin test suites
#   make test-all   # broad local CI approximation
#   make patch      # bump patch version, stage all pending changes, commit, tag
#                   # (optionally: make patch TITLE="..." MSG="..." to override the
#                   # commit title / add a commit message body note)
#                   # (then: git push origin master v<ver>)
#   make clean      # remove build artifacts

VERSION     := $(shell git describe --tags --always --dirty 2>/dev/null || echo dev)
GO_BIN      := cove
KOTLIN_DIR  := app

# Auto-detect injected private implementations and compose build tags.
# After `make inject-private`, client.go and/or discover.go are present
# and the real implementations compile in automatically.
_empty :=
_space := $(_empty) $(_empty)
, := ,
_PRIVATE_TAGS := $(strip \
  $(if $(wildcard internal/supabase/client.go),supabase) \
  $(if $(wildcard internal/discover/discover.go),discover))
_BUILD_TAGS   := $(subst $(_space),$(,),$(_PRIVATE_TAGS))
_TAG_FLAGS    := $(if $(_BUILD_TAGS),-tags $(_BUILD_TAGS))

.PHONY: all build run dev hot go app test test-kotlin test-all test-go test-build \
        test-workflows test-release-notes test-security test-private \
        patch minor major clean inject-private

all: build

## Build the Go backend and the Compose Desktop app.
build: go app

## Compose Desktop app — configure, compile, and package.
app:
	cd $(KOTLIN_DIR) && ./gradlew :desktop:build

## Go backend binary (repo root). Static build — no cgo.
## Private build tags (supabase, discover) are added automatically when the
## corresponding implementation files are present (run `make inject-private` first).
go:
	$(if $(TMDB_API_KEY),,$(warning TMDB_API_KEY not set — TMDB calls will fail at runtime unless a .env file is present next to the binary))
	CGO_ENABLED=0 go build $(_TAG_FLAGS) -ldflags "-X main.Version=$(VERSION)" -o $(GO_BIN) .

## Build everything, then launch the Compose Desktop app.
run: build
	cd $(KOTLIN_DIR) && COVE_BACKEND_PATH=$(abspath $(GO_BIN)) ./gradlew :desktop:run

## Alias for `make run`.
dev: run

## Tight Compose UI loop. Starts Cove with the real Go sidecar and automatically
## recompiles/reloads Kotlin UI changes without recreating the window.
hot: go
	cd $(KOTLIN_DIR) && COVE_BACKEND_PATH=$(abspath $(GO_BIN)) ./gradlew :desktop:hotRun --auto

## Kotlin (shared KMP + desktop JVM) test suite.
test-kotlin:
	cd $(KOTLIN_DIR) && ./gradlew test

## Complete day-to-day test suite. This is the recommended check before a PR:
## public and Supabase-tagged Go tests plus the Kotlin shared/desktop suites.
test: test-go test-kotlin

## Go checks shared with CI. Coverage profiles are left in the repository root
## (and ignored by git) so they can be inspected with `go tool cover`.
test-go:
	@files="$$(git ls-files --cached --others --exclude-standard '*.go')"; \
	unformatted="$$(gofmt -l $$files)"; \
	if [ -n "$$unformatted" ]; then \
		echo "The following Go files need gofmt:"; \
		echo "$$unformatted"; \
		exit 1; \
	fi
	go vet ./...
	go test -count=1 -covermode=atomic -coverprofile=coverage-public.out ./...
	go vet -tags supabase ./...
	go test -count=1 -tags supabase -covermode=atomic -coverprofile=coverage-supabase.out ./...
	go test -count=1 -race -tags supabase ./...

## Cross-compile the tagged Go code in the same configurations used by CI.
test-build:
	CGO_ENABLED=0 GOOS=linux GOARCH=amd64 go build -tags supabase ./...
	CGO_ENABLED=0 GOOS=windows GOARCH=amd64 go build -tags supabase ./...

## Lint the GitHub Actions definitions. Go downloads actionlint on first use.
test-workflows: test-release-notes
	@command -v shellcheck >/dev/null 2>&1 || { echo "shellcheck is required for full workflow linting (Arch: sudo pacman -S shellcheck)."; exit 1; }
	bash scripts/workflow-local-actions_test.sh
	go run github.com/rhysd/actionlint/cmd/actionlint@v1.7.12 .github/workflows/*.yml

## Verify that generated release notes contain only user-facing fixes/features.
test-release-notes:
	bash scripts/release-notes_test.sh

## Dependency and reachable-vulnerability checks. These require network access.
test-security:
	go run golang.org/x/vuln/cmd/govulncheck@v1.6.0 ./...

## Maintainer-only release-tag validation. Run `make inject-private` first.
test-private:
	@test -f internal/discover/discover.go || { echo "Private Discover sources are not injected; run 'make inject-private' first."; exit 1; }
	bash scripts/check-private-sync.sh
	go test -count=1 -tags supabase,discover ./...
	go vet -tags supabase,discover ./...

## Broadest environment-independent local approximation of CI. The private
## integration checks remain opt-in because they require private submodules.
test-all: test-workflows test test-build test-security

## Bump the version in the root VERSION file, stage all pending changes,
## commit, and tag for release. `make patch` bumps 0.22.5 -> 0.22.6, `make
## minor` bumps 0.22.5 -> 0.23.0, `make major` bumps 0.22.5 -> 1.0.0.
## VERSION replaced web/package.json as the source of truth when the Svelte
## frontend was removed; app/desktop reads it for packageVersion. Pass
## TITLE="..." to override
## the default commit title and/or MSG="..." to add a commit message body
## note (multi-line is fine). User-facing `fix` and `feat` conventional commits
## since the last release tag are appended to the commit body underneath MSG as
## markdown links ([subject](commit url)). Merge, chore, docs, dependency, and
## other internal commits are omitted from the GitHub release notes.
## Then push with: git push origin master v<version>
##
## TITLE/MSG reach the recipe via the environment ($$TITLE/$$MSG), NOT via
## make's $(...) substitution: make pastes $(MSG) into the recipe text
## verbatim, so a message containing real newlines used to split the recipe
## into broken shell lines ("unexpected EOF while looking for matching quote").
## Environment values pass through the shell untouched, newlines and all.
export TITLE MSG
patch minor major:
	@CUR=$$(cat VERSION); \
	NEW_VER=$$(awk -F. -v part=$@ '{ \
		if (part == "patch")      printf "%d.%d.%d", $$1, $$2, $$3+1; \
		else if (part == "minor") printf "%d.%d.0",  $$1, $$2+1; \
		else                      printf "%d.0.0",   $$1+1; \
	}' VERSION); \
	printf '%s\n' "$$NEW_VER" > VERSION; \
	sed -i "s/^packageVersion *= *\".*\"/packageVersion = \"$$NEW_VER\"/" $(KOTLIN_DIR)/desktop/build.gradle.kts; \
	TITLE="$${TITLE:-chore: bump version to v$$NEW_VER}"; \
	LAST_TAG=$$(git describe --tags --abbrev=0 2>/dev/null || true); \
	LOG=""; \
	if [ -n "$$LAST_TAG" ]; then \
		LOG=$$(bash scripts/release-notes.sh "$$LAST_TAG" HEAD); \
	fi; \
	if [ -n "$$MSG" ] && [ -n "$$LOG" ]; then \
		BODY=$$(printf '%s\n\nChanges from %s:\n%s' "$$MSG" "$$LAST_TAG" "$$LOG"); \
	elif [ -n "$$LOG" ]; then \
		BODY=$$(printf 'Changes from %s:\n%s' "$$LAST_TAG" "$$LOG"); \
	else \
		BODY="$$MSG"; \
	fi; \
	sed -i "s|<release version=\"[^\"]*\" date=\"[^\"]*\"/>|<release version=\"$$NEW_VER\" date=\"$$(date +%Y-%m-%d)\"/>|" flatpak/io.github.coveninja.Cove.metainfo.xml && \
	git add -A && \
	if [ -n "$$BODY" ]; then \
		git commit -m "$$TITLE" -m "$$BODY"; \
	else \
		git commit -m "$$TITLE"; \
	fi && \
	git tag "v$$NEW_VER" && \
	echo "" && \
	echo "  Tagged v$$NEW_VER — push with: git push origin master v$$NEW_VER"

## Pull private submodules and inject implementation files into internal/.
inject-private:
	git submodule update --init
	cp _private/cove-auth/*.go internal/supabase/
	cp _private/cove-discover/*.go internal/discover/

## Remove build artifacts.
clean:
	rm -f $(GO_BIN)
	rm -f coverage-*.out
	rm -rf $(KOTLIN_DIR)/build $(KOTLIN_DIR)/*/build $(KOTLIN_DIR)/.gradle
