package com.remodex.android.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.remodex.android.core.data.RemodexRepository
import com.remodex.android.core.model.AccessMode
import com.remodex.android.core.model.GitDiffResult
import com.remodex.android.core.model.RemodexUiState
import com.remodex.android.core.model.ThreadRuntimeOverride
import com.remodex.android.core.security.QrPairingValidationResult
import com.remodex.android.core.security.QrPairingValidator
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.remodex.android.core.voice.GptTranscriptionClient
import com.remodex.android.core.voice.TranscriptionException
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement

class RemodexAppViewModel(
    private val repository: RemodexRepository,
) : ViewModel() {
    val uiState: StateFlow<RemodexUiState> = repository.uiState
    var pairingFeedback by mutableStateOf<String?>(null)
        private set
    var diffResult by mutableStateOf<GitDiffResult?>(null)
        private set
    var voiceTranscribing by mutableStateOf(false)
        private set
    private val transcriptionClient = GptTranscriptionClient()

    init {
        if (uiState.value.hasSavedPairing) {
            reconnect()
        }
    }

    fun reconnect() {
        pairingFeedback = null
        viewModelScope.launch {
            repository.connectFromSavedSession()
        }
    }

    fun onPairingCodeSubmitted(rawCode: String) {
        when (val result = QrPairingValidator.validate(rawCode)) {
            is QrPairingValidationResult.Success -> {
                pairingFeedback = null
                viewModelScope.launch {
                    repository.connectWithPairing(result.payload)
                }
            }
            is QrPairingValidationResult.ScanError -> {
                pairingFeedback = result.message
            }
            is QrPairingValidationResult.BridgeUpdateRequired -> {
                pairingFeedback = result.message
            }
        }
    }

    fun disconnect(clearPairing: Boolean = false) {
        pairingFeedback = null
        viewModelScope.launch {
            repository.disconnect(clearPairing)
        }
    }

    fun clearPairingFeedback() {
        pairingFeedback = null
    }

    fun refreshThreads() {
        viewModelScope.launch {
            repository.refreshThreads()
        }
    }

    fun selectThread(threadId: String) {
        viewModelScope.launch {
            repository.openThread(threadId)
        }
    }

    fun startThread() {
        viewModelScope.launch {
            repository.startThread()
        }
    }

    fun sendTurn(threadId: String, text: String) {
        viewModelScope.launch {
            repository.sendTurn(threadId, text)
        }
    }

    fun interruptTurn(threadId: String) {
        viewModelScope.launch {
            repository.interruptTurn(threadId)
        }
    }

    fun respondToApproval(accept: Boolean) {
        viewModelScope.launch {
            repository.respondToApproval(accept)
        }
    }

    // Git actions
    fun gitStatus(cwd: String) {
        viewModelScope.launch { repository.gitStatus(cwd) }
    }

    fun gitCommit(cwd: String, message: String? = null) {
        viewModelScope.launch {
            repository.gitCommit(cwd, message)
            repository.gitStatus(cwd)
        }
    }

    fun gitPush(cwd: String) {
        viewModelScope.launch {
            repository.gitPush(cwd)
            repository.gitStatus(cwd)
        }
    }

    fun gitPull(cwd: String) {
        viewModelScope.launch {
            repository.gitPull(cwd)
            repository.gitStatus(cwd)
        }
    }

    fun gitBranches(cwd: String) {
        viewModelScope.launch { repository.gitBranches(cwd) }
    }

    fun gitDiff(cwd: String) {
        viewModelScope.launch {
            diffResult = repository.gitDiff(cwd)
        }
    }

    fun gitCheckout(cwd: String, branch: String) {
        viewModelScope.launch {
            repository.gitCheckout(cwd, branch)
            repository.gitStatus(cwd)
        }
    }

    fun clearDiffResult() {
        diffResult = null
    }

    // Runtime config
    fun setRuntimeOverride(threadId: String, override: ThreadRuntimeOverride) {
        repository.setRuntimeOverride(threadId, override)
    }

    // Access mode
    fun setAccessMode(mode: AccessMode) {
        viewModelScope.launch { repository.setAccessMode(mode) }
    }

    // Structured input
    fun respondToStructuredInput(requestId: JsonElement, answers: Map<String, String>) {
        viewModelScope.launch { repository.respondToStructuredInput(requestId, answers) }
    }

    // Foreground tracking
    fun onForegroundResume() {
        repository.isAppInForeground.set(true)
        viewModelScope.launch { repository.attemptAutoReconnect() }
    }

    fun onBackgroundPause() {
        repository.isAppInForeground.set(false)
    }

    // Voice transcription
    fun transcribeVoice(wavData: ByteArray, onResult: (String) -> Unit) {
        voiceTranscribing = true
        viewModelScope.launch {
            try {
                val token = repository.resolveVoiceAuthToken()
                if (token == null) {
                    voiceTranscribing = false
                    return@launch
                }
                val text = transcriptionClient.transcribe(wavData, token)
                onResult(text)
            } catch (e: TranscriptionException.AuthExpired) {
                // Try once more with fresh token
                val freshToken = repository.resolveVoiceAuthToken()
                if (freshToken != null) {
                    try {
                        val text = transcriptionClient.transcribe(wavData, freshToken)
                        onResult(text)
                    } catch (_: Exception) { /* give up */ }
                }
            } catch (_: Exception) { /* give up */ }
            finally {
                voiceTranscribing = false
            }
        }
    }
}
