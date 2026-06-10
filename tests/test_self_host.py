import io
import unittest
from contextlib import redirect_stdout

import self_host


class SelfHostTests(unittest.TestCase):
    def test_parse_url_rejects_paths(self):
        with self.assertRaises(ValueError):
            self_host.parse_site_url("https://snow-white.example.com/room/demo")

    def test_parse_url_defaults_to_https_snow_white(self):
        site = self_host.parse_site_url(None)
        self.assertEqual(site.scheme, "https")
        self.assertEqual(site.host, "snow-white.pinky.lilf.ir")
        self.assertEqual(site.origin, "https://snow-white.pinky.lilf.ir")

    def test_https_caddy_block_redirects_http_to_https(self):
        site = self_host.parse_site_url("https://game.example.test")
        block = self_host.render_caddy_block(site, self_host.Mode.PROD, self_host.Ports(backend=38933, frontend_dev=38934))
        self.assertIn("http://game.example.test", block)
        self.assertIn("redir https://game.example.test{uri} permanent", block)
        self.assertIn("@backend path /api /api/* /ws /health", block)
        self.assertIn("reverse_proxy @backend localhost:38933", block)
        self.assertIn("root * ", block)
        self.assertIn("try_files {path} /index.html", block)

    def test_http_caddy_block_redirects_https_to_http(self):
        site = self_host.parse_site_url("http://game.example.test:8080")
        block = self_host.render_caddy_block(site, self_host.Mode.DEV, self_host.Ports(backend=38941, frontend_dev=38942))
        self.assertIn("https://game.example.test:8080", block)
        self.assertIn("redir http://game.example.test:8080{uri} permanent", block)
        self.assertIn("reverse_proxy @backend localhost:38941", block)
        self.assertIn("reverse_proxy localhost:38942", block)

    def test_tmux_env_args_include_existing_proxy_vars(self):
        env = {
            "ALL_PROXY": "http://127.0.0.1:9999",
            "https_proxy": "http://127.0.0.1:9998",
            "UNRELATED": "ignored",
        }
        args = self_host.tmux_env_args(env)
        self.assertEqual(
            args,
            [
                "-e",
                "ALL_PROXY=http://127.0.0.1:9999",
                "-e",
                "https_proxy=http://127.0.0.1:9998",
            ],
        )

    def test_node_command_uses_node_24_via_nvm(self):
        command = self_host.node_command("pnpm build")
        self.assertIn("nvm-load", command)
        self.assertIn("nvm use 24", command)
        self.assertTrue(command.endswith("&& pnpm build"))

    def test_frontend_install_command_uses_noninteractive_pnpm_without_dedupe_by_default(self):
        command = self_host.frontend_install_command()
        self.assertEqual(command, "CI=true pnpm install --frozen-lockfile")

    def test_frontend_install_command_can_include_setup_dedupe(self):
        command = self_host.frontend_install_command(dedupe=True)
        self.assertEqual(command, "CI=true pnpm install --frozen-lockfile && CI=true pnpm dedupe")

    def test_config_roundtrip(self):
        cfg = self_host.Config(
            site=self_host.parse_site_url("http://lan.example.test"),
            mode=self_host.Mode.DEV,
            ports=self_host.Ports(backend=38951, frontend_dev=38952),
        )
        payload = cfg.to_json()
        restored = self_host.Config.from_json(payload)
        self.assertEqual(restored.site.origin, "http://lan.example.test")
        self.assertEqual(restored.mode, self_host.Mode.DEV)
        self.assertEqual(restored.ports.backend, 38951)
        self.assertEqual(restored.ports.frontend_dev, 38952)

    def test_legacy_config_uses_default_ports(self):
        restored = self_host.Config.from_json('{"url": "http://lan.example.test", "mode": "dev"}')
        self.assertEqual(restored.ports.backend, self_host.DEFAULT_BACKEND_PORT)
        self.assertEqual(restored.ports.frontend_dev, self_host.DEFAULT_FRONTEND_DEV_PORT)

    def test_find_free_port_skips_busy_and_reserved_ports(self):
        reserved = {39001}
        self.assertEqual(self_host.find_free_port(39001, reserved=reserved, is_free=lambda port: port == 39003), 39003)

    def test_allocate_ports_reuses_free_configured_ports(self):
        ports = self_host.allocate_ports(self_host.Ports(backend=39011, frontend_dev=39012), is_free=lambda port: True)
        self.assertEqual(ports.backend, 39011)
        self.assertEqual(ports.frontend_dev, 39012)

    def test_allocate_ports_reselects_busy_configured_ports(self):
        ports = self_host.allocate_ports(
            self_host.Ports(backend=39021, frontend_dev=39022),
            is_free=lambda port: port not in {39021, 39022},
        )
        self.assertEqual(ports.backend, 39023)
        self.assertEqual(ports.frontend_dev, 39024)

    def test_frontend_dev_command_includes_selected_ports_and_backend_origin(self):
        command, env = self_host.frontend_dev_command(self_host.Ports(backend=39031, frontend_dev=39032))
        self.assertIn("--port 39032", command)
        self.assertEqual(env["SNOW_BACKEND"], "http://localhost:39031")

    def test_tmux_env_args_include_snow_backend_for_vite_proxy(self):
        args = self_host.tmux_env_args({"SNOW_BACKEND": "http://localhost:39041"})
        self.assertEqual(args, ["-e", "SNOW_BACKEND=http://localhost:39041"])

    def test_announce_serving_prints_site_origin(self):
        cfg = self_host.Config(
            site=self_host.parse_site_url("https://game.example.test"),
            mode=self_host.Mode.PROD,
            ports=self_host.Ports(backend=39051, frontend_dev=39052),
        )
        out = io.StringIO()
        with redirect_stdout(out):
            self_host.announce_serving(cfg)
        self.assertEqual(out.getvalue(), "serving: https://game.example.test\n")


if __name__ == "__main__":
    unittest.main()
