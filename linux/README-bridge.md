# MT OS Android–Linux bridge

This package targets the **Ubuntu rootfs** and builds independently of MT-Phosh and MT-Phoc.

## Components
- `linux/bridge-client/nativOS-bridge.py`: persistent JSON-lines client/event listener.
- `linux/bridge-client/mt-bridge.py`: CLI for Android system controls and API requests.
- `linux/bridge-client/metmc-settings.py` and `metmc-wifi.py`: Android-owned settings launchers.
- `linux/config/nativOS-bridge.service`: Linux-side service.
- `android/app/src/main/java/com/nativOS/bridge/`: Android service and API handlers integrated into the MT OS APK.

## Examples
```sh
mt-bridge status
mt-bridge battery
mt-bridge wifi
mt-bridge brightness
mt-bridge brightness-set 150
mt-bridge volume
mt-bridge volume-set 7 --stream media
mt-bridge clipboard-get
mt-bridge clipboard-set 'Text copied from Ubuntu'
mt-bridge apps
mt-bridge launch-app com.example.app com.example.app.MainActivity
mt-bridge display-settings
mt-bridge request sensor list_sensors
```

The CLI only reports success when the Android handler returns success. Use the generic `request` subcommand for other implemented handler actions, passing parameters as a JSON object. Android permissions, foreground-state limits and privileged APIs still apply.

## Runtime contract
The Android service listens on the abstract Unix socket `nativOS_bridge`. Linux clients try `/run/nativOS/bridge.sock` first and then fall back to the abstract socket, which works when the Ubuntu chroot shares Android’s network namespace. Generated app icons/desktop entries still need the existing Android-to-rootfs shared path mounted at `/run/nativOS/android-apps`. A green CI run proves source/package checks, not live device communication or Android permission availability. No network download is needed at boot.
