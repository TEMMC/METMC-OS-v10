"""Unix-socket transport shared by MT OS bridge clients.

Android LocalServerSocket binds in the abstract AF_UNIX namespace. The app's
filesystem-path compatibility listener may not be reachable from a chroot, so
try the documented path first and then the Android service's abstract name.
"""
import socket

DEFAULT_SOCKET_PATH = "/run/nativOS/bridge.sock"
ANDROID_ABSTRACT_SOCKET = "\0nativOS_bridge"


def connect(path=DEFAULT_SOCKET_PATH, timeout=None):
    addresses = [path]
    if path == DEFAULT_SOCKET_PATH:
        addresses.append(ANDROID_ABSTRACT_SOCKET)
    errors = []
    for address in addresses:
        client = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
        if timeout is not None:
            client.settimeout(timeout)
        try:
            client.connect(address)
            return client
        except OSError as error:
            errors.append("{}: {}".format(
                "Android abstract socket" if address.startswith("\0") else address,
                error))
            client.close()
    raise OSError("Unable to connect to MT Android bridge (" + "; ".join(errors) + ")")
