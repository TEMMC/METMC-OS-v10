# MT OS GPU acceleration

The A04e exposes a PowerVR Rogue GE8320 through Android's graphics stack. The Linux desktop runs through Xlorie/X11 with Phoc/Phosh nested above it, so acceleration depends on the Android surface path **and** whether the Ubuntu rootfs can use a compatible EGL/DRM driver.

The Ubuntu setup installs Mesa's diagnostic/runtime libraries, but does not claim that this alone enables hardware acceleration. Android's PowerVR userspace libraries are not automatically compatible with Ubuntu's Mesa stack. The setup deliberately does not force `pvr`, `panfrost`, `llvmpipe`, or another driver.

After installing the bridge/GPU helpers inside Ubuntu:
```sh
mt-gpu-status
glxinfo -B
```
Look for `OpenGL renderer string`. `llvmpipe`, `softpipe`, or `swrast` means software rendering. A visible `/dev/dri/renderD128` is useful but not proof of acceleration. If the active renderer is software, the next required step is to validate Xlorie's EGL/GLES path and the kernel/userspace driver compatibility; do not blindly copy Android graphics libraries into Ubuntu.

The helper only diagnoses; it does not change display resolution, compositor, kernel modules, or GPU driver. This avoids breaking the working display session.
