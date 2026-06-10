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
from typing import Callable
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

DEFAULT_BACKEND_PORT = 38931
DEFAULT_FRONTEND_DEV_PORT = 38932
DEFAULT_NREPL_PORT = 38933
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
    "SNOW_BACKEND",
)


class Mode(str, Enum):
    PROD = "prod"
    DEV = "dev"


@dataclass(frozen=True)
class Ports:
    backend: int = DEFAULT_BACKEND_PORT
    frontend_dev: int = DEFAULT_FRONTEND_DEV_PORT
    nrepl: int = DEFAULT_NREPL_PORT

    def to_json(self) -> dict[str, int]:
        return {"backend": self.backend, "frontend_dev": self.frontend_dev, "nrepl": self.nrepl}

    @classmethod
    def from_json(cls, raw: object) -> "Ports":
        if not isinstance(raw, dict):
            return cls()
        return cls(
            backend=int(raw.get("backend", DEFAULT_BACKEND_PORT)),
            frontend_dev=int(raw.get("frontend_dev", DEFAULT_FRONTEND_DEV_PORT)),
            nrepl=int(raw.get("nrepl", DEFAULT_NREPL_PORT)),
        )


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
    ports: Ports = Ports()

    def to_json(self) -> str:
        return json.dumps(
            {"url": self.site.origin, "mode": self.mode.value, "ports": self.ports.to_json()},
            indent=2,
            sort_keys=True,
        )

    @classmethod
    def from_json(cls, payload: str) -> "Config":
        raw = json.loads(payload)
        return cls(
            site=parse_site_url(raw.get("url")),
            mode=Mode(raw.get("mode", Mode.PROD.value)),
            ports=Ports.from_json(raw.get("ports")),
        )


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


def caddy_dev_upstream(ports: Ports) -> str:
    return f"localhost:{ports.frontend_dev}"


def render_caddy_block(site: Site, mode: Mode, ports: Ports = Ports()) -> str:
    if mode == Mode.PROD:
        frontend = f"""	handle {{
		root * {BUILD_DIR}
		try_files {{path}} /index.html
		file_server
	}}"""
    else:
        frontend = f"""	handle {{
		reverse_proxy {caddy_dev_upstream(ports)}
	}}"""

    return f"""{MANAGED_START}
{site.opposite_origin} {{
	redir {site.origin}{{uri}} permanent
}}

{site.origin} {{
	@backend path /api /api/* /ws /health
	handle @backend {{
		reverse_proxy localhost:{ports.backend}
	}}

{frontend}
}}
{MANAGED_END}
"""


def update_caddyfile(site: Site, mode: Mode, ports: Ports) -> None:
    block = render_caddy_block(site, mode, ports)
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


def find_free_port(
    preferred: int,
    *,
    reserved: set[int] | None = None,
    is_free: Callable[[int], bool] = port_free,
) -> int:
    reserved = reserved or set()
    for port in range(preferred, 65536):
        if port not in reserved and is_free(port):
            return port
    raise RuntimeError(f"No free port found at or above {preferred}")


def allocate_ports(preferred: Ports, *, is_free: Callable[[int], bool] = port_free) -> Ports:
    backend = find_free_port(preferred.backend, is_free=is_free)
    frontend_dev = find_free_port(preferred.frontend_dev, reserved={backend}, is_free=is_free)
    nrepl = find_free_port(preferred.nrepl, reserved={backend, frontend_dev}, is_free=is_free)
    return Ports(backend=backend, frontend_dev=frontend_dev, nrepl=nrepl)


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


def frontend_install_command(*, dedupe: bool = False) -> str:
    command = "CI=true pnpm install --frozen-lockfile"
    if dedupe:
        command += " && CI=true pnpm dedupe"
    return command


def install_frontend(*, dedupe: bool = False) -> None:
    run(["zsh", "-lc", node_command(frontend_install_command(dedupe=dedupe))], cwd=FRONTEND)


def build_frontend() -> None:
    run(["zsh", "-lc", node_command("pnpm build")], cwd=FRONTEND)


def reload_caddy() -> None:
    run(["caddy", "fmt", "--overwrite", str(CADDYFILE)])
    run(["caddy", "reload", "--config", str(CADDYFILE)])


def announce_serving(config: Config) -> None:
    print(f"serving: {config.site.origin}")


def backend_server_command(ports: Ports) -> str:
    return f"clojure -M:dev-server {ports.backend} {ports.nrepl}"


def backend_repl_client_command(ports: Ports) -> str:
    return (
        f"bash -lc 'until nc -z 127.0.0.1 {ports.nrepl} >/dev/null 2>&1; do sleep 0.2; done; "
        f"exec clojure -M:repl-client --port {ports.nrepl}'"
    )


def backend_tmux_commands(session: str, ports: Ports) -> list[list[str]]:
    return [
        ["tmux", "kill-session", "-t", session],
        [
            "tmux",
            "new",
            "-d",
            "-s",
            session,
            "-n",
            "server",
            "-c",
            str(BACKEND),
            backend_server_command(ports),
        ],
        [
            "tmux",
            "new-window",
            "-t",
            f"{session}:",
            "-n",
            "repl",
            "-c",
            str(BACKEND),
            backend_repl_client_command(ports),
        ],
    ]


def start_backend(session: str, ports: Ports) -> None:
    commands = backend_tmux_commands(session, ports)
    subprocess.run(commands[0], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    env_args = tmux_env_args()
    run(commands[1][:2] + env_args + commands[1][2:])
    run(commands[2][:2] + env_args + commands[2][2:])


def start_backend_prod(ports: Ports) -> None:
    start_backend(PROD_SESSION, ports)


def start_backend_dev(ports: Ports) -> None:
    start_backend(DEV_BACKEND_SESSION, ports)


def frontend_dev_command(ports: Ports) -> tuple[str, dict[str, str]]:
    command = f"pnpm dev --host {frontend_dev_host()} --port {ports.frontend_dev}"
    return command, {"SNOW_BACKEND": f"http://localhost:{ports.backend}"}


def start_frontend_dev(ports: Ports) -> None:
    command, env = frontend_dev_command(ports)
    tmuxnew(DEV_FRONTEND_SESSION, shell_with_node(command), cwd=FRONTEND, env_args=tmux_env_args(os.environ | env))


def resolve_config(url: str | None, fallback_mode: Mode = Mode.PROD) -> Config:
    if url:
        existing = load_config()
        return Config(parse_site_url(url), fallback_mode, existing.ports if existing else Ports())
    existing = load_config()
    if existing:
        return Config(existing.site, fallback_mode)
    return Config(parse_site_url(None), fallback_mode)


def resolve_runtime_config(url: str | None, mode: Mode) -> Config:
    config = resolve_config(url, mode)
    return Config(config.site, config.mode, allocate_ports(config.ports))


def command_setup(url: str | None) -> None:
    command_stop()
    config = resolve_runtime_config(url, Mode.PROD)
    ensure_tools(["caddy", "clojure", "nc", "pnpm", "tmux", "zsh"])
    install_frontend(dedupe=True)
    build_frontend()
    update_caddyfile(config.site, Mode.PROD, config.ports)
    save_config(config)
    reload_caddy()
    start_backend_prod(config.ports)
    announce_serving(config)


def command_redeploy(url: str | None) -> None:
    command_stop()
    config = resolve_runtime_config(url, Mode.PROD)
    ensure_tools(["caddy", "clojure", "nc", "pnpm", "tmux", "zsh"])
    install_frontend()
    build_frontend()
    update_caddyfile(config.site, Mode.PROD, config.ports)
    save_config(config)
    reload_caddy()
    start_backend_prod(config.ports)
    announce_serving(config)


def command_start(url: str | None) -> None:
    command_stop()
    config = resolve_runtime_config(url, Mode.PROD)
    ensure_tools(["caddy", "clojure", "nc", "tmux"])
    if not BUILD_DIR.exists():
        ensure_tools(["pnpm", "zsh"])
        install_frontend()
        build_frontend()
    update_caddyfile(config.site, Mode.PROD, config.ports)
    save_config(config)
    reload_caddy()
    start_backend_prod(config.ports)
    announce_serving(config)


def command_dev_start(url: str | None) -> None:
    command_stop()
    config = resolve_runtime_config(url, Mode.DEV)
    ensure_tools(["caddy", "clojure", "nc", "pnpm", "tmux", "zsh"])
    install_frontend()
    update_caddyfile(config.site, Mode.DEV, config.ports)
    save_config(config)
    reload_caddy()
    start_backend_dev(config.ports)
    start_frontend_dev(config.ports)
    announce_serving(config)


def command_stop() -> None:
    ensure_tools(["tmux"])
    stop_sessions()


def command_status() -> None:
    config = load_config()
    print(f"config: {CONFIG_FILE if config else 'not written'}")
    if config:
        print(f"url: {config.site.origin}")
        print(f"mode: {config.mode.value}")
        print(f"backend port: {config.ports.backend}")
        print(f"frontend dev port: {config.ports.frontend_dev}")
        print(f"nREPL port: {config.ports.nrepl}")
    for session in (PROD_SESSION, DEV_BACKEND_SESSION, DEV_FRONTEND_SESSION):
        if shutil.which("tmux") is None:
            print(f"tmux {session}: tmux not installed")
        else:
            found = subprocess.run(["tmux", "has-session", "-t", session], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL).returncode == 0
            print(f"tmux {session}: {'running' if found else 'stopped'}")
    ports = config.ports if config else Ports()
    for port in (ports.backend, ports.frontend_dev, ports.nrepl):
        print(f"port {port}: {'free' if port_free(port) else 'in use'}")
    if shutil.which("curl") is None:
        print("backend health: curl not installed")
    else:
        health = subprocess.run(["curl", "-fsS", f"http://localhost:{ports.backend}/health"], text=True, capture_output=True)
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
