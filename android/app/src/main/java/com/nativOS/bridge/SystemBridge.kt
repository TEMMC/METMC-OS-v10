package com.nativOS.bridge

import android.content.Context
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.IntentFilter
import android.hardware.camera2.CameraManager
import android.os.BatteryManager
import android.provider.Settings
import android.telephony.SignalStrength
import android.telephony.TelephonyManager
import android.util.Log
import org.json.JSONObject

/**
 * System bridge — handles brightness, battery, flashlight, signal strength,
 * and other device-wide controls.
 *
 * Actions: set_brightness, get_battery, torch_on, torch_off, get_signal
 * Events: battery_changed, signal_changed
 */
class SystemBridge(private val service: BridgeService) {

    companion object {
        private const val TAG = "NativOS.System"
    }

    private val cameraManager = service.getSystemService(Context.CAMERA_SERVICE) as CameraManager

    fun handle(request: BridgeRequest): String {
        return when (request.action) {
            BridgeProtocol.ACTION_SET_BRIGHTNESS -> handleSetBrightness(request)
            BridgeProtocol.ACTION_GET_BATTERY -> handleGetBattery(request)
            "get_brightness" -> handleGetBrightness(request)
            "get_clipboard" -> handleGetClipboard(request)
            "set_clipboard" -> handleSetClipboard(request)
            BridgeProtocol.ACTION_TORCH_ON -> handleTorch(request, true)
            BridgeProtocol.ACTION_TORCH_OFF -> handleTorch(request, false)
            BridgeProtocol.ACTION_GET_SIGNAL -> handleGetSignal(request)
            "get_device_info" -> handleGetDeviceInfo(request)
            else -> BridgeProtocol.response(
                request.id, BridgeProtocol.STATUS_ERROR,
                JSONObject().put("message", "Unknown system action: ${request.action}")
            )
        }
    }

    private fun handleGetClipboard(request: BridgeRequest): String {
        return try {
            val clipboard = service.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = clipboard.primaryClip
            val text = if (clip != null && clip.itemCount > 0) {
                clip.getItemAt(0).coerceToText(service)?.toString().orEmpty()
            } else ""
            BridgeProtocol.response(request.id, BridgeProtocol.STATUS_OK, JSONObject().apply {
                put("text", text)
                put("has_text", text.isNotEmpty())
            })
        } catch (e: Exception) {
            BridgeProtocol.response(request.id, BridgeProtocol.STATUS_ERROR,
                JSONObject().put("message", "Clipboard read failed; Android only allows clipboard reads while METMC OS is in the foreground: ${e.message}"))
        }
    }

    private fun handleSetClipboard(request: BridgeRequest): String {
        val text = request.params.optString("text", "")
        if (text.length > 1000000) {
            return BridgeProtocol.response(request.id, BridgeProtocol.STATUS_ERROR,
                JSONObject().put("message", "Clipboard text is limited to 1 MB"))
        }
        return try {
            val clipboard = service.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("METMC OS", text))
            BridgeProtocol.response(request.id, BridgeProtocol.STATUS_OK,
                JSONObject().put("copied", true).put("length", text.length))
        } catch (e: Exception) {
            BridgeProtocol.response(request.id, BridgeProtocol.STATUS_ERROR,
                JSONObject().put("message", "Clipboard write failed: ${e.message}"))
        }
    }

    private fun handleSetBrightness(request: BridgeRequest): String {
        val level = request.params.optInt("level", -1) // 0-255
        if (level !in 0..255) {
            return BridgeProtocol.response(request.id, BridgeProtocol.STATUS_ERROR,
                JSONObject().put("message", "Brightness must be 0-255"))
        }
        if (!Settings.System.canWrite(service)) {
            return BridgeProtocol.response(request.id, BridgeProtocol.STATUS_ERROR,
                JSONObject().put("message", "Allow METMC OS to modify system settings in Android Settings to control brightness"))
        }
        return try {
            Settings.System.putInt(
                service.contentResolver,
                Settings.System.SCREEN_BRIGHTNESS_MODE,
                Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
            )
            val changed = Settings.System.putInt(
                service.contentResolver,
                Settings.System.SCREEN_BRIGHTNESS,
                level
            )
            if (!changed) throw IllegalStateException("Android rejected the brightness change")
            BridgeProtocol.response(request.id, BridgeProtocol.STATUS_OK,
                JSONObject().put("brightness", level))
        } catch (e: Exception) {
            BridgeProtocol.response(request.id, BridgeProtocol.STATUS_ERROR,
                JSONObject().put("message", "Failed to set brightness: ${e.message}"))
        }
    }

    private fun handleGetBrightness(request: BridgeRequest): String {
        return try {
            val level = Settings.System.getInt(
                service.contentResolver,
                Settings.System.SCREEN_BRIGHTNESS,
                128
            ).coerceIn(0, 255)
            val automatic = Settings.System.getInt(
                service.contentResolver,
                Settings.System.SCREEN_BRIGHTNESS_MODE,
                Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
            ) == Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC
            BridgeProtocol.response(request.id, BridgeProtocol.STATUS_OK, JSONObject().apply {
                put("brightness", level)
                put("can_write_settings", Settings.System.canWrite(service))
                put("automatic", automatic)
            })
        } catch (e: Exception) {
            BridgeProtocol.response(request.id, BridgeProtocol.STATUS_ERROR,
                JSONObject().put("message", "Could not read brightness: ${e.message}"))
        }
    }

    private fun handleGetBattery(request: BridgeRequest): String {
        val batteryIntent = service.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = batteryIntent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = batteryIntent?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val status = batteryIntent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val temperature = batteryIntent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0
        val voltage = batteryIntent?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0) ?: 0
        val plugged = batteryIntent?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0

        val percentage = if (scale > 0) (level * 100 / scale) else level
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                       status == BatteryManager.BATTERY_STATUS_FULL

        return BridgeProtocol.response(request.id, BridgeProtocol.STATUS_OK, JSONObject().apply {
            put("percentage", percentage)
            put("charging", charging)
            put("status", when (status) {
                BatteryManager.BATTERY_STATUS_CHARGING -> "charging"
                BatteryManager.BATTERY_STATUS_DISCHARGING -> "discharging"
                BatteryManager.BATTERY_STATUS_FULL -> "full"
                BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "not_charging"
                else -> "unknown"
            })
            put("temperature", temperature / 10.0) // tenths of degree C
            put("voltage", voltage / 1000.0) // mV to V
            put("plugged", when (plugged) {
                BatteryManager.BATTERY_PLUGGED_AC -> "ac"
                BatteryManager.BATTERY_PLUGGED_USB -> "usb"
                BatteryManager.BATTERY_PLUGGED_WIRELESS -> "wireless"
                else -> "none"
            })
        })
    }

    private fun handleTorch(request: BridgeRequest, on: Boolean): String {
        return try {
            val cameraId = cameraManager.cameraIdList.firstOrNull() ?: "0"
            cameraManager.setTorchMode(cameraId, on)
            Log.i(TAG, "Torch: ${if (on) "ON" else "OFF"}")
            BridgeProtocol.response(request.id, BridgeProtocol.STATUS_OK,
                JSONObject().put("torch", on))
        } catch (e: Exception) {
            BridgeProtocol.response(request.id, BridgeProtocol.STATUS_ERROR,
                JSONObject().put("message", "Torch failed: ${e.message}"))
        }
    }

    @Suppress("MissingPermission")
    private fun handleGetSignal(request: BridgeRequest): String {
        return try {
            val telephony = service.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
            val data = JSONObject().apply {
                put("network_type", telephony.dataNetworkType)
                put("operator", telephony.networkOperatorName ?: "Unknown")
                put("sim_state", when (telephony.simState) {
                    TelephonyManager.SIM_STATE_READY -> "ready"
                    TelephonyManager.SIM_STATE_ABSENT -> "absent"
                    TelephonyManager.SIM_STATE_PIN_REQUIRED -> "pin_required"
                    else -> "unknown"
                })
                put("phone_type", when (telephony.phoneType) {
                    TelephonyManager.PHONE_TYPE_GSM -> "gsm"
                    TelephonyManager.PHONE_TYPE_CDMA -> "cdma"
                    TelephonyManager.PHONE_TYPE_SIP -> "sip"
                    else -> "none"
                })
            }
            BridgeProtocol.response(request.id, BridgeProtocol.STATUS_OK, data)
        } catch (e: SecurityException) {
            BridgeProtocol.response(request.id, BridgeProtocol.STATUS_ERROR,
                JSONObject().put("message", "Phone state permission not granted"))
        }
    }

    private fun handleGetDeviceInfo(request: BridgeRequest): String {
        return BridgeProtocol.response(request.id, BridgeProtocol.STATUS_OK, JSONObject().apply {
            put("brand", android.os.Build.BRAND)
            put("model", android.os.Build.MODEL)
            put("device", android.os.Build.DEVICE)
            put("android_version", android.os.Build.VERSION.RELEASE)
            put("sdk_level", android.os.Build.VERSION.SDK_INT)
            put("soc", android.os.Build.SOC_MODEL)
            put("abi", android.os.Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown")
        })
    }
}
