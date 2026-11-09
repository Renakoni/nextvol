package indi.renakoni.nextvol.ui.dialog

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import indi.renakoni.nextvol.data.userdata.UserDataRepository
import io.nightfish.lightnovelreader.api.userdata.FloatUserData
import jakarta.inject.Inject
import kotlinx.coroutines.flow.Flow

@HiltViewModel
class SliderValueDialogViewModel @Inject constructor(
    private val userDataRepository: UserDataRepository
) : ViewModel() {
    private var floatUserData: FloatUserData? = null

    fun init(floatUserDataPath: String): Flow<Float> {
        floatUserData = userDataRepository.floatUserData(floatUserDataPath)
        val value = floatUserData!!.getFlowWithDefault(Float.NaN)
        return value
    }

    fun setValue(value: Float) {
        floatUserData?.asynchronousSet(value)
    }
}
