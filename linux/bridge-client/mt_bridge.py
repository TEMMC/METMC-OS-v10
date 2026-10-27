#!/usr/bin/env python3
"""MT OS Android hardware bridge client (JSON Lines over Android abstract AF_UNIX)."""
import argparse
import json
import os
import socket
import sys

SOCKET_NAME = "\0nativOS_bridge"
MAX_LINE = 1_048_576

def main():
    parser = argparse.ArgumentParser(
        description="Send a request to the MT OS Android hardware bridge."
    )
    parser.add_argument("type", help="Bridge type, e.g. system, audio, bluetooth")
    parser.add_argument("action", help="Action, e.g. get_battery, set_brightness")
    parser.add_argument("--params", default="{}", help="JSON object of request parameters")
    parser.add_argument("--timeout", type=float, default=8.0)
    args = parser.parse_args()

    try:
        params = json.loads(args.params)
        if not isinstance(params, dict):
            raise ValueError("--params must decode to a JSON object")
    except (json.JSONDecodeError, ValueError) as exc:
        parser.error(str(exc))

    request = json.dumps({
        "id": 1, "type": args.type, "action": args.action, "params": params
    }, separators=(",", ":")) + "\n"

    client = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
    client.settimeout(max(0.1, args.timeout))
    try:
        client.connect(SOCKET_NAME)
        client.sendall(request.encode("utf-8"))
        stream = client.makefile("rb")
        while True:
            line = stream.readline(MAX_LINE + 1)
            if not line:
                raise RuntimeError("Android bridge closed the connection without a response")
            if len(line) > MAX_LINE:
                raise RuntimeError("Android bridge response exceeded 1 MiB")
            try:
                message = json.loads(line.decode("utf-8"))
            except (UnicodeDecodeError, json.JSONDecodeError):
                continue
            # Unsolicited events may arrive before the correlated response.
            if message.get("id") == 1:
                print(json.dumps(message, indent=2, ensure_ascii=False))
                if message.get("status") != "ok":
                    return 2
                return 0
    except (OSError, RuntimeError) as exc:
        print("mt-bridge: " + str(exc), file=sys.stderr)
        print("Check that MT OS is open and the Android bridge service is running.", file=sys.stderr)
        return 1
    finally:
        client.close()

if __name__ == "__main__":
    sys.exit(main())
