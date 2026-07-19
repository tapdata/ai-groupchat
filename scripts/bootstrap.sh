#!/usr/bin/env bash
set -euo pipefail

REPO_URL="${REPO_URL:-https://github.com/tapdata/ai-groupchat.git}"
BRANCH="${BRANCH:-main}"
INSTALL_DIR="${INSTALL_DIR:-$HOME/ai-groupchat}"
APP_PORT="${APP_PORT:-7860}"

log() {
  printf '\n==> %s\n' "$1"
}

warn() {
  printf '\nWARN: %s\n' "$1" >&2
}

fail() {
  printf '\nERROR: %s\n' "$1" >&2
  exit 1
}

have() {
  command -v "$1" >/dev/null 2>&1
}

sudo_if_needed() {
  if [ "$(id -u)" -eq 0 ]; then
    "$@"
  elif have sudo; then
    sudo "$@"
  else
    fail "This step needs root privileges. Install sudo or run manually: $*"
  fi
}

java_major() {
  if ! have java; then
    echo 0
    return
  fi
  java -version 2>&1 | awk -F[\".] '/version/ {
    if ($2 == "1") print $3; else print $2;
    exit
  }'
}

install_with_package_manager() {
  local packages=("$@")
  if have brew; then
    brew update
    brew install "${packages[@]}"
  elif have apt-get; then
    sudo_if_needed apt-get update
    sudo_if_needed apt-get install -y "${packages[@]}"
  elif have dnf; then
    sudo_if_needed dnf install -y "${packages[@]}"
  elif have yum; then
    sudo_if_needed yum install -y "${packages[@]}"
  elif have pacman; then
    sudo_if_needed pacman -Sy --noconfirm "${packages[@]}"
  elif have zypper; then
    sudo_if_needed zypper --non-interactive install "${packages[@]}"
  else
    return 1
  fi
}

install_git() {
  have git && return
  log "Installing Git"
  if have brew; then
    brew install git
  elif have apt-get; then
    sudo_if_needed apt-get update
    sudo_if_needed apt-get install -y git
  elif have dnf; then
    sudo_if_needed dnf install -y git
  elif have yum; then
    sudo_if_needed yum install -y git
  elif have pacman; then
    sudo_if_needed pacman -Sy --noconfirm git
  elif have zypper; then
    sudo_if_needed zypper --non-interactive install git
  else
    fail "Git is not installed and no supported package manager was found."
  fi
}

install_java() {
  local major
  major="$(java_major)"
  if [ "${major:-0}" -ge 17 ] 2>/dev/null; then
    return
  fi

  log "Installing Java 17+"
  if have brew; then
    brew install openjdk@17
    export PATH="/opt/homebrew/opt/openjdk@17/bin:/usr/local/opt/openjdk@17/bin:$PATH"
    return
  fi

  if have apt-get; then
    sudo_if_needed apt-get update
    sudo_if_needed apt-get install -y openjdk-17-jdk
  elif have dnf; then
    sudo_if_needed dnf install -y java-17-openjdk-devel
  elif have yum; then
    sudo_if_needed yum install -y java-17-openjdk-devel
  elif have pacman; then
    sudo_if_needed pacman -Sy --noconfirm jdk17-openjdk
  elif have zypper; then
    sudo_if_needed zypper --non-interactive install java-17-openjdk-devel
  else
    fail "Java 17+ is required and no supported package manager was found."
  fi
}

install_maven() {
  have mvn && return
  log "Installing Maven"
  if ! install_with_package_manager maven; then
    fail "Maven is not installed and no supported package manager was found."
  fi
}

clone_or_update() {
  log "Preparing source checkout"
  if [ -d "$INSTALL_DIR/.git" ]; then
    git -C "$INSTALL_DIR" fetch origin "$BRANCH"
    git -C "$INSTALL_DIR" checkout "$BRANCH"
    git -C "$INSTALL_DIR" pull --ff-only origin "$BRANCH"
  elif [ -d "$INSTALL_DIR" ] && [ "$(find "$INSTALL_DIR" -mindepth 1 -maxdepth 1 | wc -l | tr -d ' ')" != "0" ]; then
    fail "$INSTALL_DIR exists and is not an empty Git checkout. Set INSTALL_DIR to another path."
  else
    mkdir -p "$(dirname "$INSTALL_DIR")"
    git clone --branch "$BRANCH" "$REPO_URL" "$INSTALL_DIR"
  fi
}

build_and_run() {
  cd "$INSTALL_DIR"
  log "Building ai-groupchat"
  mvn -q -DskipTests package

  log "Starting ai-groupchat"
  printf 'Open http://localhost:%s after the server starts.\n' "$APP_PORT"
  exec java -jar target/ai-groupchat.jar
}

main() {
  log "Checking prerequisites"
  install_git
  install_java
  install_maven

  have git || fail "Git installation failed."
  [ "$(java_major)" -ge 17 ] 2>/dev/null || fail "Java 17+ installation failed."
  have mvn || fail "Maven installation failed."

  clone_or_update
  build_and_run
}

main "$@"
