package com.tobevpn.app.presentation.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tobevpn.app.data.local.PrefsDataStore
import com.tobevpn.app.data.remote.BotApi
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

// Fallback defaults used only when /api/config is unreachable or omits a field.
private const val DEFAULT_ANON_TRAFFIC_BYTES = 1_073_741_824L  // 1 GB — pre-sign-in

@HiltViewModel
class OnboardingViewModel @Inject constructor(
    private val prefsDataStore: PrefsDataStore,
    private val botApi: BotApi,
) : ViewModel() {

    private val _trialTerms = MutableStateFlow(TrialTermsUiState())
    val trialTerms: StateFlow<TrialTermsUiState> = _trialTerms.asStateFlow()

    init {
        loadTrialTerms()
    }

    fun completeOnboarding() {
        viewModelScope.launch {
            prefsDataStore.setOnboardingSeen()
        }
    }

    private fun loadTrialTerms() {
        viewModelScope.launch {
            runCatching { botApi.getConfig() }
                .getOrNull()
                ?.data
                ?.let { config ->
                    _trialTerms.value = TrialTermsUiState(
                        anonBytes = config.anonTrafficBytes.takeIf { it > 0 }
                            ?: DEFAULT_ANON_TRAFFIC_BYTES,
                    )
                }
        }
    }
}

data class TrialTermsUiState(
    /** Traffic available before signing in (anonymous). */
    val anonBytes: Long = DEFAULT_ANON_TRAFFIC_BYTES,
)
