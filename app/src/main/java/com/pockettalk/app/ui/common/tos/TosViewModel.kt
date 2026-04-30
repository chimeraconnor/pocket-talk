package com.pockettalk.app.ui.common.tos

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class TosViewModel @Inject constructor() : ViewModel() {
  fun getIsTosAccepted(): Boolean = true
  fun acceptTos() {}
}
