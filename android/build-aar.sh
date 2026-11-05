#!/usr/bin/env bash
# Builds app/libs/libmygo.aar, the Go side of the app (../mobile), with
# gomobile. Needs Go, the Android SDK (ANDROID_HOME) and NDK (ANDROID_NDK_HOME
# or $ANDROID_HOME/ndk/<version>).
#
#   ./build-aar.sh                 arm64 and x86_64 (the emulator)
#   ABIS=android/arm64 ./build-aar.sh
set -euo pipefail
cd "$(dirname "$0")/.."

: "${ABIS:=android/arm64,android/amd64}"
if [[ -z "${ANDROID_NDK_HOME:-}" && -n "${ANDROID_HOME:-}" ]]; then
	ANDROID_NDK_HOME="$(ls -d "$ANDROID_HOME"/ndk/* 2>/dev/null | sort -V | tail -1)"
	export ANDROID_NDK_HOME
fi

# gomobile's version comes from go.mod, which requires golang.org/x/mobile.
go install golang.org/x/mobile/cmd/gomobile golang.org/x/mobile/cmd/gobind
PATH="$(go env GOPATH)/bin:$PATH"
gomobile init

# The geo databases the core carries, as desktop release builds do: a
# first start with GEOIP or GEOSITE rules needs no download.
go run ./tools/geodata

version="$(sed -n 's/.*"version": *"\([^"]*\)".*/\1/p' mygo.json | head -1)"
mkdir -p android/app/libs
# with_gvisor: the TUN stack; cmfa: mihomo's Android build for apps, which
# takes the system's DNS and the apps of connections from the app.
gomobile bind \
	-target="$ABIS" \
	-androidapi 24 \
	-javapkg io.mygo.clash \
	-tags with_gvisor,cmfa \
	-trimpath \
	-ldflags "-s -w -buildid= -X github.com/mygo-clash/mygo-clash/internal/corehost.AppVersion=$version" \
	-o android/app/libs/libmygo.aar \
	./mobile
ls -lh android/app/libs/libmygo.aar
