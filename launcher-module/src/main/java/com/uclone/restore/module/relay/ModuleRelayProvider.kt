package com.uclone.restore.module.relay

import android.app.PendingIntent
import android.content.ComponentName
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.os.Binder
import java.util.UUID
import kotlin.math.absoluteValue

class ModuleRelayProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        val context = requireNotNull(context)
        val callerPackages = context.packageManager.getPackagesForUid(Binder.getCallingUid()).orEmpty().toSet()
        val allowedLaunchers = ModuleSettingsStore.allowedLaunchers()
        if (callerPackages.none { it in allowedLaunchers }) {
            return rejected("caller not allowed: ${callerPackages.joinToString()}")
        }
        return when (method) {
            ModuleConstants.METHOD_QUERY_MENU_STATE -> queryMenuState(extras)
            ModuleConstants.METHOD_RECORD_HOOK_EVENT -> {
                val event = extras?.getString(ModuleRelayContract.EXTRA_HOOK_EVENT).orEmpty()
                val kind = extras?.getString(ModuleRelayContract.EXTRA_EVENT_KIND).orEmpty()
                if (event.isNotBlank()) ModuleSettingsStore.recordHookEvent(context, event, kind == "error")
                accepted(showMenu = false, message = "recorded")
            }
            else -> rejected("unknown method: $method")
        }
    }

    private fun queryMenuState(extras: Bundle?): Bundle {
        val context = requireNotNull(context)
        if (!ModuleSettingsStore.isHookEnabled(context)) return accepted(showMenu = false, message = "hook disabled")
        val packageName = extras?.getString(ModuleRelayContract.EXTRA_PACKAGE_NAME)?.takeIf { it.isNotBlank() }
            ?: return accepted(showMenu = false, message = "missing packageName")
        val userId = extras.getInt(ModuleRelayContract.EXTRA_TARGET_USER_ID, -1)
        if (userId != 0) return accepted(showMenu = false, message = "non-user0 target: $userId")
        if (packageName == ModuleConstants.MODULE_PACKAGE || packageName == ModuleConstants.UCLONE_PACKAGE) {
            return accepted(showMenu = false, message = "self package blocked")
        }
        if (!ModuleSettingsStore.isPackageAllowed(context, packageName)) {
            return accepted(showMenu = false, message = "package not whitelisted")
        }

        val requestId = extras.getString(ModuleRelayContract.EXTRA_REQUEST_ID)?.takeIf { it.isNotBlank() }
            ?: UUID.randomUUID().toString()
        val serviceIntent = Intent(context, ModuleRelayService::class.java)
            .putExtra(ModuleRelayContract.EXTRA_OPERATION, ModuleRelayContract.OPERATION_SWITCH_OR_RESTORE)
            .putExtra(ModuleRelayContract.EXTRA_PACKAGE_NAME, packageName)
            .putExtra(ModuleRelayContract.EXTRA_COMPONENT_NAME, extras.getString(ModuleRelayContract.EXTRA_COMPONENT_NAME).orEmpty())
            .putExtra(ModuleRelayContract.EXTRA_TARGET_USER_ID, userId)
            .putExtra(ModuleRelayContract.EXTRA_REQUEST_ID, requestId)

        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val pendingIntent = PendingIntent.getService(context, requestId.hashCode().absoluteValue, serviceIntent, flags)

        return accepted(showMenu = true, message = "ready").apply {
            putString(ModuleRelayContract.EXTRA_MENU_LABEL, ModuleConstants.MENU_LABEL)
            putString(ModuleRelayContract.EXTRA_REQUEST_ID, requestId)
            putParcelable(ModuleRelayContract.EXTRA_PENDING_INTENT, pendingIntent)
        }
    }

    private fun accepted(showMenu: Boolean, message: String): Bundle =
        Bundle().apply {
            putString(ModuleRelayContract.STATUS, ModuleRelayContract.STATUS_ACCEPTED)
            putBoolean(ModuleRelayContract.EXTRA_SHOW_MENU, showMenu)
            putString(ModuleRelayContract.EXTRA_MESSAGE, message)
        }

    private fun rejected(message: String): Bundle =
        Bundle().apply {
            putString(ModuleRelayContract.STATUS, ModuleRelayContract.STATUS_REJECTED)
            putBoolean(ModuleRelayContract.EXTRA_SHOW_MENU, false)
            putString(ModuleRelayContract.EXTRA_MESSAGE, message)
        }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
