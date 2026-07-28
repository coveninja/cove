# Cove build orchestration.
#
#   make            # build everything (Go backend, web frontend, Qt shell)
#   make run        # build everything, then launch the shell
#   make dev        # regenerate TS types, build everything, launch the shell
#   make go|web|qt  # build a single component
#   make test       # run the complete Go + web test suites
#   make test-all   # run the broad local CI suite (adds Qt, Android, security)
#   make web-dev    # Vite dev server (browser only — no mpv bridge)
#   make patch      # bump patch version, stage all pending changes, commit, tag
#                   # (optionally: make patch TITLE="..." MSG="..." to override the
#                   # commit title / add a commit message body note)
#                   # (then: git push origin master v<ver>)
#   make clean      # remove build artifacts

VERSION   := $(shell git describe --tags --always --dirty 2>/dev/null || echo dev)
GO_BIN    := cove
WEB_DIR   := web
QT_DIR    := qt
QT_BUILD  := $(QT_DIR)/build
SHELL_BIN := $(QT_BUILD)/cove_shell

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
_ANDROID_TAGS := embedweb$(if $(_BUILD_TAGS),$(,)$(_BUILD_TAGS))

# Android SDK paths — override on the command line if your SDK lives elsewhere.
# ANDROID_NDK_HOME points to the versioned NDK installed by sdkmanager.
ANDROID_NDK_VERSION := 27.2.12479018
ANDROID_HOME        ?= $(HOME)/Android/Sdk
ANDROID_NDK_HOME    ?= $(ANDROID_HOME)/ndk/$(ANDROID_NDK_VERSION)

# Targets that drive the Android toolchain export these so that gomobile,
# sdkmanager, avdmanager, and Gradle all pick up the same SDK without
# requiring the caller to pre-export them in the shell.
export ANDROID_HOME
export ANDROID_NDK_HOME

.PHONY: all build run run-debug dev go web qt qt-configure generate web-dev shell test test-all test-go test-web test-build test-workflows test-release-notes test-security test-qt test-android test-android-connected test-private patch minor major clean android-aar android android-install tv-avd tv-install hot hot-debug inject-private

all: build

## Build all three components.
build: go web qt

## Go backend binary (repo root). Static build — no cgo.
## Private build tags (supabase, discover) are added automatically when the
## corresponding implementation files are present (run `make inject-private` first).
go:
	$(if $(TMDB_API_KEY),,$(warning TMDB_API_KEY not set — TMDB calls will fail at runtime unless a .env file is present next to the binary))
	CGO_ENABLED=0 go build $(_TAG_FLAGS) -ldflags "-X main.Version=$(VERSION)" -o $(GO_BIN) .

## Frontend → web/dist (Vite).
web:
	cd $(WEB_DIR) && npm run build

## Configure the Qt build dir. Run once, or after CMakeLists.txt changes.
qt-configure:
	cmake -S $(QT_DIR) -B $(QT_BUILD)

## Build the Qt shell, configuring the build dir first if it's missing.
qt:
	@test -d $(QT_BUILD) || cmake -S $(QT_DIR) -B $(QT_BUILD)
	cmake --build $(QT_BUILD)

## Regenerate TypeScript types from Go structs (tygo).
generate:
	tygo generate

## Build everything, then run the shell: it serves web/dist and spawns ./cove.
run: build
	$(SHELL_BIN) --backend ./$(GO_BIN) --webroot ./$(WEB_DIR)/dist

## Rebuild only the frontend and relaunch the shell (fast frontend iteration).
shell: web
	$(SHELL_BIN) --backend ./$(GO_BIN) --webroot ./$(WEB_DIR)/dist

## Full dev cycle: regenerate types, build all, launch.
dev: generate run

## Vite dev server in a browser. The mpv bridge is absent here, so the player
## shows "unavailable", but the rest of the UI works against the Go backend.
web-dev:
	cd $(WEB_DIR) && npm run dev

## Complete day-to-day test suite. This is the recommended check before a PR:
## public and Supabase-tagged Go tests plus the web unit/browser/type/lint/build
## checks. Run `npx playwright install chromium` in web/ once before the first
## browser-test run.
test: test-go test-web

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

## Frontend unit coverage, static checks, production build, and Playwright flows.
test-web:
	cd $(WEB_DIR) && npm test
	cd $(WEB_DIR) && npm run check
	cd $(WEB_DIR) && npm run lint
	cd $(WEB_DIR) && npm run build
	cd $(WEB_DIR) && npm run test:e2e

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
	cd $(WEB_DIR) && npm audit --audit-level=low

## Verify that the desktop shell configures, compiles, and passes its Qt tests.
test-qt:
	cmake -S $(QT_DIR) -B $(QT_BUILD) -DCOVE_BUILD_TESTS=ON
	cmake --build $(QT_BUILD)
	ctest --test-dir $(QT_BUILD) --output-on-failure

## Rebuild the gomobile AAR, then run Android lint/JVM tests and compile the
## instrumentation APK. A configured Android SDK/NDK and JDK 17 are required.
test-android: android-aar
	cd android && ./gradlew lintDebug testDebugUnitTest assembleDebugAndroidTest

## Run the Android instrumentation tests on an already-running device/emulator.
test-android-connected: test-android
	cd android && ./gradlew connectedDebugAndroidTest

## Maintainer-only release-tag validation. Run `make inject-private` first.
test-private:
	@test -f internal/discover/discover.go || { echo "Private Discover sources are not injected; run 'make inject-private' first."; exit 1; }
	bash scripts/check-private-sync.sh
	go test -count=1 -tags supabase,discover ./...
	go vet -tags supabase,discover ./...

## Broadest environment-independent local approximation of CI. The private
## integration and connected-emulator checks remain opt-in because they require
## private submodules or a running Android target.
test-all: test-workflows test test-build test-security test-qt test-android

run-debug: build
	QTWEBENGINE_REMOTE_DEBUGGING=9222 $(SHELL_BIN) --backend ./$(GO_BIN) --webroot ./$(WEB_DIR)/dist

## Hot-reload frontend dev: Vite serves the UI in-window with HMR via the
## shell's --dev mode. Builds the backend + shell but NOT the frontend (Vite
## serves it live). Requires the stripCspInDev() plugin in vite.config.ts.
hot: go qt
	bash scripts/dev-hot.sh

## Same as `hot`, with QtWebEngine remote devtools on :9222 (open in a browser).
hot-debug: go qt
	QTWEBENGINE_REMOTE_DEBUGGING=9222 bash scripts/dev-hot.sh

## Bump the version in web/package.json, stage all pending changes, commit,
## and tag for release. `make patch` bumps 0.22.5 -> 0.22.6, `make minor`
## bumps 0.22.5 -> 0.23.0, `make major` bumps 0.22.5 -> 1.0.0 (the target
## name is passed straight to `npm version`). Pass TITLE="..." to override
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
	cd $(WEB_DIR) && npm version $@ --no-git-tag-version
	@NEW_VER=$$(node -p "require('./$(WEB_DIR)/package.json').version"); \
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

## Build the gomobile AAR for all four Android ABIs (API 29+).
## arm64 targets modern devices, arm covers armv7-only TV boxes (e.g. Mibox),
## amd64 is required for the x86_64 emulator AVD. The ABI set must match what
## the libmpv dependency ships (all four): if an ABI dir exists in the APK
## without libgojni.so, Android may select it as the primary ABI and the
## backend fails to load.
## Prerequisites:
##   1. gomobile: go install golang.org/x/mobile/cmd/gomobile@latest && gomobile init
##   2. ANDROID_HOME / ANDROID_NDK_HOME set (defaults above, override as needed)
##   3. JDK 17 (gomobile invokes javac when packaging the AAR)
## Private build tags (supabase, discover) are added automatically when the
## corresponding implementation files are present (run `make inject-private` first).
## GOFLAGS disables VCS stamping: gomobile compiles ./gobind inside a temporary
## work directory under $TMPDIR, and when that is a separate mount (tmpfs on
## most Linux setups) git aborts at the filesystem boundary with exit 128,
## which the Go toolchain treats as fatal rather than skipping the stamp.
android-aar: web
	mkdir -p android/app/libs
	PATH=$(HOME)/go/bin:$(PATH) GOFLAGS=-buildvcs=false gomobile bind -target android/arm,android/arm64,android/386,android/amd64 -androidapi 28 -tags $(_ANDROID_TAGS) -o android/app/libs/cove.aar ./mobile

## Build the Android debug APK. Requires all android-aar prerequisites above.
android: android-aar
	cd android && ./gradlew assembleDebug

## Install the debug APK on a connected device / running emulator and launch.
## For UI-only iterations that don't require an AAR rebuild, run:
##   cd android && ./gradlew installDebug
##
## Svelte HMR on device (edit web/ with instant reload, no reinstall):
##   1. cd web && npm run dev
##   2. adb reverse tcp:5173 tcp:5173
##   3. add WEB_URL=http://127.0.0.1:5173 to android/local.properties, reinstall once
##   Remove the WEB_URL line to return to the embedded UI (127.0.0.1:6969).
## mpv video renders BLACK under the emulator's SwiftShader GPU — test playback
## on a real device or a windowed emulator started with -gpu host.
android-install: android
	adb install -r android/app/build/outputs/apk/debug/app-debug.apk
	adb shell am start -n com.coveninja.cove/.WebViewActivity

## One-time: create the Android TV emulator AVD (AOSP TV, API 36, x86_64 —
## the only x86_64 TV image left in Google's SDK repo; google_atv is arm64-only now).
## Launch it windowed with GPU passthrough (mpv is black under SwiftShader):
##   $(ANDROID_HOME)/emulator/emulator -avd cove-tv -gpu host
tv-avd:
	$(ANDROID_HOME)/cmdline-tools/latest/bin/sdkmanager "system-images;android-36;android-tv;x86_64"
	$(ANDROID_HOME)/cmdline-tools/latest/bin/avdmanager create avd --name cove-tv --package "system-images;android-36;android-tv;x86_64" --device tv_1080p --force
	# avdmanager defaults to hw.keyboard=no, which blocks ALL host-keyboard input
	# (incl. arrow keys / Enter) — with it enabled the host keyboard acts as the
	# remote: arrows = D-pad, Enter = OK, Esc = Back.
	sed -i 's/^hw.keyboard = no/hw.keyboard = yes/' $(HOME)/.android/avd/cove-tv.avd/config.ini
	@echo "AVD 'cove-tv' created. Launch with: $(ANDROID_HOME)/emulator/emulator -avd cove-tv -gpu host"

## Install + launch on the TV emulator/device. Same APK as android-install —
## the app picks the TV shell at runtime (UiModeManager → __covePlatform).
tv-install: android-install

## Remove build artifacts.
clean:
	rm -f $(GO_BIN)
	rm -f coverage-*.out
	rm -rf $(WEB_DIR)/dist
	rm -rf $(QT_BUILD)
