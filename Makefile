# Cove build orchestration.
#
#   make            # build everything (Go backend, web frontend, Qt shell)
#   make run        # build everything, then launch the shell
#   make dev        # regenerate TS types, build everything, launch the shell
#   make go|web|qt  # build a single component
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
ANDROID_HOME     ?= $(HOME)/Android/Sdk
ANDROID_NDK_HOME ?= $(ANDROID_HOME)/ndk/27.2.12479018

# Targets that drive the Android toolchain export these so that gomobile,
# sdkmanager, avdmanager, and Gradle all pick up the same SDK without
# requiring the caller to pre-export them in the shell.
export ANDROID_HOME
export ANDROID_NDK_HOME

.PHONY: all build run dev go web qt qt-configure generate web-dev shell patch minor major clean android-aar android android-install tv-avd tv-install

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
## note (multi-line is fine). All commits since the last release tag are
## appended to the commit body underneath MSG as markdown links
## ([subject](commit url)) — these render clickable in GitHub release notes
## and PR/issue bodies, but show as raw markdown in the plain commit view.
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
		LOG=$$(git log --reverse --format="- [%s](https://github.com/coveninja/cove/commit/%H)" "$$LAST_TAG"..HEAD); \
	fi; \
	if [ -n "$$MSG" ] && [ -n "$$LOG" ]; then \
		BODY=$$(printf '%s\n\Changes from %s:\n%s' "$$MSG" "$$LAST_TAG" "$$LOG"); \
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

## Build the gomobile AAR for Android arm64 + amd64 (API 29+).
## arm64 targets real devices; amd64 is required for the x86_64 emulator AVD.
## Prerequisites:
##   1. gomobile: go install golang.org/x/mobile/cmd/gomobile@latest && gomobile init
##   2. ANDROID_HOME / ANDROID_NDK_HOME set (defaults above, override as needed)
##   3. JDK 17 (gomobile invokes javac when packaging the AAR)
## Private build tags (supabase, discover) are added automatically when the
## corresponding implementation files are present (run `make inject-private` first).
android-aar: web
	mkdir -p android/app/libs
	PATH=$(HOME)/go/bin:$(PATH) gomobile bind -target android/arm64,android/amd64 -androidapi 29 -tags $(_ANDROID_TAGS) -o android/app/libs/cove.aar ./mobile

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
tv-install: android
	adb install -r android/app/build/outputs/apk/debug/app-debug.apk
	adb shell am start -n com.coveninja.cove/.WebViewActivity

## Remove build artifacts.
clean:
	rm -f $(GO_BIN)
	rm -rf $(WEB_DIR)/dist
	rm -rf $(QT_BUILD)
