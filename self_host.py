#!/usr/bin/env python3
"""Manage Snow White self-hosting with Caddy and tmux.

The script is intentionally boring: Caddy serves the static Svelte build, the
Clojure backend owns `/api`, `/ws`, and `/health`, and tmux keeps the processes
easy to inspect or restart without Docker.
"""

from __future__ import annotations

import argparse
import json
import os
import platform
import shutil
import socket
import subprocess
import sys
from dataclasses import dataclass
from enum import Enum
from pathlib import Path
from urllib.parse import urlparse


ROOT = Path(__file__).resolve().parent
FRONTEND = ROOT / "frontend"
BACKEND = ROOT / "backend"
BUILD_DIR = FRONTEND / "build"
CONFIG_DIR = ROOT / ".self-host"
CONFIG_FILE = CONFIG_DIR / "config.json"
CADDYFILE = Path.home() / "Caddyfile"
DEFAULT_URL = "https://snow-white.pinky.lilf.ir"
DEFAULT_NODE_VERSION = "24"

BACKEND_PORT = 3000
FRONTEND_DEV_PORT = 5173
PROD_SESSION = "snow-white-backend"
DEV_BACKEND_SESSION = "snow-white-dev-backend"
DEV_FRONTEND_SESSION = "snow-white-dev-frontend"
MANAGED_START = "# BEGIN snow-white self_host.py"
MANAGED_END = "# END snow-white self_host.py"
PROXY_ENV_NAMES = (
    "ALL_PROXY",
    "all_proxy",
    "http_proxy",
    "https_proxy",
    "HTTP_PROXY",
    "HTTPS_PROXY",
    "npm_config_proxy",
    "npm_config_https_proxy",
    "NO_PROXY",
    "no_proxy",
)


class Mode(str, Enum):
    PROD = "prod"
    DEV = "dev"


@dataclass(frozen=True)
class Site:
    scheme: str
    host: str
    port: int | None = None

    @property
    def authority(self) -> str:
        return f"{self.host}:{self.port}" if self.port else self.host

    @property
    def origin(self) -> str:
        return f"{self.scheme}://{self.authority}"

    @property
    def opposite_scheme(self) -> str:
        return "http" if self.scheme == "https" else "https"

    @property
    def opposite_origin(self) -> str:
        return f"{self.opposite_scheme}://{self.authority}"


@dataclass(frozen=True)
class Config:
    site: Site
    mode: Mode

    def to_json(self) -> str:
        return json.dumps(
            {"url": self.site.origin, "mode": self.mode.value},
            indent=2,
            sort_keys=True,
        )

    @classmethod
    def from_json(cls, payload: str) -> "Config":
        raw = json.loads(payload)
        return cls(site=parse_site_url(raw.get("url")), mode=Mode(raw.get("mode", Mode.PROD.value)))


def parse_site_url(value: str | None) -> Site:
    parsed = urlparse(value or DEFAULT_URL)
    if parsed.scheme not in {"http", "https"}:
        raise ValueError("URL must start with http:// or https://")
    if not parsed.hostname:
        raise ValueError("URL must include a host")
    if parsed.path not in {"", "/"} or parsed.params or parsed.query or parsed.fragment:
        raise ValueError("URL must be only an origin, for example https://snow-white.example.test")
    return Site(parsed.scheme, parsed.hostname, parsed.port)


def load_config() -> Config | None:
    if not CONFIG_FILE.exists():
        return None
    return Config.from_json(CONFIG_FILE.read_text())


def save_config(config: Config) -> None:
    CONFIG_DIR.mkdir(exist_ok=True)
    CONFIG_FILE.write_text(config.to_json() + "\n")


def tmux_env_args(env: dict[str, str] | None = None) -> list[str]:
    source = env if env is not None else os.environ
    args: list[str] = []
    for name in PROXY_ENV_NAMES:
        value = source.get(name)
        if value:
            args.extend(["-e", f"{name}={value}"])
    return args


def frontend_dev_host() -> str:
    return "localhost" if platform.system() == "Darwin" else "0.0.0.0"


def caddy_dev_upstream() -> str:
    return f"localhost:{FRONTEND_DEV_PORT}"


def render_caddy_block(site: Site, mode: Mode) -> str:
    if mode == Mode.PROD:
        frontend = f"""	root * {BUILD_DIR}
	try_files {{path}} /index.html
	file_server"""
    else:
        frontend = f"""	reverse_proxy {caddy_dev_upstream()}"""

    return f"""{MANAGED_START}
{site.opposite_origin} {{
	redir {site.origin}{{uri}} permanent
}}

{site.origin} {{
	@backend path /api/* /ws /health
	reverse_proxy @backend localhost:{BACKEND_PORT}

{frontend}
}}
{MANAGED_END}
"""


def update_caddyfile(site: Site, mode: Mode) -> None:
    block = render_caddy_block(site, mode)
    existing = CADDYFILE.read_text() if CADDYFILE.exists() else ""
    start = existing.find(MANAGED_START)
    end = existing.find(MANAGED_END)
    if (start == -1) != (end == -1):
        raise RuntimeError(f"{CADDYFILE} contains only one Snow White managed marker")
    if start == -1:
        text = existing.rstrip() + ("\n\n" if existing.strip() else "") + block
    else:
        end += len(MANAGED_END)
        text = existing[:start].rstrip() + "\n\n" + block + existing[end:].lstrip("\n")
    CADDYFILE.write_text(text)


def run(cmd: list[str], *, cwd: Path = ROOT, check: bool = True) -> subprocess.CompletedProcess[str]:
    print("+", " ".join(cmd))
    return subprocess.run(cmd, cwd=cwd, check=check, text=True)


def ensure_tools(names: list[str]) -> None:
    missing = [name for name in names if shutil.which(name) is None]
    if missing:
        raise RuntimeError("Missing required command(s): " + ", ".join(missing))


def port_free(port: int) -> bool:
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as sock:
        sock.settimeout(0.2)
        return sock.connect_ex(("127.0.0.1", port)) != 0


def require_free_ports(ports: list[int]) -> None:
    busy = [port for port in ports if not port_free(port)]
    if busy:
        raise RuntimeError("Required port(s) already in use: " + ", ".join(map(str, busy)))


def tmux_kill(session: str) -> None:
    subprocess.run(["tmux", "kill-session", "-t", session], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)


def stop_sessions() -> None:
    for session in (PROD_SESSION, DEV_BACKEND_SESSION, DEV_FRONTEND_SESSION):
        tmux_kill(session)


def tmuxnew(session: str, command: str, *, cwd: Path, env_args: list[str] | None = None) -> None:
    tmux_kill(session)
    args = ["tmux", "new", "-d", "-s", session]
    args.extend(env_args or [])
    args.extend(["-c", str(cwd), command])
    run(args)


def shell_with_node(command: str) -> str:
    return f"zsh -lc {json.dumps(node_command(command))}"


def node_command(command: str) -> str:
    nvmrc = FRONTEND / ".nvmrc"
    version = nvmrc.read_text().strip() if nvmrc.exists() else DEFAULT_NODE_VERSION
    node_setup = (
        "command -v nvm-load >/dev/null 2>&1 "
        "|| { echo 'nvm-load is required for frontend commands' >&2; exit 1; }"
        f" && nvm-load && nvm use {version}"
    )
    return node_setup + " && " + command


def install_frontend() -> None:
    run(["zsh", "-lc", node_command("CI=true pnpm install --frozen-lockfile && CI=true pnpm dedupe")], cwd=FRONTEND)


def build_frontend() -> None:
    run(["zsh", "-lc", node_command("pnpm build")], cwd=FRONTEND)


def reload_caddy() -> None:
    run(["caddy", "fmt", "--overwrite", str(CADDYFILE)])
    run(["caddy", "reload", "--config", str(CADDYFILE)])


def start_backend_prod() -> None:
    require_free_ports([BACKEND_PORT])
    tmuxnew(PROD_SESSION, "clj -M:run", cwd=BACKEND, env_args=tmux_env_args())


def start_backend_dev() -> None:
    require_free_ports([BACKEND_PORT])
    tmuxnew(DEV_BACKEND_SESSION, "clj -M:dev", cwd=BACKEND, env_args=tmux_env_args())
    run(["tmux", "send-keys", "-t", DEV_BACKEND_SESSION, "(go)", "Enter"])


def start_frontend_dev() -> None:
    require_free_ports([FRONTEND_DEV_PORT])
    command = f"pnpm dev --host {frontend_dev_host()} --port {FRONTEND_DEV_PORT}"
    tmuxnew(DEV_FRONTEND_SESSION, shell_with_node(command), cwd=FRONTEND, env_args=tmux_env_args())


def resolve_config(url: str | None, fallback_mode: Mode = Mode.PROD) -> Config:
    if url:
        return Config(parse_site_url(url), fallback_mode)
    existing = load_config()
    if existing:
        return Config(existing.site, fallback_mode)
    return Config(parse_site_url(None), fallback_mode)


def command_setup(url: str | None) -> None:
    command_stop()
    config = resolve_config(url, Mode.PROD)
    ensure_tools(["caddy", "clj", "pnpm", "tmux", "zsh"])
    install_frontend()
    build_frontend()
    update_caddyfile(config.site, Mode.PROD)
    save_config(config)
    reload_caddy()
    start_backend_prod()


def command_redeploy(url: str | None) -> None:
    command_stop()
    config = resolve_config(url, Mode.PROD)
    ensure_tools(["caddy", "clj", "pnpm", "tmux", "zsh"])
    install_frontend()
    build_frontend()
    update_caddyfile(config.site, Mode.PROD)
    save_config(config)
    reload_caddy()
    start_backend_prod()


def command_start(url: str | None) -> None:
    command_stop()
    config = resolve_config(url, Mode.PROD)
    ensure_tools(["caddy", "clj", "tmux"])
    if not BUILD_DIR.exists():
        ensure_tools(["pnpm", "zsh"])
        install_frontend()
        build_frontend()
    update_caddyfile(config.site, Mode.PROD)
    save_config(config)
    reload_caddy()
    start_backend_prod()


def command_dev_start(url: str | None) -> None:
    command_stop()
    config = resolve_config(url, Mode.DEV)
    ensure_tools(["caddy", "clj", "pnpm", "tmux", "zsh"])
    install_frontend()
    update_caddyfile(config.site, Mode.DEV)
    save_config(config)
    reload_caddy()
    start_backend_dev()
    start_frontend_dev()


def command_stop() -> None:
    ensure_tools(["tmux"])
    stop_sessions()


def command_status() -> None:
    config = load_config()
    print(f"config: {CONFIG_FILE if config else 'not written'}")
    if config:
        print(f"url: {config.site.origin}")
        print(f"mode: {config.mode.value}")
    for session in (PROD_SESSION, DEV_BACKEND_SESSION, DEV_FRONTEND_SESSION):
        if shutil.which("tmux") is None:
            print(f"tmux {session}: tmux not installed")
        else:
            found = subprocess.run(["tmux", "has-session", "-t", session], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL).returncode == 0
            print(f"tmux {session}: {'running' if found else 'stopped'}")
    for port in (BACKEND_PORT, FRONTEND_DEV_PORT):
        print(f"port {port}: {'free' if port_free(port) else 'in use'}")
    if shutil.which("curl") is None:
        print("backend health: curl not installed")
    else:
        health = subprocess.run(["curl", "-fsS", f"http://localhost:{BACKEND_PORT}/health"], text=True, capture_output=True)
        print(f"backend health: {health.stdout.strip() if health.returncode == 0 else 'unavailable'}")


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Self-host Snow White with Caddy and tmux.")
    parser.add_argument("command", choices=["setup", "redeploy", "start", "stop", "dev-start", "status"])
    parser.add_argument("url", nargs="?", help=f"Site origin, default {DEFAULT_URL}")
    args = parser.parse_args(argv)

    try:
        if args.command == "setup":
            command_setup(args.url)
        elif args.command == "redeploy":
            command_redeploy(args.url)
        elif args.command == "start":
            command_start(args.url)
        elif args.command == "dev-start":
            command_dev_start(args.url)
        elif args.command == "stop":
            command_stop()
        elif args.command == "status":
            command_status()
    except Exception as exc:
        print(f"error: {exc}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
