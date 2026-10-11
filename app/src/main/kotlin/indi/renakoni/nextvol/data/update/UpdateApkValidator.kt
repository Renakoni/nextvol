package indi.renakoni.nextvol.data.update

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import indi.renakoni.nextvol.R
import java.io.File
import java.io.IOException

/** Check identity before exposing an installer intent or an install notification. */
@Suppress("DEPRECATION")
internal fun validateUpdateApk(context: Context, file: File, expectedVersion: Int) {
    val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES
        else PackageManager.GET_SIGNATURES
    val manager = context.packageManager
    val archive = manager.getPackageArchiveInfo(file.absolutePath, flags)
        ?: throw IOException(context.getString(R.string.update_invalid_apk))
    val installed = manager.getPackageInfo(context.packageName, flags)
    fun version(info: PackageInfo) = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode
        else info.versionCode.toLong()
    fun signatures(info: PackageInfo) = if (Build.VERSION.SDK_INT >= 28)
        info.signingInfo?.apkContentsSigners?.toSet() else info.signatures?.toSet()

    if (archive.packageName != context.packageName) {
        throw IOException(context.getString(R.string.update_wrong_package))
    }
    if (version(archive) != expectedVersion.toLong() || version(archive) <= version(installed)) {
        throw IOException(context.getString(R.string.update_wrong_version))
    }
    val installedSignatures = signatures(installed)
    if (installedSignatures.isNullOrEmpty() || signatures(archive) != installedSignatures) {
        throw IOException(context.getString(R.string.update_wrong_signature))
    }
}
