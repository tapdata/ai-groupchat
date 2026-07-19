#!/usr/bin/env bash
set -euo pipefail

REPO_URL="${REPO_URL:-https://github.com/tapdata/ai-groupchat.git}"
BRANCH="${BRANCH:-main}"
INSTALL_DIR="${INSTALL_DIR:-$HOME/ai-groupchat}"
APP_PORT="${APP_PORT:-7860}"

log() {
  printf '\n==> %s\n' "$1"
}

info() {
  printf '    %s\n' "$1"
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

run_with_timeout() {
  local seconds="$1"
  shift
  local tmp pid elapsed status
  tmp="$(mktemp)"
  "$@" >"$tmp" 2>&1 </dev/null &
  pid="$!"
  elapsed=0
  while kill -0 "$pid" 2>/dev/null; do
    if [ "$elapsed" -ge "$seconds" ]; then
      kill "$pid" 2>/dev/null || true
      sleep 1
      kill -9 "$pid" 2>/dev/null || true
      wait "$pid" 2>/dev/null || true
      rm -f "$tmp"
      return 124
    fi
    sleep 1
    elapsed=$((elapsed + 1))
  done
  wait "$pid"
  status="$?"
  cat "$tmp"
  rm -f "$tmp"
  return "$status"
}

first_line_with_timeout() {
  local seconds="$1"
  shift
  run_with_timeout "$seconds" "$@" | sed -n '1p'
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
  local version_output major
  if ! version_output="$(run_with_timeout 10 java -version)"; then
    warn "java -version failed or did not complete within 10 seconds; treating Java as missing."
    echo 0
    return
  fi
  major="$(printf '%s\n' "$version_output" | awk -F[\".] '/version/ {
    if ($2 == "1") print $3; else print $2;
    exit
  }')"
  echo "${major:-0}"
}

use_macos_java_home() {
  if [ "$(uname -s)" != "Darwin" ] || [ ! -x /usr/libexec/java_home ]; then
    return 1
  fi
  local java_home
  info "Checking macOS Java home."
  if java_home="$(run_with_timeout 10 /usr/libexec/java_home -v 17)"; then
    export JAVA_HOME="$java_home"
    export PATH="$JAVA_HOME/bin:$PATH"
    info "JAVA_HOME set to $JAVA_HOME"
    return 0
  fi
  info "No macOS Java 17 home found."
  return 1
}

install_with_package_manager() {
  local packages=("$@")
  if have brew; then
    HOMEBREW_NO_AUTO_UPDATE=1 brew install "${packages[@]}"
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
  if have git; then
    info "Git found: $(git --version)"
    return
  fi
  log "Installing Git"
  if have brew; then
    HOMEBREW_NO_AUTO_UPDATE=1 brew install git
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
  info "Detecting Java version."
  use_macos_java_home || true
  major="$(java_major)"
  if [ "${major:-0}" -ge 17 ] 2>/dev/null; then
    info "Java found: $(first_line_with_timeout 10 java -version)"
    return
  fi

  log "Installing Java 17+"
  if have brew; then
    info "Installing openjdk@17 with Homebrew. This can take several minutes."
    HOMEBREW_NO_AUTO_UPDATE=1 brew install openjdk@17
    export PATH="/opt/homebrew/opt/openjdk@17/bin:/usr/local/opt/openjdk@17/bin:$PATH"
    use_macos_java_home || true
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
  if have mvn; then
    info "Maven found: $(first_line_with_timeout 10 mvn -version)"
    return
  fi
  log "Installing Maven"
  if ! install_with_package_manager maven; then
    fail "Maven is not installed and no supported package manager was found."
  fi
}

clone_or_update() {
  log "Preparing source checkout"
  info "Repository: $REPO_URL"
  info "Branch: $BRANCH"
  info "Install dir: $INSTALL_DIR"
  if [ -d "$INSTALL_DIR/.git" ]; then
    info "Existing checkout found; fetching latest main branch."
    git -C "$INSTALL_DIR" fetch origin "$BRANCH"
    git -C "$INSTALL_DIR" checkout "$BRANCH"
    git -C "$INSTALL_DIR" pull --ff-only origin "$BRANCH"
  elif [ -d "$INSTALL_DIR" ] && [ "$(find "$INSTALL_DIR" -mindepth 1 -maxdepth 1 | wc -l | tr -d ' ')" != "0" ]; then
    fail "$INSTALL_DIR exists and is not an empty Git checkout. Set INSTALL_DIR to another path."
  else
    mkdir -p "$(dirname "$INSTALL_DIR")"
    info "Cloning repository."
    git clone --branch "$BRANCH" "$REPO_URL" "$INSTALL_DIR"
  fi
}

build_and_run() {
  cd "$INSTALL_DIR"
  log "Building ai-groupchat"
  info "Running Maven package with tests skipped."
  mvn -q -DskipTests package

  log "Starting ai-groupchat"
  printf 'Open http://localhost:%s after the server starts.\n' "$APP_PORT"
  exec java -jar target/ai-groupchat.jar
}

main() {
  log "Checking prerequisites"
  info "Checking Git."
  install_git
  info "Checking Java 17+."
  install_java
  info "Checking Maven."
  install_maven

  have git || fail "Git installation failed."
  info "Verifying Java 17+."
  [ "$(java_major)" -ge 17 ] 2>/dev/null || fail "Java 17+ installation failed."
  have mvn || fail "Maven installation failed."

  clone_or_update
  build_and_run
}

main "$@"
