# MT OS graphics defaults.
# Leave driver selection to Mesa/Xlorie auto-detection; forcing a driver here
# can break PowerVR devices or cause a software-rendering fallback.
unset LIBGL_ALWAYS_SOFTWARE
unset GALLIUM_DRIVER
unset MESA_LOADER_DRIVER_OVERRIDE
