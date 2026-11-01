package indi.renakoni.nextvol.tts

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import dagger.hilt.android.qualifiers.ApplicationContext
import indi.renakoni.nextvol.data.userdata.UserDataRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SpeechNetworkCheck @Inject constructor(@ApplicationContext context: Context, userData: UserDataRepository) {
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private val enabled = userData.booleanUserData(SETTING_KEY)

    suspend fun check() = withContext(Dispatchers.IO) {
        if (enabled.get() != true) return@withContext
        val validated = connectivity.activeNetwork?.let(connectivity::getNetworkCapabilities)
            ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
        if (!validated) throw SpeechException(SpeechError.NetworkUnavailable)
    }

    companion object {
        const val SETTING_KEY = "tts.network.check"
    }
}
