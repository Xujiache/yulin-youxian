package com.yulin.rider.core.update

import android.content.Context
import androidx.datastore.preferences.core.edit
import com.yulin.rider.core.datastore.RiderPreferenceKeys
import com.yulin.rider.core.datastore.riderDataStore
import com.yulin.rider.core.model.AppUpdateLatest
import com.yulin.rider.core.network.api.RiderAppUpdateApi
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

data class UpdateUiState(
    val latest: AppUpdateLatest? = null,
    val policy: String = UpdatePolicyResolver.NONE,
    val checking: Boolean = false,
    val downloading: Boolean = false,
    val downloadedBytes: Long = 0,
    val totalBytes: Long = 0,
    val ready: Boolean = false,
    val installing: Boolean = false,
    val error: String? = null,
    val managedMode: String = ManagedDevice.STANDARD,
    val showOptional: Boolean = false,
)

@Singleton
class UpdateController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val api: RiderAppUpdateApi,
    private val downloader: ApkDownloadClient,
    private val installer: UpdateInstaller,
) {
    private val mutex = Mutex()
    private val _state = MutableStateFlow(UpdateUiState(managedMode = ManagedDevice.mode(context)))
    val state: StateFlow<UpdateUiState> = _state.asStateFlow()

    fun blocksBusiness(): Boolean = _state.value.policy == UpdatePolicyResolver.FORCE

    suspend fun checkOnLaunch(): Boolean {
        check(manual = false)
        return blocksBusiness()
    }

    suspend fun check(manual: Boolean = true) {
        val shouldDownload = mutex.withLock {
            _state.value = _state.value.copy(checking = true, error = null)
            val current = ManagedDevice.currentVersionCode(context)
            val latest = runCatching { api.latest(versionCode = current).data }.getOrNull()
            context.riderDataStore.edit { it[RiderPreferenceKeys.UPDATE_LAST_CHECK_AT] = System.currentTimeMillis() }
            if (latest == null || !latest.available) {
                _state.value = _state.value.copy(
                    checking = false,
                    latest = latest,
                    policy = UpdatePolicyResolver.NONE,
                    showOptional = false,
                    error = if (manual) "已是最新版本" else null,
                )
                return@withLock false
            }
            val skipped = context.riderDataStore.data.first()[RiderPreferenceKeys.UPDATE_SKIPPED_VERSION_CODE] ?: 0L
            val showOptional = latest.isOptional && (manual || skipped != (latest.versionCode ?: 0).toLong())
            val apk = apkFile(latest.versionCode ?: 0)
            val ready = apk.isFile && ApkChecksum.matches(apk, latest.fileSha256)
            _state.value = _state.value.copy(
                checking = false,
                latest = latest,
                policy = latest.policy,
                showOptional = showOptional,
                ready = ready,
                totalBytes = latest.fileSize ?: apk.length(),
                downloadedBytes = if (ready) apk.length() else if (apk.exists()) apk.length() else 0L,
                managedMode = ManagedDevice.mode(context),
                error = null,
            )
            latest.isForce && !ready
        }
        if (shouldDownload) startDownload()
    }

    suspend fun startDownload() {
        val latest = mutex.withLock {
            _state.value = _state.value.copy(downloading = true, error = null)
            _state.value.latest
        } ?: return
        val url = latest.fileUrl ?: return
        val sha = latest.fileSha256 ?: return
        val apk = apkFile(latest.versionCode ?: 0)
        runCatching {
            downloader.download(url, apk, sha) { downloaded, total ->
                _state.value = _state.value.copy(downloadedBytes = downloaded, totalBytes = total)
            }
        }.onSuccess {
            _state.value = _state.value.copy(downloading = false, ready = true, error = null)
        }.onFailure { error ->
            _state.value = _state.value.copy(downloading = false, ready = false, error = error.message ?: "下载失败")
        }
    }

    suspend fun install() {
        val latest = _state.value.latest ?: return
        val apk = apkFile(latest.versionCode ?: 0)
        _state.value = _state.value.copy(installing = true, error = null)
        val error = installer.install(apk, latest.fileSha256.orEmpty(), latest.versionCode ?: 0)
        _state.value = _state.value.copy(
            installing = false,
            error = if (error == UpdateInstaller.NEED_UNKNOWN_SOURCES) "请允许安装未知应用后再点安装" else error,
        )
    }

    suspend fun skipOptional() {
        val version = _state.value.latest?.versionCode ?: return
        if (_state.value.policy == UpdatePolicyResolver.FORCE) return
        context.riderDataStore.edit { it[RiderPreferenceKeys.UPDATE_SKIPPED_VERSION_CODE] = version.toLong() }
        _state.value = _state.value.copy(showOptional = false)
    }

    fun dismissOptional() {
        if (_state.value.policy != UpdatePolicyResolver.FORCE) {
            _state.value = _state.value.copy(showOptional = false)
        }
    }

    fun showOptional() {
        if (_state.value.latest?.isOptional == true) {
            _state.value = _state.value.copy(showOptional = true)
        }
    }

    fun apkFile(versionCode: Int): File = File(File(context.filesDir, "updates"), "rider-$versionCode.apk")
}
