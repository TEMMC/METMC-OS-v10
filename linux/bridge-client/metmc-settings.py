#!/usr/bin/env python3
"""METMC OS bridge for Android-owned hardware and system settings."""
import json
import socket
import sys

SOCKET_PATH = "/run/nativOS/bridge.sock"
ACTIONS = {
    "bluetooth": ("open_bluetooth_settings", "Opening Android Bluetooth settings."),
    "settings": ("open_system_settings", "Opening Android system settings."),
    "display": ("open_display_settings", "Opening Android display settings."),
    "sound": ("open_sound_settings", "Opening Android sound settings."),
    "location": ("open_location_settings", "Opening Android location settings."),
}

def request(action):
    with socket.socket(socket.AF_UNIX, socket.SOCK_STREAM) as client:
        client.settimeout(8)
        client.connect(SOCKET_PATH)
        client.sendall((json.dumps({
            "id": 7302, "type": "system", "action": action, "params": {}
        }) + "\n").encode())
        stream = client.makefile("r", encoding="utf-8")
        while True:
            line = stream.readline()
            if not line:
                raise RuntimeError("Android bridge closed the connection")
            message = json.loads(line)
            if message.get("id") == 7302:
                if message.get("status") != "ok":
                    raise RuntimeError(message.get("data", {}).get("message", "Settings bridge failed"))
                return message.get("data", {})

def main():
    command = sys.argv[1] if len(sys.argv) > 1 else "settings"
    if command not in ACTIONS:
        print("Usage: metmc-settings [bluetooth|settings|display|sound|location]", file=sys.stderr)
        return 2
    action, message = ACTIONS[command]
    try:
        request(action)
        print(message + " Android continues to manage the device hardware.")
        return 0
    except (OSError, ValueError, RuntimeError) as error:
        print("METMC settings bridge unavailable: {}".format(error), file=sys.stderr)
        print("Make sure METMC OS is running and its Android bridge service is active.", file=sys.stderr)
        return 1

if __name__ == "__main__":
    raise SystemExit(main())
