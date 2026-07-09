package com.uclone.restore.module.hook

import android.app.Application
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Bundle
import android.os.UserHandle
import android.util.Log
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import com.uclone.restore.module.relay.ModuleConstants
import com.uclone.restore.module.relay.ModuleRelayContract
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.UUID

class UCloneLauncherModule : XposedModule() {
    override fun onModuleLoaded(param: ModuleLoadedParam) {
        log(Log.INFO, TAG, "loaded process=${param.processName} api=$apiVersion framework=$frameworkName")
    }

    override fun onPackageReady(param: PackageReadyParam) {
        if (param.packageName != "com.miui.home" || !param.isFirstPackage) return
        runHook("install hooks") {
            installMenuListHook(param.classLoader)
            installMenuBindHook(param.classLoader)
        }
    }

    private fun installMenuListHook(classLoader: ClassLoader) {
        val managerClass = Class.forName("com.miui.home.launcher.shortcuts.ShortcutMenuManager", false, classLoader)
        val itemInfoClass = Class.forName("com.miui.home.model.api.ItemInfo", false, classLoader)
        val menuItemClass = Class.forName("com.miui.home.launcher.shortcuts.ShortcutMenuItem", false, classLoader)
        val method = managerClass.getDeclaredMethod("getValidSystemShortcutMenuItemList", itemInfoClass)

        hook(method).intercept { chain ->
            val result = chain.proceed()
            runCatching {
                val context = currentApplication() ?: return@runCatching
                val target = TargetInfo.from(chain.args.firstOrNull()) ?: return@runCatching
                val list = result as? MutableList<Any> ?: return@runCatching
                if (list.any { isMarkerMenuItem(it) }) return@runCatching
                val state = queryMenuState(context, target) ?: return@runCatching
                if (!state.showMenu) {
                    recordHookEvent(context, "skip package=${target.packageName} user=${target.userId} reason=${state.message}", false)
                    return@runCatching
                }
                list.add(createMarkerMenuItem(menuItemClass, target.userHandle, state.menuLabel))
                recordHookEvent(context, "inject package=${target.packageName} user=${target.userId} label=${state.menuLabel}", false)
            }.onFailure { error ->
                currentApplication()?.let { recordHookEvent(it, "inject error=${error.message}", true) }
                log(Log.ERROR, TAG, "menu list hook failed", error)
            }
            result
        }
        log(Log.INFO, TAG, "hooked ShortcutMenuManager.getValidSystemShortcutMenuItemList")
    }

    private fun installMenuBindHook(classLoader: ClassLoader) {
        val containerClass = Class.forName("com.miui.home.launcher.shortcuts.IconAndTitleShortcutMenuItemContainer", false, classLoader)
        val menuItemClass = Class.forName("com.miui.home.launcher.shortcuts.ShortcutMenuItem", false, classLoader)
        val itemInfoClass = Class.forName("com.miui.home.model.api.ItemInfo", false, classLoader)
        val method = containerClass.getDeclaredMethod("bindShortcutMenuItem", menuItemClass, itemInfoClass)

        hook(method).intercept { chain ->
            val result = chain.proceed()
            runCatching {
                val menuItem = chain.args.getOrNull(0) ?: return@runCatching
                if (!isMarkerMenuItem(menuItem)) return@runCatching
                val view = chain.thisObject as? View ?: return@runCatching
                val target = TargetInfo.from(chain.args.getOrNull(1)) ?: return@runCatching
                bindMarkerView(view, target)
            }.onFailure { error ->
                currentApplication()?.let { recordHookEvent(it, "bind error=${error.message}", true) }
                log(Log.ERROR, TAG, "menu bind hook failed", error)
            }
            result
        }
        log(Log.INFO, TAG, "hooked IconAndTitleShortcutMenuItemContainer.bindShortcutMenuItem")
    }

    private fun bindMarkerView(view: View, target: TargetInfo) {
        val context = view.context
        setFieldText(view, "mTitle", ModuleConstants.MENU_LABEL)
        setFieldIcon(view, "mIcon")
        view.setOnClickListener {
            val state = queryMenuState(context, target)
            val pendingIntent = state?.pendingIntent
            if (pendingIntent == null) {
                Toast.makeText(context, state?.message ?: "UClone 模块不可用", Toast.LENGTH_SHORT).show()
                recordHookEvent(context, "click rejected package=${target.packageName} reason=${state?.message}", false)
                return@setOnClickListener
            }
            runCatching {
                pendingIntent.send()
                Toast.makeText(context, "UClone 已接收任务", Toast.LENGTH_SHORT).show()
                recordHookEvent(context, "click sent package=${target.packageName} request=${state.requestId}", false)
            }.onFailure { error ->
                Toast.makeText(context, "UClone 启动失败：${error.message}", Toast.LENGTH_SHORT).show()
                recordHookEvent(context, "click error=${error.message}", true)
            }
        }
    }

    private fun createMarkerMenuItem(menuItemClass: Class<*>, userHandle: UserHandle?, label: String): Any {
        val item = menuItemClass.getDeclaredConstructor().newInstance()
        invokeIfExists(item, "setShortTitle", arrayOf(CharSequence::class.java), label)
        invokeIfExists(item, "setLongTitle", arrayOf(CharSequence::class.java), label)
        invokeIfExists(item, "setComponentName", arrayOf(ComponentName::class.java), MARKER_COMPONENT)
        userHandle?.let { invokeIfExists(item, "setUserHandle", arrayOf(UserHandle::class.java), it) }
        invokeIfExists(item, "setIconDrawable", arrayOf(android.graphics.drawable.Drawable::class.java), ColorDrawable(Color.rgb(0, 122, 255)))
        return item
    }

    private fun isMarkerMenuItem(item: Any): Boolean {
        val component = item.callNoArg("getComponentName") as? ComponentName
        return component == MARKER_COMPONENT
    }

    private fun queryMenuState(context: Context, target: TargetInfo): MenuState? {
        val result = context.contentResolver.call(
            RELAY_URI,
            ModuleConstants.METHOD_QUERY_MENU_STATE,
            null,
            Bundle().apply {
                putString(ModuleRelayContract.EXTRA_OPERATION, ModuleRelayContract.OPERATION_SWITCH_OR_RESTORE)
                putString(ModuleRelayContract.EXTRA_PACKAGE_NAME, target.packageName)
                putString(ModuleRelayContract.EXTRA_COMPONENT_NAME, target.componentName?.flattenToShortString().orEmpty())
                putInt(ModuleRelayContract.EXTRA_TARGET_USER_ID, target.userId)
                putString(ModuleRelayContract.EXTRA_REQUEST_ID, UUID.randomUUID().toString())
            },
        ) ?: return null
        return MenuState.from(result)
    }

    private fun recordHookEvent(context: Context, event: String, isError: Boolean) {
        runCatching {
            context.contentResolver.call(
                RELAY_URI,
                ModuleConstants.METHOD_RECORD_HOOK_EVENT,
                null,
                Bundle().apply {
                    putString(ModuleRelayContract.EXTRA_HOOK_EVENT, event)
                    putString(ModuleRelayContract.EXTRA_EVENT_KIND, if (isError) "error" else "info")
                },
            )
        }
    }

    private fun setFieldText(instance: Any, fieldName: String, text: CharSequence) {
        val field = instance.findField(fieldName) ?: return
        (field.get(instance) as? TextView)?.text = text
    }

    private fun setFieldIcon(instance: Any, fieldName: String) {
        val field = instance.findField(fieldName) ?: return
        (field.get(instance) as? ImageView)?.setImageDrawable(ColorDrawable(Color.rgb(0, 122, 255)))
    }

    private fun currentApplication(): Application? =
        runCatching {
            val activityThread = Class.forName("android.app.ActivityThread")
            activityThread.getDeclaredMethod("currentApplication").invoke(null) as? Application
        }.getOrNull()

    private fun runHook(label: String, block: () -> Unit) {
        runCatching(block).onFailure { error ->
            log(Log.ERROR, TAG, "$label failed", error)
        }
    }

    private fun invokeIfExists(target: Any, methodName: String, parameterTypes: Array<Class<*>>, vararg args: Any?) {
        val method = runCatching { target.javaClass.getMethod(methodName, *parameterTypes) }.getOrNull() ?: return
        method.invoke(target, *args)
    }

    private data class MenuState(
        val showMenu: Boolean,
        val menuLabel: String,
        val requestId: String,
        val message: String,
        val pendingIntent: PendingIntent?,
    ) {
        companion object {
            @Suppress("DEPRECATION")
            fun from(bundle: Bundle): MenuState =
                MenuState(
                    showMenu = bundle.getBoolean(ModuleRelayContract.EXTRA_SHOW_MENU, false),
                    menuLabel = bundle.getString(ModuleRelayContract.EXTRA_MENU_LABEL) ?: ModuleConstants.MENU_LABEL,
                    requestId = bundle.getString(ModuleRelayContract.EXTRA_REQUEST_ID).orEmpty(),
                    message = bundle.getString(ModuleRelayContract.EXTRA_MESSAGE).orEmpty(),
                    pendingIntent = bundle.getParcelable<PendingIntent>(ModuleRelayContract.EXTRA_PENDING_INTENT),
                )
        }
    }

    private data class TargetInfo(
        val packageName: String,
        val componentName: ComponentName?,
        val userHandle: UserHandle?,
        val userId: Int,
    ) {
        companion object {
            fun from(itemInfo: Any?): TargetInfo? {
                if (itemInfo == null) return null
                val component = itemInfo.callNoArg("getComponentName") as? ComponentName
                    ?: itemInfo.callNoArg("getTargetComponent") as? ComponentName
                    ?: (itemInfo.callNoArg("getIntent") as? Intent)?.component
                val packageName = (itemInfo.callNoArg("getPackageName") as? String)
                    ?: (itemInfo.callNoArg("getTargetPackage") as? String)
                    ?: (itemInfo.callNoArg("getTargetPackageName") as? String)
                    ?: component?.packageName
                    ?: return null
                val userHandle = (itemInfo.callNoArg("getUserHandle") as? UserHandle)
                    ?: (itemInfo.callNoArg("getUser") as? UserHandle)
                return TargetInfo(
                    packageName = packageName,
                    componentName = component,
                    userHandle = userHandle,
                    userId = userHandle.userIdOrUnknown(),
                )
            }

            private fun UserHandle?.userIdOrUnknown(): Int {
                if (this == null) return -1
                runCatching {
                    val method: Method = javaClass.getDeclaredMethod("getIdentifier")
                    method.isAccessible = true
                    return method.invoke(this) as Int
                }
                val text = toString()
                val inside = text.substringAfter('{', "").substringBefore('}', "")
                return inside.toIntOrNull() ?: -1
            }
        }
    }

    companion object {
        private const val TAG = "UCloneLauncherModule"
        private val RELAY_URI = Uri.parse("content://${ModuleConstants.PROVIDER_AUTHORITY}")
        private val MARKER_COMPONENT = ComponentName(ModuleConstants.MODULE_PACKAGE, "UCloneMenuAction")
    }
}

private fun Any.callNoArg(methodName: String): Any? =
    javaClass.methods.firstOrNull { it.name == methodName && it.parameterTypes.isEmpty() }
        ?.let { method -> runCatching { method.invoke(this) }.getOrNull() }

private fun Any.findField(fieldName: String): Field? {
    var type: Class<*>? = javaClass
    while (type != null) {
        val current = type ?: break
        runCatching {
            val field = current.getDeclaredField(fieldName)
            field.isAccessible = true
            return field
        }
        type = current.superclass
    }
    return null
}
