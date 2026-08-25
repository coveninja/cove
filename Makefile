# Cove build orchestration.
#
#   make            # build the Kotlin/Compose desktop and mobile apps
#   make run        # build, then launch the desktop app
#   make mobile     # build the Android phone/tablet/TV APK (one artifact, all three)
#   make run-tv     # launch the television shell in a desktop window (arrows = D-pad)
#   make hot-tv     # the same, with hot reload — the loop for tweaking the TV UI
#   make tv-avd     # create the Android TV emulator (once); then: emulator -avd cove-tv -gpu host
#   make tv-install # build and install the APK on a running TV emulator or device
#   make onboarding # open the first-run flow on the desktop shell (design harness)
#   make onboarding-tv      # the same flow in the television shell
#   make onboarding-mobile  # build, install and open it on a phone/tablet device or emulator
#   make onboarding-tv-install # the same, on an Android TV emulator or device
#   make hot-onboarding     # hot-reload loop for the desktop flow
#   make hot-onboarding-tv  # hot-reload loop for the television flow
#   make test       # Kotlin test suites
#   make test-all   # broad local CI approximation
#   make test-release       # rehearse the release-only workflow steps locally
#   make release-preflight  # the same, plus a GitHub secrets/variables check (needs gh)
#   make patch      # bump patch version, stage all pending changes, commit, tag
#                   # (optionally: make patch TITLE="..." MSG="..." to override the
#                   # commit title / add a commit message body note)
#                   # (then: git push origin master v<ver>)
#   make clean      # remove build artifacts

KOTLIN_DIR  := app

TV_AVD      := cove-tv
TV_IMAGE    := system-images;android-36;android-tv;x86_64

.PHONY: all build run dev hot app mobile test test-kotlin test-all test-build \
        test-workflows test-release-notes test-site-docs test-release release-preflight \
        patch minor major clean \
        run-tv hot-tv tv-avd tv-install \
        onboarding onboarding-tv onboarding-mobile onboarding-tv-install \
        hot-onboarding hot-onboarding-tv

all: build

## Build the Kotlin backend and both shared-UI application hosts.
build: app mobile

## Compose Desktop app — configure, compile, and package.
app:
	cd $(KOTLIN_DIR) && ./gradlew :desktop:build

## Android phone/tablet/TV app; the device selects the touch or ten-foot shell.
mobile:
	cd $(KOTLIN_DIR) && ./gradlew :mobile:assembleDebug

## Build, then launch the Compose Desktop app.
run: app
	cd $(KOTLIN_DIR) && ./gradlew :desktop:run

## Alias for `make run`.
dev: run

## Tight Compose UI loop with fixture data (no TMDB key required).
hot:
	cd $(KOTLIN_DIR) && ./gradlew :desktop:hotRun --auto

## The television shell in a desktop window. Arrow keys are the D-pad, Enter is OK and
## Escape is Back, so the whole TV UI can be driven without an emulator in the loop.
run-tv:
	cd $(KOTLIN_DIR) && ./gradlew :desktop:run --args="--backend-mode kotlin --tv"

## Hot-reload loop for the television shell: edit a Composable, save, see it on screen.
## Passed as task arguments rather than an environment variable — hotRun forks its JVM from
## the Gradle daemon, which inherits the environment the *daemon* started with, so an exported
## COVE_UI is invisible to it on any warm daemon.
hot-tv:
	cd $(KOTLIN_DIR) && ./gradlew :desktop:hotRun --auto --args="--tv"

## Create the Android TV emulator once. Run it afterwards with:
##   emulator -avd $(TV_AVD) -gpu host
## `-gpu host` is not optional — mpv renders black on the emulator's software GL.
tv-avd:
	@command -v avdmanager >/dev/null 2>&1 || { echo "avdmanager not on PATH (Android SDK cmdline-tools)."; exit 1; }
	sdkmanager "$(TV_IMAGE)"
	avdmanager create avd -n $(TV_AVD) -k "$(TV_IMAGE)" -d tv_1080p --force
	@# avdmanager writes hw.keyboard=no by default, which drops every host key event —
	@# including the arrows that are the only way to navigate a TV UI.
	@config="$$HOME/.android/avd/$(TV_AVD).avd/config.ini"; \
	  sed -i'' -e '/^hw\.keyboard=/d' -e '/^hw\.dpad=/d' "$$config"; \
	  printf 'hw.keyboard=yes\nhw.dpad=yes\n' >> "$$config"; \
	  echo "configured $$config"

## Build the debug APK and install it on the running TV emulator or device.
tv-install: mobile
	adb install -r $(KOTLIN_DIR)/mobile/build/outputs/apk/debug/mobile-debug.apk

# ── First-run onboarding ──────────────────────────────────────────────────────
#
# `--onboarding` previews the one-time flow without changing its completion flag. Fixtures are
# the default; use BACKEND=kotlin for live content and addon installation:
#
#   make onboarding BACKEND=kotlin
BACKEND ?= fixtures

## The first-run flow in the desktop shell.
onboarding:
	cd $(KOTLIN_DIR) && ./gradlew :desktop:run --args="--backend-mode $(BACKEND) --onboarding"

## The first-run flow in the television shell. Arrows are the D-pad, Enter is OK, Escape is Back.
onboarding-tv:
	cd $(KOTLIN_DIR) && ./gradlew :desktop:run --args="--backend-mode $(BACKEND) --tv --onboarding"

## Hot-reload loop for the desktop flow — edit a step, save, see it.
hot-onboarding:
	cd $(KOTLIN_DIR) && ./gradlew :desktop:hotRun --auto --args="--backend-mode $(BACKEND) --onboarding"

## Hot-reload loop for the television flow.
hot-onboarding-tv:
	cd $(KOTLIN_DIR) && ./gradlew :desktop:hotRun --auto --args="--backend-mode $(BACKEND) --tv --onboarding"

# Device targets select phone or TV from the APK's leanback feature. Debug-only extras choose
# fixtures; DEVICE selects among matching devices and ABI=all disables the single-ABI build.
FIXTURES ?= true
ABI ?=
DEVICE ?=

## Build, install and open the flow on a connected phone or tablet.
onboarding-mobile:
	bash scripts/onboarding-install.sh --kind phone \
	  --fixtures $(FIXTURES) --abi "$(ABI)" --device "$(DEVICE)"

## The same, on a connected Android TV emulator or device — the television shell.
##
## Create the emulator once with `make tv-avd`, then start it with:
##   emulator -avd $(TV_AVD) -gpu host
onboarding-tv-install:
	bash scripts/onboarding-install.sh --kind tv \
	  --fixtures $(FIXTURES) --abi "$(ABI)" --device "$(DEVICE)"

## Kotlin (shared KMP + desktop JVM + Android host) test suite.
test-kotlin:
	cd $(KOTLIN_DIR) && ./gradlew test

## Complete day-to-day test suite.
test: test-kotlin

## Build the desktop image and Android APK used by release packaging.
test-build:
	cd $(KOTLIN_DIR) && ./gradlew :desktop:createDistributable :mobile:assembleDebug

## Rehearse release packaging and signing contracts with a throwaway key. Run
## `make test-build` first to validate the real jpackage layout instead of a stub.
test-release:
	bash scripts/release-dry-run.sh

## The same, plus GitHub secrets, variables and tag state. Needs an authenticated gh with
## admin on the repo; run it before `make patch`.
release-preflight:
	bash scripts/release-dry-run.sh --remote

## Lint the GitHub Actions definitions.
test-workflows: test-release-notes test-site-docs test-release
	@command -v shellcheck >/dev/null 2>&1 || { echo "shellcheck is required for full workflow linting (Arch: sudo pacman -S shellcheck)."; exit 1; }
	@command -v actionlint >/dev/null 2>&1 || { echo "actionlint is required for workflow linting."; exit 1; }
	bash scripts/workflow-local-actions_test.sh
	actionlint .github/workflows/*.yml

## Verify that generated release notes contain only user-facing fixes/features.
test-release-notes:
	bash scripts/release-notes_test.sh

## Validate the versioned user-documentation contract consumed by cove.ninja.
test-site-docs:
	node scripts/validate-site-docs.mjs

## Broadest local approximation of CI.
test-all: test-workflows test test-build

## Bump VERSION, stage all changes, commit, and tag. `patch`, `minor`, and `major` apply
## semantic version increments. TITLE overrides the commit title; MSG adds a body before
## generated user-facing fix/feat notes.
## Then push with: git push origin master v<version>
## TITLE and MSG use the environment so multiline values are not expanded into recipe text.
export TITLE MSG
patch minor major:
	@CUR=$$(cat VERSION); \
	NEW_VER=$$(awk -F. -v part=$@ '{ \
		if (part == "patch")      printf "%d.%d.%d", $$1, $$2, $$3+1; \
		else if (part == "minor") printf "%d.%d.0",  $$1, $$2+1; \
		else                      printf "%d.0.0",   $$1+1; \
	}' VERSION); \
	printf '%s\n' "$$NEW_VER" > VERSION; \
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

## Remove build artifacts.
clean:
	rm -rf $(KOTLIN_DIR)/build $(KOTLIN_DIR)/*/build $(KOTLIN_DIR)/.gradle
