package com.uclone.restore.sync

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import java.security.MessageDigest

fun interface PackageSigningIdentityProvider {
    fun certificateSha256(packageName: String): String
}

internal class AndroidPackageSigningIdentityProvider(context: Context) : PackageSigningIdentityProvider {
    private val packageManager = context.packageManager

    override fun certificateSha256(packageName: String): String {
        val flags = PackageManager.GET_SIGNING_CERTIFICATES or PackageManager.MATCH_UNINSTALLED_PACKAGES
        val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(flags.toLong()))
        } else {
            @Suppress("DEPRECATION")
            packageManager.getPackageInfo(packageName, flags)
        }
        val signers = packageInfo.signingInfo?.apkContentsSigners.orEmpty()
        check(signers.isNotEmpty()) { "无法读取 $packageName 的签名证书" }
        return signingCertificateSetSha256(signers.map { it.toByteArray() })
    }
}

internal fun signingCertificateSetSha256(certificates: List<ByteArray>): String {
    require(certificates.isNotEmpty())
    return certificates
        .map { certificate -> MessageDigest.getInstance("SHA-256").digest(certificate).toHex() }
        .distinct()
        .sorted()
        .joinToString(",")
}

private fun ByteArray.toHex(): String {
    val bytes = this
    return buildString(bytes.size * 2) {
        bytes.forEach { byte ->
            val value = byte.toInt() and 0xff
            append(HEX[value ushr 4])
            append(HEX[value and 0x0f])
        }
    }
}

private const val HEX = "0123456789abcdef"
