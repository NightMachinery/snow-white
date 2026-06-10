import unittest

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
        block = self_host.render_caddy_block(site, self_host.Mode.PROD)
        self.assertIn("http://game.example.test", block)
        self.assertIn("redir https://game.example.test{uri} permanent", block)
        self.assertIn("root * ", block)
        self.assertIn("try_files {path} /index.html", block)

    def test_http_caddy_block_redirects_https_to_http(self):
        site = self_host.parse_site_url("http://game.example.test:8080")
        block = self_host.render_caddy_block(site, self_host.Mode.DEV)
        self.assertIn("https://game.example.test:8080", block)
        self.assertIn("redir http://game.example.test:8080{uri} permanent", block)
        self.assertIn("reverse_proxy localhost:", block)

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

    def test_config_roundtrip(self):
        cfg = self_host.Config(site=self_host.parse_site_url("http://lan.example.test"), mode=self_host.Mode.DEV)
        payload = cfg.to_json()
        restored = self_host.Config.from_json(payload)
        self.assertEqual(restored.site.origin, "http://lan.example.test")
        self.assertEqual(restored.mode, self_host.Mode.DEV)


if __name__ == "__main__":
    unittest.main()
