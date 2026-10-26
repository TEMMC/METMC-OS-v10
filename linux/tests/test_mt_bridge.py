import importlib.util
import json
from pathlib import Path
import socket
import threading

SCRIPT = Path(__file__).parents[1] / "bridge-client" / "mt-bridge.py"
spec = importlib.util.spec_from_file_location("mt_bridge_cli", SCRIPT)
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


def test_action_catalog_contains_core_desktop_controls():
    for name in ("status", "battery", "wifi", "brightness", "volume",
                 "clipboard-get", "torch-on", "display-settings", "apps"):
        assert name in module.ACTIONS


def test_request_round_trip_over_unix_socket(tmp_path):
    path = str(tmp_path / "bridge.sock")
    server = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
    server.bind(path)
    server.listen(1)

    def serve():
        conn, _ = server.accept()
        with conn:
            request = json.loads(conn.makefile("r", encoding="utf-8").readline())
            assert request["type"] == "system"
            assert request["action"] == "get_battery"
            conn.sendall((json.dumps({"id": request["id"], "status": "ok",
                                     "data": {"percentage": 71}}) + "\n").encode())
        server.close()

    thread = threading.Thread(target=serve)
    thread.start()
    assert module.request(path, "system", "get_battery", {}) == {"percentage": 71}
    thread.join(timeout=2)
    assert not thread.is_alive()


def test_request_surfaces_android_error(tmp_path):
    path = str(tmp_path / "bridge.sock")
    server = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
    server.bind(path)
    server.listen(1)

    def serve():
        conn, _ = server.accept()
        with conn:
            request = json.loads(conn.makefile("r", encoding="utf-8").readline())
            conn.sendall((json.dumps({"id": request["id"], "status": "error",
                                     "data": {"message": "permission required"}}) + "\n").encode())
        server.close()

    thread = threading.Thread(target=serve)
    thread.start()
    try:
        module.request(path, "system", "get_battery", {})
        assert False, "Expected RuntimeError"
    except RuntimeError as error:
        assert "permission required" in str(error)
    thread.join(timeout=2)
