package moe.lyniko.glyphhacker.util

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku

object ShizukuAccessibilityHelper {

    private const val LOG_TAG = "GlyphHacker-Shizuku"
    private const val SHIZUKU_PERMISSION_REQUEST_CODE = 7441
    private const val ACCESSIBILITY_SEPARATOR = ':'

    enum class Result {
        ENABLED,
        SHIZUKU_NOT_READY,
        SHIZUKU_PERMISSION_REQUESTED,
        SHIZUKU_PERMISSION_DENIED,
        GRANT_WRITE_SECURE_SETTINGS_FAILED,
        APPLY_SECURE_SETTINGS_FAILED,
    }

    suspend fun grantAndEnableAccessibility(
        context: Context,
        serviceComponent: ComponentName,
    ): Result {
        Log.d(LOG_TAG, "[AUTO_A11Y] Start grantAndEnableAccessibility")
        if (!isShizukuReady()) {
            Log.d(LOG_TAG, "[AUTO_A11Y] Shizuku binder not ready")
            return Result.SHIZUKU_NOT_READY
        }

        when (ensureShizukuPermission()) {
            PermissionResult.GRANTED -> Unit
            PermissionResult.REQUESTED -> {
                Log.d(LOG_TAG, "[AUTO_A11Y] Shizuku permission requested")
                return Result.SHIZUKU_PERMISSION_REQUESTED
            }

            PermissionResult.DENIED -> {
                Log.d(LOG_TAG, "[AUTO_A11Y] Shizuku permission denied")
                return Result.SHIZUKU_PERMISSION_DENIED
            }
        }

        val packageName = context.packageName
        val grantCommand = "pm grant $packageName ${Manifest.permission.WRITE_SECURE_SETTINGS}"
        if (!runCommandByShizuku(grantCommand)) {
            Log.d(LOG_TAG, "[AUTO_A11Y] pm grant WRITE_SECURE_SETTINGS failed")
            return Result.GRANT_WRITE_SECURE_SETTINGS_FAILED
        }
        Log.d(LOG_TAG, "[AUTO_A11Y] pm grant WRITE_SECURE_SETTINGS success")

        val resolver = context.contentResolver
        val enabledServices = Settings.Secure.getString(
            resolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        )
        val updatedServices = enabledServices
            .orEmpty()
            .split(ACCESSIBILITY_SEPARATOR)
            .filter { it.isNotBlank() }
            .toMutableSet()
            .apply { add(serviceComponent.flattenToString()) }
            .joinToString(ACCESSIBILITY_SEPARATOR.toString())

        val writeByApi = runCatching {
            val updatedList = Settings.Secure.putString(
                resolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                updatedServices,
            )
            val updatedEnabled = Settings.Secure.putInt(
                resolver,
                Settings.Secure.ACCESSIBILITY_ENABLED,
                1,
            )
            Log.d(
                LOG_TAG,
                "[AUTO_A11Y] Settings.Secure.putString=$updatedList putInt=$updatedEnabled",
            )
            updatedList && updatedEnabled
        }.getOrElse {
            Log.e(LOG_TAG, "[AUTO_A11Y] Settings.Secure write threw", it)
            false
        }

        val enabled = if (writeByApi) {
            true
        } else {
            val putServicesByShell = runCommandByShizuku(
                "settings put secure enabled_accessibility_services \"$updatedServices\"",
            )
            val putEnabledByShell = runCommandByShizuku("settings put secure accessibility_enabled 1")
            Log.d(
                LOG_TAG,
                "[AUTO_A11Y] Shell settings put services=$putServicesByShell enabled=$putEnabledByShell",
            )
            putServicesByShell && putEnabledByShell
        }

        if (enabled) {
            Log.d(LOG_TAG, "[AUTO_A11Y] Wrote secure settings success")
            return Result.ENABLED
        }

        Log.d(LOG_TAG, "[AUTO_A11Y] Wrote secure settings failed")
        return Result.APPLY_SECURE_SETTINGS_FAILED
    }

    private fun isShizukuReady(): Boolean {
        return runCatching {
            Shizuku.pingBinder()
        }.getOrDefault(false)
    }

    private fun ensureShizukuPermission(): PermissionResult {
        val granted = runCatching {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)
        if (granted) {
            Log.d(LOG_TAG, "[AUTO_A11Y] Shizuku permission already granted")
            return PermissionResult.GRANTED
        }

        val shouldShowRationale = runCatching {
            Shizuku.shouldShowRequestPermissionRationale()
        }.getOrDefault(false)
        if (shouldShowRationale) {
            Log.d(LOG_TAG, "[AUTO_A11Y] shouldShowRequestPermissionRationale=true")
            return PermissionResult.DENIED
        }

        val requested = runCatching {
            Shizuku.requestPermission(SHIZUKU_PERMISSION_REQUEST_CODE)
            true
        }.getOrElse {
            Log.e(LOG_TAG, "[AUTO_A11Y] requestPermission threw", it)
            false
        }

        return if (requested) {
            PermissionResult.REQUESTED
        } else {
            PermissionResult.DENIED
        }
    }

    private suspend fun runCommandByShizuku(command: String): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val method = Shizuku::class.java.getDeclaredMethod(
                "newProcess",
                Array<String>::class.java,
                Array<String>::class.java,
                String::class.java,
            )
            method.isAccessible = true
            val process = method.invoke(
                null,
                arrayOf("sh", "-c", command),
                null,
                null,
            ) as Process
            val exitCode = process.waitFor()
            process.destroy()
            Log.d(LOG_TAG, "[AUTO_A11Y] command exitCode=$exitCode command=$command")
            exitCode == 0
        }.getOrElse {
            Log.e(LOG_TAG, "[AUTO_A11Y] runCommandByShizuku threw", it)
            false
        }
    }

    private enum class PermissionResult {
        GRANTED,
        REQUESTED,
        DENIED,
    }
}
