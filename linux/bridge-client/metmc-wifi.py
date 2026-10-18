#!/usr/bin/env python3
"""METMC OS Wi-Fi bridge client. Android keeps ownership of the Wi-Fi radio."""
import json
import socket
import sys

SOCKET_PATH = "/run/nativOS/bridge.sock"

def request(action):
    with socket.socket(socket.AF_UNIX, socket.SOCK_STREAM) as client:
        client.settimeout(8)
        client.connect(SOCKET_PATH)
        client.sendall((json.dumps({
            "id": 7301, "type": "system", "action": action, "params": {}
        }) + "\n").encode())
        stream = client.makefile("r", encoding="utf-8")
        while True:
            line = stream.readline()
            if not line:
                raise RuntimeError("Android bridge closed the connection")
            message = json.loads(line)
            if message.get("id") == 7301:
                if message.get("status") != "ok":
                    raise RuntimeError(message.get("data", {}).get("message", "Wi-Fi bridge failed"))
                return message.get("data", {})

def main():
    command = sys.argv[1] if len(sys.argv) > 1 else "status"
    try:
        if command == "open":
            result = request("open_wifi_settings")
            print("Opening Android Wi-Fi controls. Android continues to manage the Wi-Fi radio.")
            return
        if command != "status":
            print("Usage: metmc-wifi [status|open]", file=sys.stderr)
            return 2
        data = request("get_wifi")
        print("Wi-Fi: " + ("enabled" if data.get("enabled") else "disabled"))
        print("Connection: " + (data.get("ssid") or ("connected" if data.get("connected") else "not connected")))
        print("Signal: {} dBm".format(data.get("rssi_dbm", "unknown")))
        print("Link speed: {} Mbps".format(data.get("link_speed_mbps", "unknown")))
        print("Frequency: {} MHz".format(data.get("frequency_mhz", "unknown")))
        print("To manage Wi-Fi from Phosh, run: metmc-wifi open")
        return 0
    except (OSError, ValueError, RuntimeError) as error:
        print("METMC Wi-Fi bridge unavailable: {}".format(error), file=sys.stderr)
        print("Make sure METMC OS is running and the Android bridge service is active.", file=sys.stderr)
        return 1

if __name__ == "__main__":
    raise SystemExit(main())
