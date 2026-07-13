package com.uclone.restore.sync

import android.os.IBinder
import java.lang.reflect.InvocationTargetException

internal object RootPackageStateBridge {
    private const val packageNamePattern = "[A-Za-z0-9_.]+"

    @JvmStatic
    fun main(args: Array<String>) {
        val exitCode = runCatching {
            enableHiddenApiAccess()
            when (args.firstOrNull()) {
                "--probe" -> {
                    require(args.size == 1)
                    packageManager()
                    println("PM_BRIDGE_READY")
                }

                "--set-stopped" -> {
                    require(args.size == 6)
                    require(args[1] == "--user")
                    require(args[3] == "--package")
                    require(args[5] == "true" || args[5] == "false")
                    val userId = args[2].toIntOrNull()?.takeIf { it >= 0 }
                        ?: error("invalid user")
                    val packageName = args[4].takeIf { it.matches(Regex(packageNamePattern)) }
                        ?: error("invalid package")
                    setPackageStoppedState(packageManager(), packageName, args[5].toBooleanStrict(), userId)
                    println("PM_BRIDGE_SET_STOPPED:user=$userId package=$packageName stopped=${args[5]}")
                }

                else -> error("invalid command")
            }
            0
        }.getOrElse { throwable ->
            val cause = unwrap(throwable)
            System.err.println("PM_BRIDGE_ERROR:${cause.javaClass.simpleName}:${cause.message.orEmpty()}")
            1
        }
        if (exitCode != 0) {
            System.exit(exitCode)
        }
    }

    private fun enableHiddenApiAccess() {
        runCatching {
            val runtimeClass = Class.forName("dalvik.system.VMRuntime")
            val runtime = runtimeClass.getDeclaredMethod("getRuntime").invoke(null)
            runtimeClass.getDeclaredMethod("setHiddenApiExemptions", Array<String>::class.java)
                .invoke(runtime, arrayOf("L"))
        }
    }

    private fun packageManager(): Any {
        val serviceManager = Class.forName("android.os.ServiceManager")
        val binder = serviceManager.getMethod("getService", String::class.java)
            .invoke(null, "package") as? IBinder
            ?: error("package service unavailable")
        val stub = Class.forName("android.content.pm.IPackageManager\$Stub")
        return stub.getMethod("asInterface", IBinder::class.java).invoke(null, binder)
            ?: error("package manager unavailable")
    }

    private fun setPackageStoppedState(packageManager: Any, packageName: String, stopped: Boolean, userId: Int) {
        val method = packageManager.javaClass.methods.singleOrNull { candidate ->
            candidate.name == "setPackageStoppedState" &&
                candidate.parameterTypes.size == 3 &&
                candidate.parameterTypes[0] == String::class.java &&
                candidate.parameterTypes[1] == Boolean::class.javaPrimitiveType &&
                candidate.parameterTypes[2] == Int::class.javaPrimitiveType
        } ?: error("setPackageStoppedState unavailable")
        method.invoke(packageManager, packageName, stopped, userId)
    }

    private fun unwrap(throwable: Throwable): Throwable =
        if (throwable is InvocationTargetException && throwable.targetException != null) {
            throwable.targetException
        } else {
            throwable
        }
}
