package indi.renakoni.nextvol.data.update

import android.app.Application
import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.content.pm.SigningInfo
import android.os.Build
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException

@Suppress("DEPRECATION")
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [24, 30], application = Application::class)
class UpdateApkValidatorTest {
    private val context = mockk<Context>()
    private val manager = mockk<PackageManager>()
    private val file = File("NextVol-update.apk")
    private lateinit var archive: PackageInfo

    private fun apk(name: String = "indi.renakoni.nextvol", version: Int = 20, signer: String = "1234") =
        PackageInfo().apply {
            packageName = name
            versionCode = version
            val certificates = arrayOf(Signature(signer))
            if (Build.VERSION.SDK_INT >= 28) {
                signingInfo = mockk<SigningInfo>().also { every { it.apkContentsSigners } returns certificates }
            } else signatures = certificates
        }

    @Before fun prepare() {
        every { context.packageManager } returns manager
        every { context.packageName } returns "indi.renakoni.nextvol"
        every { context.getString(any()) } returns "Invalid update"
        every { manager.getPackageInfo("indi.renakoni.nextvol", any<Int>()) } returns apk(version = 10)
        archive = apk()
        every { manager.getPackageArchiveInfo(file.absolutePath, any<Int>()) } answers { archive }
    }

    @Test fun acceptsANewerReleaseSignedByTheInstalledApp() {
        validateUpdateApk(context, file, 20)
    }

    @Test fun rejectsUpstreamAndDifferentBuildVariants() {
        for (name in listOf("indi.dmzz_yyhyy.lightnovelreader", "indi.renakoni.nextvol.debug", "indi.renakoni.nextvol.snapshot")) {
            archive = apk(name = name)
            assertThrows(IOException::class.java) { validateUpdateApk(context, file, 20) }
        }
    }

    @Test fun rejectsDowngradesAndMetadataMismatches() {
        for (version in listOf(9, 10, 19, 21)) {
            archive = apk(version = version)
            assertThrows(IOException::class.java) { validateUpdateApk(context, file, 20) }
        }
    }

    @Test fun rejectsDifferentSigners() {
        archive = apk(signer = "5678")
        assertThrows(IOException::class.java) { validateUpdateApk(context, file, 20) }
    }

    @Test fun rejectsUnsignedAndUnparseableApks() {
        if (Build.VERSION.SDK_INT >= 28) archive.signingInfo = null else archive.signatures = null
        assertThrows(IOException::class.java) { validateUpdateApk(context, file, 20) }
        every { manager.getPackageArchiveInfo(file.absolutePath, any<Int>()) } returns null
        assertThrows(IOException::class.java) { validateUpdateApk(context, file, 20) }
    }
}
