package com.tobevpn.app.presentation.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tobevpn.app.data.local.PrefsDataStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class OnboardingViewModel @Inject constructor(
    private val prefsDataStore: PrefsDataStore,
) : ViewModel() {

    fun completeOnboarding() {
        viewModelScope.launch {
            prefsDataStore.setOnboardingSeen()
        }
    }
}
