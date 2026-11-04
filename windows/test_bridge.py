import json
import http.client
import socket
import threading
import unittest
import urllib.error
import urllib.request
from unittest.mock import patch

import bridge


class FakeSource:
    def snapshot(self):
        return {"schemaVersion": 1, "periods": {"day": {"totalTokens": 42}}}


class BridgeProtocolTests(unittest.TestCase):
    def setUp(self):
        self.key = "test-pairing-key-at-least-16"
        self.server = bridge.make_http_server("127.0.0.1", 0, FakeSource(), self.key)
        self.worker = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.worker.start()
        self.url = "http://127.0.0.1:" + str(self.server.server_port)

    def tearDown(self):
        self.server.shutdown()
        self.server.server_close()

    def request(self, body, key=None):
        headers = {"Content-Type": "application/json"}
        if key:
            headers["Authorization"] = "Bearer " + key
        return urllib.request.Request(self.url + "/api/rpc", data=body, headers=headers)

    def test_no_rpc_before_authentication(self):
        with patch("bridge.engine_rpc") as handler:
            for key in [None, "wrong-key"]:
                with self.assertRaises(urllib.error.HTTPError) as error:
                    urllib.request.urlopen(self.request(b'{"channel":"settings:update","args":[{}]}', key))
                self.assertEqual(error.exception.code, 401)
            handler.assert_not_called()

    def test_rpc_shape_and_arguments_preserved(self):
        body = {"channel": "settings:update", "args": [{"periodMonthMode": "week"}]}
        expected = {"ok": True, "result": {"periodMonthMode": "week"}}
        with patch("bridge.engine_rpc", return_value=expected) as handler:
            with urllib.request.urlopen(self.request(json.dumps(body).encode(), self.key)) as response:
                self.assertEqual(json.load(response), expected)
            handler.assert_called_once_with(body["channel"], body["args"], self.key)

    def test_invalid_json_rejected(self):
        with self.assertRaises(urllib.error.HTTPError) as error:
            urllib.request.urlopen(self.request(b"not json", self.key))
        self.assertEqual(error.exception.code, 400)

    def test_oversized_request_rejected(self):
        # The server should reject the declared size before reading any body.
        # Uploading megabytes concurrently races the intentional early close
        # and can surface Windows WSAECONNABORTED instead of the HTTP response.
        connection = http.client.HTTPConnection("127.0.0.1", self.server.server_port, timeout=3)
        try:
            with patch("bridge.engine_rpc") as handler:
                connection.putrequest("POST", "/api/rpc")
                connection.putheader("Authorization", "Bearer " + self.key)
                connection.putheader("Content-Type", "application/json")
                connection.putheader("Content-Length", str(2 * 1024 * 1024 + 1))
                connection.endheaders()
                response = connection.getresponse()
                self.assertEqual(response.status, 413)
                self.assertEqual(json.loads(response.read())["error"], "request_too_large")
                handler.assert_not_called()
        finally:
            connection.close()

    def test_rpc_validates_argument_array(self):
        self.assertEqual(bridge.engine_rpc("stats:get", {}, self.key)["error"], "invalid_request")

    def test_bluetooth_framing_auth_and_rpc(self):
        local, remote = socket.socketpair()
        with patch("bridge.engine_rpc", return_value={"ok": True, "result": {"unicode": "额度", "tokens": 123}}):
            worker = threading.Thread(target=bridge.bluetooth_client, args=(local, FakeSource(), self.key), daemon=True)
            worker.start()
            with remote, remote.makefile("rwb") as stream:
                auth = json.dumps({"type": "auth", "key": self.key}).encode() + b"\n"
                # Fragmented writes verify framing is stream based.
                stream.write(auth[:5]); stream.flush()
                stream.write(auth[5:]); stream.flush()
                self.assertEqual(json.loads(stream.readline()), {"type": "authenticated"})
                request = {"type": "rpc", "channel": "settings:update", "args": [{"x": "a" * 10000}]}
                stream.write(json.dumps(request).encode() + b"\n"); stream.flush()
                reply = json.loads(stream.readline())
                self.assertTrue(reply["ok"])
                self.assertEqual(reply["result"]["unicode"], "额度")
            worker.join(1)
            self.assertFalse(worker.is_alive())

    def test_bluetooth_wrong_key_disconnects(self):
        local, remote = socket.socketpair()
        worker = threading.Thread(target=bridge.bluetooth_client, args=(local, FakeSource(), self.key), daemon=True)
        worker.start()
        with remote, remote.makefile("rwb") as stream:
            stream.write(b'{"type":"auth","key":"wrong"}\n'); stream.flush()
            self.assertEqual(json.loads(stream.readline()), {"error": "unauthorized"})
            self.assertEqual(stream.readline(), b"")

    def test_source_merges_overlapping_snapshots_without_addition(self):
        def day(tokens):
            return {"observations": {"model": {"tokens": tokens, "modelId": "gpt-test", "cost": 1}}}
        merged = bridge.merge_days({"days": {"2026-11-03": day(80)}, "liveDays": {"2026-11-03": day(100)}}, "2026-11-03")
        self.assertEqual(bridge.day_tokens(merged["2026-11-03"]), 100)


if __name__ == "__main__":
    unittest.main(verbosity=2)
