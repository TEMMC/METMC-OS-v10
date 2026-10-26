#!/usr/bin/env python3
"""MT OS bridge command line for supported Android APIs from the Ubuntu rootfs."""
import argparse
import json
import os
from bridge_transport import connect as connect_bridge
import sys

DEFAULT_SOCKET = os.environ.get("MT_BRIDGE_SOCKET", "/run/nativOS/bridge.sock")
ACTIONS = {
    "status": ("system", "get_device_info", {}),
    "battery": ("system", "get_battery", {}),
    "wifi": ("system", "get_wifi", {}),
    "brightness": ("system", "get_brightness", {}),
    "volume": ("audio", "get_volume", {}),
    "audio": ("audio", "get_audio_info", {}),
    "clipboard-get": ("system", "get_clipboard", {}),
    "torch-on": ("system", "torch_on", {}),
    "torch-off": ("system", "torch_off", {}),
    "wifi-settings": ("system", "open_wifi_settings", {}),
    "bluetooth-settings": ("system", "open_bluetooth_settings", {}),
    "display-settings": ("system", "open_display_settings", {}),
    "sound-settings": ("system", "open_sound_settings", {}),
    "location-settings": ("system", "open_location_settings", {}),
    "settings": ("system", "open_system_settings", {}),
    "signal": ("system", "get_signal", {}),
    "apps": ("system", "list_android_apps", {}),
}
TYPES = {"system", "audio", "telephony", "sms", "camera", "sensor",
         "location", "bluetooth", "haptics", "notification"}


def request(socket_path, msg_type, action, params, timeout=8):
    message = {"id": 91371, "type": msg_type, "action": action, "params": params}
    with connect_bridge(socket_path, timeout=timeout) as client:
        client.sendall((json.dumps(message, separators=(",", ":")) + "\n").encode())
        stream = client.makefile("r", encoding="utf-8")
        while True:
            line = stream.readline()
            if not line:
                raise RuntimeError("Bridge closed the connection before replying")
            try:
                response = json.loads(line)
            except json.JSONDecodeError:
                continue
            if response.get("id") != message["id"]:
                continue
            if response.get("status") != "ok":
                detail = response.get("data", {}).get("message", "request failed")
                raise RuntimeError(detail)
            return response.get("data", {})


def main(argv=None):
    parser = argparse.ArgumentParser(
        prog="mt-bridge",
        description="Control supported Android APIs from the MT Ubuntu desktop.")
    parser.add_argument("--socket", default=DEFAULT_SOCKET, help="bridge socket path")
    sub = parser.add_subparsers(dest="command", required=True)
    for name in ACTIONS:
        sub.add_parser(name)
    p = sub.add_parser("brightness-set")
    p.add_argument("level", type=int, choices=range(0, 256), metavar="0..255")
    p = sub.add_parser("volume-set")
    p.add_argument("level", type=int)
    p.add_argument("--stream", choices=("media", "ring", "notification", "alarm", "call"),
                   default="media")
    p = sub.add_parser("clipboard-set")
    p.add_argument("text")
    p = sub.add_parser("call")
    p.add_argument("action", choices=("dial", "answer", "hangup", "hold", "unhold", "get_call_state"))
    p.add_argument("--number")
    p = sub.add_parser("sms-send")
    p.add_argument("number")
    p.add_argument("text")
    p = sub.add_parser("request")
    p.add_argument("type", choices=sorted(TYPES))
    p.add_argument("action")
    p.add_argument("--params", default="{}", help="JSON object of action parameters")
    args = parser.parse_args(argv)
    try:
        if args.command == "request":
            params = json.loads(args.params)
            if not isinstance(params, dict):
                raise ValueError("--params must be a JSON object")
            result = request(args.socket, args.type, args.action, params)
        elif args.command == "brightness-set":
            result = request(args.socket, "system", "set_brightness", {"level": args.level})
        elif args.command == "volume-set":
            result = request(args.socket, "audio", "set_volume",
                             {"level": args.level, "stream": args.stream})
        elif args.command == "clipboard-set":
            result = request(args.socket, "system", "set_clipboard", {"text": args.text})
        elif args.command == "call":
            params = {"number": args.number} if args.number else {}
            result = request(args.socket, "telephony", args.action, params)
        elif args.command == "sms-send":
            result = request(args.socket, "sms", "send",
                             {"number": args.number, "text": args.text})
        else:
            msg_type, action, params = ACTIONS[args.command]
            result = request(args.socket, msg_type, action, params)
        print(json.dumps(result, indent=2, sort_keys=True))
        return 0
    except (OSError, ValueError, RuntimeError) as error:
        print("mt-bridge: {}".format(error), file=sys.stderr)
        print("Check that the Android bridge service is running and its socket is mounted at {}.".format(
            args.socket), file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
