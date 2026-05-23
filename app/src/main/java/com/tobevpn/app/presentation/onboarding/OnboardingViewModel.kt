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

private const val DEFAULT_FREE_TRIAL_TRAFFIC_BYTES = 3_221_225_472L // 3 GB
private const val DEFAULT_FREE_TRIAL_DAYS = 3

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
                        trafficBytes = config.freeTrialTrafficBytes.takeIf { it > 0 }
                            ?: DEFAULT_FREE_TRIAL_TRAFFIC_BYTES,
                        trialDays = config.freeTrialDays
                            .takeIf { it > 0 }
                            ?.coerceAtLeast(DEFAULT_FREE_TRIAL_DAYS)
                            ?: DEFAULT_FREE_TRIAL_DAYS,
                    )
                }
        }
    }

}

data class TrialTermsUiState(
    val trafficBytes: Long = DEFAULT_FREE_TRIAL_TRAFFIC_BYTES,
    val trialDays: Int? = DEFAULT_FREE_TRIAL_DAYS,
)
