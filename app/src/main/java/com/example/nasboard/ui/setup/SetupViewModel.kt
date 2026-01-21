// SetupViewModel.kt
package com.example.nasboard.ui.setup

import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel

class SetupViewModel : ViewModel() {
    val isAllDone = MutableLiveData(false)
}