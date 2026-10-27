package com.nativOS.runtime

import android.content.Context
import android.util.Log
import java.io.File
import java.security.MessageDigest

/**
 * Manages the Alpine Linux chroot: mounting, Labwc session lifecycle, and command execution.\n * Labwc runs nested on METMC's Android-hosted X11 display.
 */
class ChrootManager(private val context: Context) {

    companion object {
        private const val TAG = "NativOS.ChrootManager"
        private val mountLock = Any()

        @Volatile private var sessionProcess: Process? = null
    }

    private val rootShell = RootShell(context)

    private val baseDir: File get() = context.filesDir
    private val rootfsDir: File get() = File(baseDir, "rootfs")
    private val tmpDir: File get() = File(baseDir, "tmp")
    private val shmDir: File get() = File(baseDir, "shm")
    private val x11HostDir: File get() = File(tmpDir, ".X11-unix")
    private val bridgeSocketDir: File get() = File(baseDir, "bridge")
    // Keep the shared directory in app-owned storage. Direct access to
    // /data/media and /storage/emulated is device/ROM dependent under SELinux.
    // SharedFolderProvider exposes this directory to Android's Files UI.
    private val androidSharedDir: File
        get() = File(baseDir, "shared")

    // ── Status ──

    fun hasRoot(): Boolean = rootShell.hasRoot()

    /**
     * Best-effort enable Android's developer freeform windowing switches.
     *
     * This asks Android's own WindowManager to try freeform windows; it cannot
     * transfer Android surfaces into Xfwm4 or add missing firmware/SystemUI
     * support. Launch bounds are only a hint unless the host supports freeform.
     */
    fun enableFreeformWindows(): Boolean {
        if (!hasRoot()) return false
        return try {
            val output = rootShell.exec(
                "settings put global enable_freeform_support 1 && " +
                    "settings put global force_resizable_activities 1; " +
                    "echo FREEFORM_FEATURES:; " +
                    "pm list features | grep -E 'android.software.freeform_window_management|android.software.activities_on_secondary_displays' || true; " +
                    "echo LOW_RAM:; " +
                    "getprop ro.config.low_ram; " +
                    "echo FREEFORM_SETTING:; settings get global enable_freeform_support; " +
                    "echo RESIZABLE_SETTING:; settings get global force_resizable_activities"
            )
            val settingValue = output.substringAfter("FREEFORM_SETTING:", "").lineSequence()
                .drop(1).firstOrNull()?.trim().orEmpty()
            val lowRamValue = output.substringAfter("LOW_RAM:", "").lineSequence()
                .drop(1).firstOrNull()?.trim().orEmpty()
            val settingEnabled = settingValue == "1"
            val featurePresent = output.contains("android.software.freeform_window_management")
            val lowRam = lowRamValue.equals("true", ignoreCase = true)
            Log.i(
                TAG,
                "Android windowing probe: settingValue=$settingValue, " +
                    "resizableValue=${output.substringAfter("RESIZABLE_SETTING:", "").lineSequence().drop(1).firstOrNull()?.trim().orEmpty()}, " +
                    "freeformFeature=$featurePresent, lowRam=$lowRam; " +
                    output.take(1200).trim()
            )
            // Return whether the request was stored, not whether firmware supports it.
            settingEnabled
        } catch (error: Throwable) {
            Log.w(TAG, "Could not configure Android freeform windowing", error)
            false
        }
    }

    fun isRootfsReady(): Boolean = File(rootfsDir, "bin/bash").exists()

    fun isLabwcInstalled(): Boolean =
        File(rootfsDir, "usr/bin/labwc").exists() &&
        File(rootfsDir, "usr/bin/waybar").exists() &&
        File(rootfsDir, "usr/bin/fuzzel").exists() &&
        File(rootfsDir, "usr/bin/foot").exists() &&
        (File(rootfsDir, "usr/bin/Xwayland").exists() || File(rootfsDir, "usr/bin/xwayland").exists())
    /** MT-Phosh/MT-Phoc is preferred when the integrated mobile shell is bundled. */
    fun isPhoshInstalled(): Boolean =
        File(rootfsDir, "usr/bin/phosh-session").canExecute() &&
        File(rootfsDir, "usr/bin/phoc").canExecute() &&
        File(rootfsDir, "usr/libexec/phosh").canExecute()

    fun isRunning(): Boolean = sessionProcess?.isAlive == true

    fun getRootfsPath(): String = rootfsDir.absolutePath

    fun getBridgeSocketPath(): String = File(bridgeSocketDir, "bridge.sock").absolutePath

    // ── Mount handling ──

    /**
     * Ensure /dev, /proc, /sys, /dev/pts and tmpfs mounts are active.
     * Also bind-mounts the bridge socket directory into the chroot.
     */
    fun ensureMounts() = synchronized(mountLock) {
        ensureMountsLocked()
    }

    private fun ensureMountsLocked() {
        if (!hasRoot()) return

        // Services such as polkit drop root privileges and must still be able to
        // enter the chroot root. Android's private parent directory remains 0700.
        rootShell.exec("chmod 755 ${rootfsDir.absolutePath}")

        // Bubblewrap's privileged fallback needs the sandbox root to be a mount
        // point. Do this before the nested mounts so /dev, /proc, and /sys stay
        // visible inside that mount rather than being hidden by it.
        val existingMounts = rootShell.exec("mount").lines()
        if (existingMounts.none { it.contains(" on ${rootfsDir.absolutePath} ") }) {
            rootShell.exec("mount --bind ${rootfsDir.absolutePath} ${rootfsDir.absolutePath}")
            Log.i(TAG, "Made chroot root a bind mount")
        }

        val mounts = rootShell.exec("mount").lines()
        fun isMounted(path: String): Boolean {
            val absolute = File(rootfsDir, path).absolutePath
            return mounts.any { it.contains(" on $absolute ") }
        }

        // Core filesystem mounts
        mountIfNeeded("/dev", "--bind /dev") { isMounted("dev") }
        mountIfNeeded("/dev/pts", "-t devpts devpts") { isMounted("dev/pts") }
        // wlroots sends its X11 framebuffer to the embedded server through
        // MIT-SHM. An anonymous tmpfs gets the generic tmpfs SELinux label,
        // which Android denies to our isolated :x11 app process. Keep /dev/shm
        // on app-owned storage so both the rooted compositor and X11 can use it.
        shmDir.mkdirs()
        val chrootShmDir = File(rootfsDir, "dev/shm").absolutePath
        val shmMount = mounts.firstOrNull { it.contains(" on $chrootShmDir ") }
        if (shmMount == null || !shmMount.startsWith("${shmDir.absolutePath} ")) {
            if (shmMount != null) rootShell.exec("umount $chrootShmDir")
            rootShell.exec("mkdir -p $chrootShmDir && mount --bind ${shmDir.absolutePath} $chrootShmDir")
            Log.i(TAG, "Bound app-owned shared memory directory into chroot")
        }
        mountIfNeeded("/proc", "--bind /proc") { isMounted("proc") }
        mountIfNeeded("/sys", "--bind /sys") { isMounted("sys") }
        mountIfNeeded("/run", "-t tmpfs tmpfs") { isMounted("run") }
        mountIfNeeded("/tmp", "-t tmpfs tmpfs") { isMounted("tmp") }

        // Android's app-data filesystem is nosuid. Change the flag only on the
        // isolated rootfs bind (not its parent mount), addressing it as / from
        // inside the chroot so mount resolves the correct bind mount.
        if (execChroot("mount -o remount,bind,suid,dev /") != 0) {
            Log.w(TAG, "Could not enable privileged helpers on chroot bind mount")
        }

        // Fix missing symlinks in Android's /dev
        val devPath = File(rootfsDir, "dev").absolutePath
        rootShell.exec("ln -snf /proc/self/fd $devPath/fd")
        rootShell.exec("ln -snf /proc/self/fd/0 $devPath/stdin")
        rootShell.exec("ln -snf /proc/self/fd/1 $devPath/stdout")
        rootShell.exec("ln -snf /proc/self/fd/2 $devPath/stderr")

        // Grant app access to GPU for Termux:X11 DRI3
        rootShell.exec("chmod 666 /dev/dri/* 2>/dev/null")

        // Create runtime directories inside chroot
        execChroot("mkdir -p /tmp/.X11-unix /tmp/runtime-root /run/nativOS /root")

        // Bind-mount bridge socket directory so Linux side can connect
        bridgeSocketDir.mkdirs()
        val chrootBridgeDir = File(rootfsDir, "run/nativOS").absolutePath
        if (!mounts.any { it.contains(" on $chrootBridgeDir ") }) {
            rootShell.exec("mkdir -p $chrootBridgeDir && mount --bind ${bridgeSocketDir.absolutePath} $chrootBridgeDir")
            Log.i(TAG, "Bound bridge socket dir into chroot")
        }

        // Bind-mount host files directory into chroot so absolute paths match
        val hostFilesDir = context.filesDir.absolutePath
        val chrootFilesDir = File(rootfsDir, hostFilesDir).absolutePath
        if (!mounts.any { it.contains(" on $chrootFilesDir ") }) {
            rootShell.exec("mkdir -p $chrootFilesDir && mount --bind $hostFilesDir $chrootFilesDir")
            Log.i(TAG, "Bound host files dir into chroot for path compatibility")
        }

        // Expose one deliberate app-owned folder. Android sees it through the
        // NativOS Shared documents provider; Linux sees /root/Shared.
        val chrootSharedDir = File(rootfsDir, "mnt/android").absolutePath
        androidSharedDir.mkdirs()
        // Rebind every session so upgrades migrate any stale /data/media mount.
        if (mounts.any { it.contains(" on $chrootSharedDir ") }) {
            rootShell.exec("umount -l $chrootSharedDir 2>/dev/null || true")
        }
        val result = rootShell.exec(
            "mkdir -p ${androidSharedDir.absolutePath} $chrootSharedDir && " +
                "chmod -R 0777 ${androidSharedDir.absolutePath} && " +
                "mount --bind ${androidSharedDir.absolutePath} $chrootSharedDir && " +
                "echo NATIVOS_SHARED_READY"
        )
        if (result.contains("NATIVOS_SHARED_READY")) {
            Log.i(TAG, "Bound Android shared folder into Linux")
        } else {
            Log.w(TAG, "Could not mount Android shared folder: ${result.trim()}")
        }
        execChroot("mkdir -p /mnt/android && ln -snf /mnt/android /root/Shared")

        // Share internal storage and mounted SD/USB volumes with Linux.
        exposeAndroidStorage()

        // Refresh DNS
        try {
            File(rootfsDir, "etc/resolv.conf").writeText("nameserver 8.8.8.8\nnameserver 1.1.1.1\n")
        } catch (e: Exception) {
            Log.w(TAG, "Could not overwrite resolv.conf from Kotlin, it might be root-owned. Proceeding.")
        }

        Log.i(TAG, "All mounts ready")
    }

    /** Expose Android shared storage and mounted removable volumes to Linux. */
    private fun exposeAndroidStorage() {
        val internal = "/storage/emulated/0"
        val linuxRoot = File(rootfsDir, "mnt/android-storage").absolutePath
        val command = """
            mkdir -p '$linuxRoot/internal' '$linuxRoot/removable'
            if [ -d '$internal' ] && ! mount | grep -F " on $linuxRoot/internal " >/dev/null 2>&1; then mount --bind '$internal' '$linuxRoot/internal' 2>/dev/null; fi
            for source in /storage/*; do
                [ -d "${'$'}source" ] || continue
                name=${'$'}{source##*/}
                case "${'$'}name" in emulated|self) continue ;; esac
                if mount | grep -F " on ${'$'}source " >/dev/null 2>&1; then
                    target='$linuxRoot/removable/'${'$'}name
                    mkdir -p "${'$'}target"
                    if ! mount | grep -F " on ${'$'}target " >/dev/null 2>&1; then
                        mount --bind "${'$'}source" "${'$'}target" 2>/dev/null
                    fi
                fi
            done
            chmod 755 '$linuxRoot' '$linuxRoot/internal' '$linuxRoot/removable' 2>/dev/null
            echo METMC_ANDROID_STORAGE_READY
        """.trimIndent()
        runCatching {
            val result = rootShell.exec(command)
            if (result.contains("METMC_ANDROID_STORAGE_READY")) {
                execChroot("mkdir -p /mnt/android-storage /root && ln -snf /mnt/android-storage /root/AndroidStorage")
                Log.i(TAG, "Exposed Android internal and mounted removable storage to Linux")
            } else {
                Log.w(TAG, "Android storage exposure returned: ${result.take(500)}")
            }
        }.onFailure { Log.w(TAG, "Could not expose Android storage to Linux", it) }
    }

    /** Bind the Android-hosted X11 socket into Linux so DISPLAY=:0 can connect. */
    fun bindX11Socket() {
        if (!hasRoot()) {
            Log.e(TAG, "Cannot bind X11 socket: root access unavailable")
            return
        }

        x11HostDir.mkdirs()
        val chrootX11 = File(rootfsDir, "tmp/.X11-unix").absolutePath
        rootShell.exec("mkdir -p $chrootX11")

        // /tmp is a chroot tmpfs, so the Android socket directory must be
        // bind-mounted after ensureMounts() creates that tmpfs. Keep an existing
        // correct bind mount in place; repeatedly unmounting it can race the desktop session.
        var mounts = rootShell.exec("mount").lines()
        val currentMount = mounts.firstOrNull { it.contains(" on $chrootX11 ") }
        if (currentMount != null && currentMount.startsWith("${x11HostDir.absolutePath} ")) {
            Log.i(TAG, "X11 socket directory already bound: $chrootX11")
            return
        }
        if (currentMount != null) {
            val unmountResult = rootShell.exec("umount $chrootX11") { output ->
                if (output.isNotBlank()) Log.d(TAG, output.trim())
            }
            if (unmountResult != 0) {
                Log.e(TAG, "Could not remove stale X11 mount at $chrootX11")
                return
            }
        }

        val result = rootShell.exec(
            "mkdir -p $chrootX11 && chmod 0777 ${x11HostDir.absolutePath} $chrootX11 " +
                "&& mount --bind ${x11HostDir.absolutePath} $chrootX11"
        ) { output ->
            if (output.isNotBlank()) Log.d(TAG, output.trim())
        }
        mounts = rootShell.exec("mount").lines()
        val bound = mounts.any {
            it.startsWith("${x11HostDir.absolutePath} ") && it.contains(" on $chrootX11 ")
        }
        if (result == 0 && bound) {
            Log.i(TAG, "Bound host X11 socket directory ${x11HostDir.absolutePath} -> $chrootX11")
        } else {
            Log.e(TAG, "Failed to bind host X11 socket directory; mount result=$result")
        }
    }

    /** Make modern GLib app launching work on kernels with partial close_range support. */
    private fun prepareCloseRangeCompatibility() {
        val library = File(rootfsDir, "usr/local/lib/libnativos-close-range.so")
        if (library.exists()) return

        try {
            val source = File(baseDir, "compat/close_range.c")
            source.parentFile?.mkdirs()
            source.writeText(
                """
                #define _GNU_SOURCE
                #include <dirent.h>
                #include <errno.h>
                #include <limits.h>
                #include <ftw.h>
                int close_range(unsigned int first, unsigned int last, int flags) {
                    (void) first; (void) last; (void) flags;
                    errno = ENOSYS;
                    return -1;
                }
                """.trimIndent()
            )
            val result = execChroot(
                "mkdir -p /usr/local/lib && " +
                    "gcc -shared -fPIC -o /usr/local/lib/libnativos-close-range.so ${source.absolutePath}"
            )
            if (result != 0) Log.w(TAG, "Could not build close_range compatibility shim")
        } catch (error: Throwable) {
            Log.w(TAG, "Could not prepare close_range compatibility shim", error)
        }
    }

    /** Build a tiny XCB helper that keeps Phoc's nested X11 window in sync with Android. */
    private fun prepareDisplayResizeHelper() {
        val helper = File(rootfsDir, "usr/local/bin/nativos-resize-phoc")
        if (!helper.exists()) try {
            val source = File(baseDir, "compat/nativos_resize_phoc.c")
            source.parentFile?.mkdirs()
            source.writeText(
                """
                #include <stdint.h>
                #include <stdlib.h>
                #include <xcb/xcb.h>

                int main(int argc, char **argv) {
                    if (argc != 3) return 64;
                    long width = strtol(argv[1], 0, 10);
                    long height = strtol(argv[2], 0, 10);
                    if (width < 1 || height < 1 || width > 32767 || height > 32767) return 64;

                    xcb_connection_t *connection = xcb_connect(0, 0);
                    if (xcb_connection_has_error(connection)) return 69;
                    const xcb_setup_t *setup = xcb_get_setup(connection);
                    xcb_screen_t *screen = xcb_setup_roots_iterator(setup).data;

                    const char atom_name[] = "WM_CLASS";
                    xcb_intern_atom_reply_t *wm_class = xcb_intern_atom_reply(
                        connection,
                        xcb_intern_atom(connection, 0, sizeof(atom_name) - 1, atom_name),
                        0
                    );
                    xcb_query_tree_reply_t *tree = xcb_query_tree_reply(
                        connection, xcb_query_tree(connection, screen->root), 0
                    );
                    if (!tree || !wm_class) return 70;

                    int resized = 0;
                    int child_count = xcb_query_tree_children_length(tree);
                    xcb_window_t *children = xcb_query_tree_children(tree);
                    for (int index = 0; index < child_count; index++) {
                        xcb_get_window_attributes_reply_t *attributes =
                            xcb_get_window_attributes_reply(
                                connection,
                                xcb_get_window_attributes(connection, children[index]),
                                0
                            );
                        xcb_get_property_reply_t *window_class = xcb_get_property_reply(
                            connection,
                            xcb_get_property(
                                connection, 0, children[index], wm_class->atom,
                                XCB_GET_PROPERTY_TYPE_ANY, 0, 1
                            ),
                            0
                        );
                        if (attributes && attributes->map_state == XCB_MAP_STATE_VIEWABLE &&
                            window_class && xcb_get_property_value_length(window_class) == 0) {
                            uint32_t dimensions[] = {(uint32_t) width, (uint32_t) height};
                            xcb_configure_window(
                                connection, children[index],
                                XCB_CONFIG_WINDOW_WIDTH | XCB_CONFIG_WINDOW_HEIGHT,
                                dimensions
                            );
                            resized++;
                        }
                        free(attributes);
                        free(window_class);
                    }
                    xcb_flush(connection);
                    free(tree);
                    free(wm_class);
                    xcb_disconnect(connection);
                    return resized > 0 ? 0 : 2;
                }
                """.trimIndent()
            )
            val result = execChroot(
                "mkdir -p /usr/local/bin && " +
                    "gcc -O2 -o /usr/local/bin/nativos-resize-phoc ${source.absolutePath} -lxcb"
            )
            if (result != 0) Log.w(TAG, "Could not build Phoc display resize helper")
        } catch (error: Throwable) {
            Log.w(TAG, "Could not prepare Phoc display resize helper", error)
        }

        val maximizeHelper = File(rootfsDir, "usr/local/bin/nativos-maximize-x11-v6")
        if (maximizeHelper.exists()) return
        try {
            val source = File(baseDir, "compat/nativos_maximize_x11.c")
            source.parentFile?.mkdirs()
            source.writeText(
                """
                #include <stdint.h>
                #include <signal.h>
                #include <stdio.h>
                #include <stdlib.h>
                #include <string.h>
                #include <unistd.h>
                #include <xcb/xcb.h>

                static volatile sig_atomic_t requested_action = 0;
                static void request_home(int signal_number) {
                    (void) signal_number;
                    requested_action = 1;
                }
                static void request_restore(int signal_number) {
                    (void) signal_number;
                    requested_action = 2;
                }

                static int is_application(xcb_connection_t *connection, xcb_window_t window,
                                          xcb_atom_t wm_class) {
                    xcb_get_window_attributes_reply_t *attributes =
                        xcb_get_window_attributes_reply(
                            connection, xcb_get_window_attributes(connection, window), 0);
                    xcb_get_property_reply_t *window_class = xcb_get_property_reply(
                        connection,
                        xcb_get_property(connection, 0, window, wm_class,
                                         XCB_GET_PROPERTY_TYPE_ANY, 0, 64),
                        0);
                    int result = attributes && attributes->map_state == XCB_MAP_STATE_VIEWABLE &&
                                 window_class && xcb_get_property_value_length(window_class) > 0;
                    free(attributes);
                    free(window_class);
                    return result;
                }

                static xcb_window_t find_desktop(xcb_connection_t *connection,
                                                 xcb_screen_t *screen,
                                                 xcb_atom_t wm_class,
                                                 xcb_window_t excluded) {
                    xcb_window_t desktop = XCB_WINDOW_NONE;
                    uint32_t largest_area = 0;
                    xcb_query_tree_reply_t *tree = xcb_query_tree_reply(
                        connection, xcb_query_tree(connection, screen->root), 0);
                    if (!tree) return desktop;
                    int count = xcb_query_tree_children_length(tree);
                    xcb_window_t *children = xcb_query_tree_children(tree);
                    for (int index = 0; index < count; index++) {
                        xcb_window_t child = children[index];
                        if (child == excluded) continue;
                        xcb_get_window_attributes_reply_t *attributes =
                            xcb_get_window_attributes_reply(
                                connection, xcb_get_window_attributes(connection, child), 0);
                        xcb_get_property_reply_t *window_class = xcb_get_property_reply(
                            connection,
                            xcb_get_property(connection, 0, child, wm_class,
                                             XCB_GET_PROPERTY_TYPE_ANY, 0, 64), 0);
                        xcb_get_geometry_reply_t *geometry = xcb_get_geometry_reply(
                            connection, xcb_get_geometry(connection, child), 0);
                        if (attributes && geometry && window_class &&
                            attributes->map_state == XCB_MAP_STATE_VIEWABLE &&
                            xcb_get_property_value_length(window_class) == 0) {
                            uint32_t area = geometry->width * geometry->height;
                            if (area > largest_area) {
                                largest_area = area;
                                desktop = child;
                            }
                        }
                        free(attributes);
                        free(window_class);
                        free(geometry);
                    }
                    free(tree);
                    return desktop;
                }

                int main(int argc, char **argv) {
                    xcb_connection_t *connection = xcb_connect(0, 0);
                    if (xcb_connection_has_error(connection)) return 69;
                    xcb_screen_t *screen = xcb_setup_roots_iterator(xcb_get_setup(connection)).data;

                    const char atom_name[] = "WM_CLASS";
                    xcb_intern_atom_reply_t *wm_class = xcb_intern_atom_reply(
                        connection,
                        xcb_intern_atom(connection, 0, sizeof(atom_name) - 1, atom_name), 0);
                    if (!wm_class) return 70;

                    if (argc == 2 && strcmp(argv[1], "--focus-desktop") == 0) {
                        xcb_window_t desktop = find_desktop(
                            connection, screen, wm_class->atom, XCB_WINDOW_NONE);
                        if (desktop == XCB_WINDOW_NONE) {
                            free(wm_class);
                            xcb_disconnect(connection);
                            return 2;
                        }
                        uint32_t above = XCB_STACK_MODE_ABOVE;
                        xcb_configure_window(connection, desktop,
                                             XCB_CONFIG_WINDOW_STACK_MODE, &above);
                        xcb_set_input_focus(connection, XCB_INPUT_FOCUS_POINTER_ROOT,
                                            desktop, XCB_CURRENT_TIME);
                        xcb_flush(connection);
                        free(wm_class);
                        xcb_disconnect(connection);
                        return 0;
                    }

                    xcb_window_t target = XCB_WINDOW_NONE;
                    xcb_get_input_focus_reply_t *focus = xcb_get_input_focus_reply(
                        connection, xcb_get_input_focus(connection), 0);
                    if (focus && focus->focus != XCB_WINDOW_NONE && focus->focus != screen->root) {
                        xcb_window_t current = focus->focus;
                        while (current != screen->root) {
                            xcb_query_tree_reply_t *tree = xcb_query_tree_reply(
                                connection, xcb_query_tree(connection, current), 0);
                            if (!tree) break;
                            xcb_window_t parent = tree->parent;
                            free(tree);
                            if (parent == screen->root) {
                                if (is_application(connection, current, wm_class->atom)) target = current;
                                break;
                            }
                            current = parent;
                        }
                    }
                    free(focus);

                    /* If an Android overlay disturbed focus, use the top-most mapped app. */
                    if (target == XCB_WINDOW_NONE) {
                        xcb_query_tree_reply_t *tree = xcb_query_tree_reply(
                            connection, xcb_query_tree(connection, screen->root), 0);
                        if (tree) {
                            int count = xcb_query_tree_children_length(tree);
                            xcb_window_t *children = xcb_query_tree_children(tree);
                            for (int index = count - 1; index >= 0; index--) {
                                if (is_application(connection, children[index], wm_class->atom)) {
                                    target = children[index];
                                    break;
                                }
                            }
                            free(tree);
                        }
                    }

                    if (target == XCB_WINDOW_NONE) {
                        free(wm_class);
                        xcb_disconnect(connection);
                        return 2;
                    }

                    xcb_get_geometry_reply_t *original = xcb_get_geometry_reply(
                        connection, xcb_get_geometry(connection, target), 0);
                    if (!original) {
                        free(wm_class);
                        xcb_disconnect(connection);
                        return 70;
                    }

                    uint32_t geometry[] = {0, 0, screen->width_in_pixels,
                                           screen->height_in_pixels, 0};
                    /*
                     * With no X11 WM, resizing a root child leaves stale backing pixels.
                     * Reparent it into a clean full-screen frame and keep that frame alive
                     * until the application closes.
                     */
                    xcb_unmap_window(connection, target);
                    xcb_window_t frame = xcb_generate_id(connection);
                    uint32_t frame_values[] = {
                        screen->black_pixel,
                        XCB_EVENT_MASK_EXPOSURE | XCB_EVENT_MASK_SUBSTRUCTURE_NOTIFY
                    };
                    xcb_create_window(
                        connection, screen->root_depth, frame, screen->root,
                        0, 0, screen->width_in_pixels, screen->height_in_pixels, 0,
                        XCB_WINDOW_CLASS_INPUT_OUTPUT, screen->root_visual,
                        XCB_CW_BACK_PIXEL | XCB_CW_EVENT_MASK, frame_values);
                    xcb_change_save_set(connection, XCB_SET_MODE_INSERT, target);
                    xcb_reparent_window(connection, target, frame, 0, 0);
                    xcb_configure_window(
                        connection, target,
                        XCB_CONFIG_WINDOW_X | XCB_CONFIG_WINDOW_Y |
                        XCB_CONFIG_WINDOW_WIDTH | XCB_CONFIG_WINDOW_HEIGHT |
                        XCB_CONFIG_WINDOW_BORDER_WIDTH,
                        geometry);
                    uint32_t child_background = screen->black_pixel;
                    xcb_change_window_attributes(
                        connection, target, XCB_CW_BACK_PIXEL, &child_background);
                    xcb_map_window(connection, frame);
                    xcb_map_window(connection, target);
                    xcb_clear_area(connection, 1, target, 0, 0, 0, 0);
                    xcb_set_input_focus(connection, XCB_INPUT_FOCUS_POINTER_ROOT,
                                        target, XCB_CURRENT_TIME);
                    xcb_flush(connection);

                    signal(SIGUSR1, request_home);
                    signal(SIGTERM, request_restore);
                    signal(SIGINT, request_restore);
                    FILE *pid_file = fopen("/run/nativOS/x11-fullscreen.pid", "w");
                    if (pid_file) {
                        fprintf(pid_file, "%ld\n", (long) getpid());
                        fclose(pid_file);
                    }

                    int target_destroyed = 0;
                    while (!requested_action && !xcb_connection_has_error(connection)) {
                        xcb_generic_event_t *event;
                        while ((event = xcb_poll_for_event(connection)) != 0) {
                            uint8_t type = event->response_type & 0x7f;
                            if (type == XCB_DESTROY_NOTIFY) {
                                xcb_destroy_notify_event_t *destroyed =
                                    (xcb_destroy_notify_event_t *) event;
                                if (destroyed->window == target) target_destroyed = 1;
                            }
                            free(event);
                        }
                        if (target_destroyed) break;
                        usleep(20000);
                    }

                    if (!target_destroyed && requested_action) {
                        xcb_unmap_window(connection, target);
                        xcb_reparent_window(connection, target, screen->root,
                                            original->x, original->y);
                        uint32_t restored[] = {
                            (uint32_t) original->x, (uint32_t) original->y,
                            original->width, original->height, original->border_width
                        };
                        xcb_configure_window(
                            connection, target,
                            XCB_CONFIG_WINDOW_X | XCB_CONFIG_WINDOW_Y |
                            XCB_CONFIG_WINDOW_WIDTH | XCB_CONFIG_WINDOW_HEIGHT |
                            XCB_CONFIG_WINDOW_BORDER_WIDTH,
                            restored);
                        xcb_destroy_window(connection, frame);

                        if (requested_action == 1) {
                            /* Home minimizes the unmanaged app so Phosh is unobscured. */
                            xcb_window_t desktop = find_desktop(
                                connection, screen, wm_class->atom, frame);
                            if (desktop != XCB_WINDOW_NONE) {
                                uint32_t above = XCB_STACK_MODE_ABOVE;
                                xcb_configure_window(connection, desktop,
                                                     XCB_CONFIG_WINDOW_STACK_MODE, &above);
                                xcb_set_input_focus(connection, XCB_INPUT_FOCUS_POINTER_ROOT,
                                                    desktop, XCB_CURRENT_TIME);
                            }
                        } else {
                            xcb_map_window(connection, target);
                            xcb_clear_area(connection, 1, target, 0, 0, 0, 0);
                            xcb_set_input_focus(connection, XCB_INPUT_FOCUS_POINTER_ROOT,
                                                target, XCB_CURRENT_TIME);
                        }
                        xcb_flush(connection);
                    }
                    unlink("/run/nativOS/x11-fullscreen.pid");
                    free(original);
                    free(wm_class);
                    xcb_disconnect(connection);
                    return 0;
                }
                """.trimIndent()
            )
            val result = execChroot(
                "mkdir -p /usr/local/bin && " +
                    "gcc -O2 -o /usr/local/bin/nativos-maximize-x11-v6 ${source.absolutePath} -lxcb"
            )
            if (result != 0) Log.w(TAG, "Could not build X11 maximize helper")
        } catch (error: Throwable) {
            Log.w(TAG, "Could not prepare X11 maximize helper", error)
        }
    }

    /** Resize only Phoc's undecorated outer X11 window; ordinary X11 apps are untouched. */
    fun resizePhoshDisplay(width: Int, height: Int): Boolean {
        if (width <= 0 || height <= 0 || !isRunning()) return false
        val helper = File(rootfsDir, "usr/local/bin/nativos-resize-phoc")
        if (!helper.exists()) return false
        val result = execChroot(
            "TMPDIR=${tmpDir.absolutePath} DISPLAY=:0 " +
                "LD_PRELOAD=/usr/local/lib/libsocket_hook.so " +
                "/usr/local/bin/nativos-resize-phoc $width $height"
        )
        if (result == 0) {
            Log.i(TAG, "Resized Phoc X11 output to ${width}x${height}")
            return true
        }
        Log.w(TAG, "Phoc X11 output resize failed (exit $result)")
        return false
    }

    /** Force the focused unmanaged X11 application to occupy the complete Linux display. */
    fun maximizeActiveX11Window(): Boolean {
        if (!isRunning()) return false
        prepareDisplayResizeHelper()
        val helper = File(rootfsDir, "usr/local/bin/nativos-maximize-x11-v6")
        if (!helper.exists()) return false
        return execChroot(
            "TMPDIR=${tmpDir.absolutePath} DISPLAY=:0 " +
                "LD_PRELOAD=/usr/local/lib/libsocket_hook.so " +
                "/usr/local/bin/nativos-maximize-x11-v6"
        ) == 0
    }

    /** Raise Phoc's outer X11 window so subsequent input reaches the Wayland desktop. */
    fun focusPhoshWindow(): Boolean {
        prepareDisplayResizeHelper()
        val helper = File(rootfsDir, "usr/local/bin/nativos-maximize-x11-v6")
        if (!helper.exists()) return false
        return execChroot(
            "TMPDIR=${tmpDir.absolutePath} DISPLAY=:0 " +
                "LD_PRELOAD=/usr/local/lib/libsocket_hook.so " +
                "/usr/local/bin/nativos-maximize-x11-v6 --focus-desktop"
        ) == 0
    }

    /** Leave forced X11 fullscreen and optionally return keyboard focus to Phosh. */
    fun restoreMaximizedX11Window(focusDesktop: Boolean): Boolean {
        val pidFile = File(rootfsDir, "run/nativOS/x11-fullscreen.pid")
        if (!pidFile.exists()) return false
        val signal = if (focusDesktop) "USR1" else "TERM"
        return execChroot(
            "test -s /run/nativOS/x11-fullscreen.pid && " +
                "kill -$signal \"${'$'}(cat /run/nativOS/x11-fullscreen.pid)\""
        ) == 0
    }

    /**
     * Provision a regular Linux user for interactive terminals. Android still
     * uses its root bridge to manage mounts and the private chroot, but terminal
     * shells default to metmc; privileged commands go through sudo.
     *
     * This is idempotent so existing installations are migrated without
     * deleting or re-extracting the user's Linux filesystem.
     */
    internal fun ensureDefaultLinuxUser() {
        val result = execChroot(
            """
                set -eu
                if ! id -u metmc >/dev/null 2>&1; then
                    useradd --create-home --shell /bin/bash metmc
                fi
                if ! command -v sudo >/dev/null 2>&1; then
                    echo "Offline rootfs is missing sudo; rebuild the bundled rootfs with sudo preinstalled." >&2
                    exit 127
                fi
                # Root account is locked; admin actions must be explicit sudo commands.
                passwd -l root >/dev/null 2>&1 || true
                install -d -m 0755 /etc/sudoers.d
                printf '%s\n' 'Defaults !requiretty' 'metmc ALL=(ALL:ALL) NOPASSWD: ALL' > /etc/sudoers.d/metmc
                chmod 0440 /etc/sudoers.d/metmc
                if command -v visudo >/dev/null 2>&1; then visudo -cf /etc/sudoers.d/metmc; fi
                # Remove the old global shell redirect, which could leave a blank terminal.
                if [ -f /etc/bash.bashrc ]; then
                    sed -i '/^# METMC_DEFAULT_TERMINAL_USER:/,/METMC_DEFAULT_TERMINAL_USER/d' /etc/bash.bashrc
                fi
                # Install a desktop-safe launcher: normal apps as metmc, admin tools as root.
                cat > /usr/local/bin/metmc-launch <<'METMC_LAUNCH_EOF'
#!/bin/bash
set -u
usage(){ echo "Usage: metmc-launch [--root|--auto] -- command [args...]" >&2; exit 2; }
MODE=auto
[ "${'$'}#" -gt 0 ] || usage
if [ "${'$'}1" = "--root" ]; then MODE=root; shift; fi
if [ "${'$'}1" = "--auto" ]; then MODE=auto; shift; fi
[ "${'$'}#" -gt 1 ] && [ "${'$'}1" = "--" ] || usage
shift
[ "${'$'}#" -gt 0 ] || usage
BASE=${'$'}{${'$'}1##*/}
case "${'$'}BASE" in
  apk|rc-service|rc-update|mount|umount|parted|mkfs*|fsck*|useradd|usermod|userdel|groupadd|groupdel|passwd|chpasswd|systemctl|service|reboot|poweroff|shutdown|fwupdmgr)
    [ "${'$'}MODE" = auto ] && MODE=root ;;
esac
if [ "${'$'}MODE" = root ]; then
  command -v sudo >/dev/null 2>&1 || { echo "METMC launcher: sudo is missing in the Linux rootfs." >&2; exit 127; }
  exec sudo -E -- "${'$'}@"
fi
id metmc >/dev/null 2>&1 || { echo "METMC launcher: Linux user metmc is missing; reopen METMC OS to provision it." >&2; exit 127; }
KEEP='DISPLAY WAYLAND_DISPLAY XDG_RUNTIME_DIR DBUS_SESSION_BUS_ADDRESS DBUS_SYSTEM_BUS_ADDRESS PULSE_SERVER PIPEWIRE_REMOTE XAUTHORITY LD_PRELOAD PATH LANG LANGUAGE TERM'
ENVARGS=()
for name in ${'$'}KEEP; do
  if printenv "${'$'}name" >/dev/null 2>&1; then ENVARGS+=("${'$'}name=$(printenv "${'$'}name")"); fi
done
exec sudo -E -u metmc -- env "${'$'}{ENVARGS[@]}" "${'$'}@"
METMC_LAUNCH_EOF
                chmod 0755 /usr/local/bin/metmc-launch
                # Lock direct root login; administrative work goes through sudo as metmc.
                passwd -l root >/dev/null 2>&1 || true
                rm -rf /root/.local/share/flatpak /home/metmc/.local/share/flatpak /var/lib/flatpak /etc/flatpak /usr/share/flatpak
                rm -f /usr/local/bin/bwrap /usr/local/bin/nativos-flatpak-drop /run/nativos-flatpak-drop
            chown -R metmc:metmc "${'$'}METMC_HOME" /tmp/runtime-metmc
                chmod 0750 /home/metmc
            """.trimIndent()
        )
        if (result == 0) {
            Log.i(TAG, "Linux user metmc is provisioned; direct root login is locked and sudo is configured")
        } else {
            Log.w(TAG, "Could not fully provision metmc default Linux user (exit $result)")
        }
    }

    // ── Session management ──

    /**
     * Start Labwc as the unprivileged Alpine user on the Android-hosted X11 display.
     * Labwc's wlroots X11 backend renders the Wayland desktop into the existing Android X server.
     */
    @Synchronized
    fun startLabwcSession(screenWidth: Int = 1080, screenHeight: Int = 2160) {
        if (!hasRoot() || !isRootfsReady()) {
            Log.e(TAG, "Cannot start Labwc: root or rootfs unavailable")
            return
        }
        if (isRunning()) stopTrackedSessionProcess()
        killRootfsProcesses()
        ensureMounts()
        ensureDefaultLinuxUser()
        bindX11Socket()
        prepareCloseRangeCompatibility()
        val tmpPath = tmpDir.absolutePath
        val runScript = """
            set -u
            export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
            export TMPDIR=${tmpPath} DISPLAY=:0 LANG=C.UTF-8 LC_ALL=C.UTF-8
            export WLR_BACKENDS=x11 WLR_RENDERER=gles2 WLR_LIBINPUT_NO_DEVICES=1 WLR_NO_HARDWARE_CURSORS=1
            export XDG_SESSION_TYPE=wayland XDG_CURRENT_DESKTOP=Labwc XDG_SESSION_DESKTOP=labwc
            export XDG_DATA_DIRS=/usr/local/share:/usr/share
            mkdir -p /run/dbus /run/metmc
            rm -f /run/dbus/pid /run/dbus/system_bus_socket
            if command -v dbus-uuidgen >/dev/null 2>&1; then dbus-uuidgen --ensure=/etc/machine-id; fi
            if command -v dbus-daemon >/dev/null 2>&1; then dbus-daemon --system --fork --nopidfile 2>/tmp/metmc-dbus.log || true; fi
            if [ -x /usr/local/bin/nativOS-bridge ]; then
                /usr/local/bin/nativOS-bridge >/tmp/metmc-bridge.log 2>&1 &
            fi
            {
                date -Is 2>/dev/null || date
                echo '=== power supplies ==='; ls -la /sys/class/power_supply 2>/dev/null || true
                echo '=== backlight ==='; ls -la /sys/class/backlight 2>/dev/null || true
                echo '=== graphics ==='; ls -la /dev/dri 2>/dev/null || true
                echo '=== input ==='; ls -la /dev/input 2>/dev/null || true
            } > /run/metmc/hardware-status.txt
            chmod 0644 /run/metmc/hardware-status.txt
            id metmc >/dev/null 2>&1 || adduser -D -s /bin/bash metmc
            METMC_HOME=$(getent passwd metmc | cut -d: -f6)
            mkdir -p "${'$'}METMC_HOME" /tmp/runtime-metmc
            chown -R metmc:metmc "${'$'}METMC_HOME" /tmp/runtime-metmc
            chmod 0700 /tmp/runtime-metmc
            PRELOAD=""
            for lib in /usr/local/lib/libsocket_hook.so /usr/local/lib/libnativos-close-range.so /usr/local/lib/libandroid-shmem.so; do
                if [ -f "${'$'}lib" ]; then PRELOAD="${'$'}{PRELOAD:+${'$'}PRELOAD:}${'$'}lib"; fi
            done
            export METMC_HOME PRELOAD
            runuser -u metmc --preserve-environment -- /bin/bash -c '
                export HOME="${'$'}METMC_HOME" USER=metmc LOGNAME=metmc DISPLAY=:0
                export TMPDIR=/tmp XDG_RUNTIME_DIR=/tmp/runtime-metmc WAYLAND_DISPLAY=wayland-0
                export LD_PRELOAD="${'$'}PRELOAD"
                export WLR_BACKENDS=x11 WLR_RENDERER=gles2 WLR_LIBINPUT_NO_DEVICES=1 WLR_NO_HARDWARE_CURSORS=1
                export XDG_SESSION_TYPE=wayland XDG_CURRENT_DESKTOP=Labwc XDG_SESSION_DESKTOP=labwc
                export GDK_BACKEND=wayland,x11 QT_QPA_PLATFORM='wayland;xcb' MOZ_ENABLE_WAYLAND=1 SDL_VIDEODRIVER=wayland,x11
                if [ -x /usr/bin/phosh-session ] && [ -x /usr/bin/phoc ] && [ -x /usr/libexec/phosh ]; then
                    echo "Starting MT-Phosh on MT-Phoc nested inside Android X11"
                    echo "Graphics: trying GLES2/EGL hardware rendering first"
                    ls -l /dev/dri 2>/dev/null || true
                    export XDG_CURRENT_DESKTOP=Phosh XDG_SESSION_DESKTOP=phosh
                    if WLR_RENDERER=gles2 dbus-run-session -- phosh-session; then
                        exit 0
                    else
                        renderer_status=$?
                        echo "Phosh/GLES2 exited with status ${'$'}{renderer_status}; retrying with pixman software rendering"
                        WLR_RENDERER=pixman exec dbus-run-session -- phosh-session
                    fi
                fi
                if [ -x /usr/bin/labwc ] && [ -x /usr/bin/waybar ] && [ -x /usr/bin/fuzzel ]; then
                    echo "Starting nested Labwc fallback desktop on Android X11"
                    export XDG_CURRENT_DESKTOP=Labwc XDG_SESSION_DESKTOP=labwc
                    if WLR_RENDERER=gles2 dbus-run-session -- labwc; then
                        exit 0
                    else
                        renderer_status=$?
                        echo "Labwc GLES2 exited with status ${'$'}{renderer_status}; retrying with pixman software rendering"
                        WLR_RENDERER=pixman exec dbus-run-session -- labwc
                    fi
                fi
                echo "ERROR: Ubuntu Phosh/Phoc and the fallback desktop are missing from the bundled rootfs" >&2
                exit 127
            '
        """.trimIndent()
        Log.i(TAG, "Starting Ubuntu Phosh/Phoc with Labwc fallback")
        val su = rootShell.findSuPath() ?: return
        val fullCommand = "chroot ${rootfsDir.absolutePath} /usr/bin/env -i /bin/sh -c ${shellQuote(runScript)}"
        val process = ProcessBuilder(su, "-c", fullCommand).redirectErrorStream(true).start()
        sessionProcess = process
        Thread {
            try {
                process.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { Log.d(TAG, "SESSION: $it") }
                }
            } catch (_: java.io.IOException) {
                Log.d(TAG, "Session output stream closed")
            }
        }.start()
    }

    /** Verify that Labwc and its touch-sized taskbar are actually running. */
    fun awaitLabwcReady(timeoutMs: Long = 45_000): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (sessionProcess?.isAlive != true) return false
            val ready = if (isPhoshInstalled()) {
                execChroot("pgrep -x phoc >/dev/null 2>&1 && pgrep -x phosh >/dev/null 2>&1") == 0
            } else {
                execChroot("pgrep -x labwc >/dev/null 2>&1 && pgrep -x waybar >/dev/null 2>&1") == 0
            }
            if (ready) {
                Log.i(TAG, if (isPhoshInstalled()) "MT-Phosh and MT-Phoc readiness check passed" else "Labwc compositor and Waybar taskbar readiness check passed")
                Thread.sleep(700)
                return true
            }
            try { Thread.sleep(300) } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return false
            }
        }
        Log.e(TAG, "Labwc compositor and taskbar did not become ready within ${timeoutMs}ms")
        return false
    }

    private fun stopTrackedSessionProcess() {
        sessionProcess?.let { process ->
            try {
                process.destroyForcibly()
                process.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)
            } catch (error: Exception) {
                Log.w(TAG, "Error stopping tracked session: ${error.message}")
            }
        }
        sessionProcess = null
    }

    /** Kill only processes whose filesystem root is this app's chroot. */
    private fun killRootfsProcesses() {
        val root = rootfsDir.absolutePath
        val command = """
            targets=''
            for proc in /proc/[0-9]*; do
                [ "${'$'}proc/root" -ef '${'$'}root' ] 2>/dev/null || continue
                targets="${'$'}targets ${'$'}{proc#/proc/}"
            done
            if [ -n "${'$'}targets" ]; then
                kill -TERM ${'$'}targets 2>/dev/null || true
                sleep 1
                for pid in ${'$'}targets; do
                    [ "/proc/${'$'}pid/root" -ef '${'$'}root' ] 2>/dev/null &&
                        kill -KILL "${'$'}pid" 2>/dev/null || true
                done
            fi
        """.trimIndent()
        try {
            val result = rootShell.exec(command) { output ->
                if (output.isNotBlank()) Log.d(TAG, "cleanup: ${output.trimEnd()}")
            }
            if (result == 0) {
                Log.i(TAG, "Cleared stale chroot processes")
            } else {
                Log.w(TAG, "Stale chroot cleanup exited with code $result")
            }
        } catch (error: Exception) {
            Log.w(TAG, "Could not clear stale chroot processes", error)
        }
    }

    /** Stop the chroot session and unmount bind mounts. */
    fun stopSession() {
        Log.i(TAG, "Stopping session...")
        stopTrackedSessionProcess()
        killRootfsProcesses()
        unmountAll()
        Log.i(TAG, "Session stopped")
    }

    /** Unmount all NativOS-related mounts. */
    fun unmountAll() {
        if (!hasRoot()) return
        val mounts = rootShell.exec("mount").lines()
        // Unmount every nested bind mount under the rootfs (including removable
        // storage), deepest first, so a distro migration can safely rename it.
        val mountedTargets = mounts.mapNotNull { line ->
            val target = line.substringAfter(" on ", "").substringBefore(" type ")
            if (target == rootfsDir.absolutePath || target.startsWith(rootfsDir.absolutePath + "/")) target else null
        }
        val targets = (mountedTargets + listOf(
            File(rootfsDir, "mnt/android").absolutePath,
            File(rootfsDir, "run/nativOS").absolutePath,
            File(rootfsDir, "tmp/.X11-unix").absolutePath,
            File(rootfsDir, "dev/pts").absolutePath,
            File(rootfsDir, "dev/shm").absolutePath,
            File(rootfsDir, "dev").absolutePath,
            File(rootfsDir, "proc").absolutePath,
            File(rootfsDir, "sys").absolutePath,
            File(rootfsDir, "run").absolutePath,
            File(rootfsDir, "tmp").absolutePath,
            rootfsDir.absolutePath
        )).distinct().sortedByDescending { it.length }
        targets.forEach { target ->
            if (mounts.any { it.contains(" on $target ") }) {
                try {
                    rootShell.exec("umount -l $target 2>/dev/null || umount $target 2>/dev/null || true")
                    Log.i(TAG, "Unmounted $target")
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to unmount $target: ${e.message}")
                }
            }
        }
    }

    // ── Command execution ──

    /** Execute a command inside the chroot as root. */
    fun execChroot(command: String, onLog: (String) -> Unit = {}): Int {
        // Never inherit Android's TMPDIR (normally /data/local/tmp): that path
        // does not exist inside the chroot and breaks package-management temporary directories.
        val wrapped = "export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin; " +
            "export TMPDIR=/tmp HOME=/root; $command"
        val output = rootShell.exec("chroot ${rootfsDir.absolutePath} /bin/bash -c ${shellQuote(wrapped)}") { chunk ->
            Log.d(TAG, "chroot: ${chunk.trimEnd()}")
            onLog(chunk)
        }
        return output
    }

    private fun mountIfNeeded(relative: String, mountArgs: String, alreadyMounted: () -> Boolean) {
        if (alreadyMounted()) return
        val target = File(rootfsDir, relative).absolutePath
        try {
            rootShell.exec("mkdir -p $target && mount $mountArgs $target")
            Log.i(TAG, "Mounted $target")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to mount $target: ${e.message}")
        }
    }

    private fun shellQuote(input: String): String {
        return "'" + input.replace("'", "'\"'\"'") + "'"
    }
}
