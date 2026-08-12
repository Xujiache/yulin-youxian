package com.yulin.rider

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.setContent
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.yulin.rider.core.common.RiderResult
import com.yulin.rider.core.common.RiderTime
import com.yulin.rider.core.datastore.RiderSettings
import com.yulin.rider.core.datastore.RiderSettingsStore
import com.yulin.rider.core.datastore.RiderTokenStore
import com.yulin.rider.core.designsystem.LocalRiderSoundEnabled
import com.yulin.rider.core.designsystem.RiderTheme
import com.yulin.rider.core.location.KeepAliveChecker
import com.yulin.rider.core.location.KeepAliveStatus
import com.yulin.rider.core.location.AmapPrivacyConsent
import com.yulin.rider.core.location.LocationController
import com.yulin.rider.core.location.LocationStartResult
import com.yulin.rider.core.location.RiderDeviceStore
import com.yulin.rider.core.model.GeoPoint
import com.yulin.rider.core.network.RiderApis
import com.yulin.rider.core.network.SessionEvents
import com.yulin.rider.core.push.PushController
import com.yulin.rider.core.push.PushNotifier
import com.yulin.rider.feature.exception.ExceptionFeature
import com.yulin.rider.feature.auth.AuthRepository
import com.yulin.rider.feature.profile.ProfileFeature
import com.yulin.rider.feature.profile.ProfileFeatureDependencies
import com.yulin.rider.feature.shift.ShiftFeature
import com.yulin.rider.feature.shift.ShiftFeatureDependencies
import com.yulin.rider.feature.task.TaskFeature
import com.yulin.rider.feature.task.data.ActionSyncWorker
import com.yulin.rider.feature.task.data.TaskRepository
import com.yulin.rider.feature.task.data.TaskSection
import com.yulin.rider.navigation.RiderNavHost
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var tokenStore: RiderTokenStore
    @Inject lateinit var settingsStore: RiderSettingsStore
    @Inject lateinit var sessionEvents: SessionEvents
    @Inject lateinit var locationController: LocationController
    @Inject lateinit var keepAliveChecker: KeepAliveChecker
    @Inject lateinit var deviceStore: RiderDeviceStore
    @Inject lateinit var pushController: PushController
    @Inject lateinit var sessionCoordinator: RiderSessionCoordinator
    @Inject lateinit var authRepository: AuthRepository

    /** 派单通知要打开的任务;导航图消费后置回 null。 */
    private val newTaskRequests = MutableStateFlow<Long?>(null)

    /** 上班时定位没起来这类「不挡流程但必须告知」的提示。 */
    private val dutyAlert = MutableStateFlow<String?>(null)

    /** 常驻通知上的「在线时长」起点,下班清空。 */
    private var onDutyAtMillis: Long? = null
    private var onDutyShiftId: Long? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // targetSdk 35 起系统强制边到边，内容会画到状态栏与导航栏下面。
        // 骑手端只有浅色，顶栏和吸底条都是白的，这里把系统栏图标固定成深色，
        // 否则跟随系统深色时会是白图标压在白底上，等于看不见。
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
        )

        installFeatureBridges()
        // 异常上报由 feature/exception 直接写队列,它调不到 feature/task 的即时重放,
        // 没有这条周期任务那条动作会一直躺在队列里
        lifecycleScope.launch {
            val session = tokenStore.current()
            AmapPrivacyConsent.update(this@MainActivity, session?.hasLocationConsent == true)
            if (session != null) ActionSyncWorker.enqueuePeriodic(this@MainActivity)
        }
        observeRiderSignals()
        handlePushIntent(intent)

        setContent {
            val settings by settingsStore.settingsFlow.collectAsState(initial = RiderSettings())

            KeepScreenOn(enabled = settings.keepScreenOn)

            // 骑手端只有浅色一套，不再跟随系统或手动切换
            RiderTheme {
                val density = LocalDensity.current
                CompositionLocalProvider(
                    LocalRiderSoundEnabled provides settings.soundEnabled,
                    // App 内字号倍率叠加在系统字号之上,设置页调大后全局生效
                    LocalDensity provides Density(
                        density = density.density,
                        fontScale = density.fontScale * settings.fontScale,
                    ),
                ) {
                    RiderNavHost(
                        tokenStore = tokenStore,
                        settingsStore = settingsStore,
                        sessionEvents = sessionEvents,
                        locationController = locationController,
                        newTaskRequests = newTaskRequests,
                        onNewTaskHandled = { newTaskRequests.value = null },
                        onSessionEnded = ::endSession,
                    )

                    val alert by dutyAlert.collectAsState()
                    alert?.let { message ->
                        AlertDialog(
                            onDismissRequest = { dutyAlert.value = null },
                            title = {
                                Text(
                                    text = "定位没能启动",
                                    style = MaterialTheme.typography.headlineSmall,
                                )
                            },
                            text = {
                                Text(
                                    text = message,
                                    style = MaterialTheme.typography.bodyLarge,
                                )
                            },
                            confirmButton = {
                                TextButton(onClick = { dutyAlert.value = null }) {
                                    Text(
                                        text = "知道了",
                                        style = MaterialTheme.typography.labelLarge,
                                    )
                                }
                            },
                        )
                    }
                }
            }
        }
    }

    /** singleTask:App 已在前台时,通知点击只会走这里,不会重建 Activity。 */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handlePushIntent(intent)
    }

    /**
     * 各 feature 模块没有 Hilt 插件,拿不到 core/location、core/push 的单例,
     * 由 app 层在这里把接缝接上。全部是幂等赋值,重建 Activity 再调一次也无副作用。
     */
    private fun installFeatureBridges() {
        TaskFeature.installLocationProvider { currentLocation() }
        ExceptionFeature.installLocationProvider { currentLocation() }

        ProfileFeature.install(object : ProfileFeatureDependencies {
            override val appVersionName: String = BuildConfig.VERSION_NAME
            override val appVersionCode: Long = BuildConfig.VERSION_CODE.toLong()
            // 备案号尚未下发,关于页会显示「备案办理中」
            override val icpFilingNumber: String? = null
            override suspend fun logout(): RiderResult<Unit> {
                val result = authRepository.logout()
                sessionCoordinator.endSession()
                return result
            }
        })

        ShiftFeature.install(object : ShiftFeatureDependencies {
            override fun keepAliveStatus(): KeepAliveStatus = keepAliveChecker.current()
            override val deviceId: String get() = deviceStore.deviceId
            override fun currentLocation(): GeoPoint? = this@MainActivity.currentLocation()
            override fun onShiftStartRequested(): Boolean =
                locationController.prepareForDuty() is LocationStartResult.Started

            override fun onShiftStarted(shiftId: Long, onDutyAt: String) {
                val startedAt = RiderTime.toEpochMillis(onDutyAt) ?: System.currentTimeMillis()
                onDutyShiftId = shiftId
                onDutyAtMillis = startedAt
                locationController.confirmDuty(shiftId, startedAt)
                ActionSyncWorker.enqueuePeriodic(this@MainActivity)
                pushController.onDutyStarted()
            }

            override fun onShiftStartFailed() = stopDutyResources()
            override fun onShiftStopped() = stopDutyResources()
        })
    }

    /**
     * 跨模块信号只有 app 层能同时看见两端,统一接在这里。
     * 绑在 STARTED 上:App 不可见时既不该刷列表,也不该拉起前台服务。
     */
    private fun observeRiderSignals() {
        val taskRepository = TaskRepository.get(this)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                // 3 秒轮询是当前唯一的派单通道,版本号变了就得把列表拉回来,
                // 否则骑手要手动下拉才看得见新单
                launch {
                    pushController.sync
                        .map { it?.taskVersion }
                        .filterNotNull()
                        .distinctUntilChanged()
                        .collect { taskRepository.refresh() }
                }
                // 位置回包搭车下发的指令
                launch {
                    locationController.commands
                        .filter { it.type == COMMAND_REFRESH_TASKS }
                        .collect { taskRepository.refresh() }
                }
                // 位置上报要带上「现在在跑哪一单」,常驻通知要显示在途单量;
                // 任务在 feature/task、定位在 core/location,只有这里同时看得见
                launch {
                    taskRepository.observeBoard()
                        .map { board -> board.allTasks.filter { it.section == TaskSection.IN_PROGRESS } }
                        .distinctUntilChanged()
                        .collect { active ->
                            val current = active.firstOrNull()
                            locationController.bindTask(current?.taskId, current?.waveId)
                            locationController.updateShift(onDutyShiftId, onDutyAtMillis, active.size)
                        }
                }
                launch {
                    settingsStore.settingsFlow
                        .map { it.voiceEnabled }
                        .distinctUntilChanged()
                        .collect { pushController.voiceEnabled = it }
                }
                launch { resumeDutyIfNeeded() }
            }
        }
    }

    /**
     * 进程被 ROM 杀掉后骑手重新打开 App:服务端仍记着他在岗,本机的定位服务与轮询却都没了。
     * 此刻 App 可见,正是唯一允许拉起定位前台服务的时机。
     */
    private suspend fun resumeDutyIfNeeded() {
        if (locationController.started.value || tokenStore.current() == null) return
        val apis = RiderApis.of(this)
        val shift = (apis.caller.result { apis.shift.getCurrentShift() } as? RiderResult.Success)?.data
        if (shift?.onDuty == true && !locationController.started.value) {
            val shiftId = shift.shiftId ?: return
            startDutyResources(shiftId, System.currentTimeMillis() - shift.onlineSeconds * 1000L)
        }
    }

    /**
     * 上班后要开两样东西:定位前台服务、3 秒 /sync 轮询。
     *
     * 定位起不来不能连带把轮询也停掉 —— 此时服务端已经认为骑手在岗并开始派单,
     * 不轮询就等于接不到单,比位置缺失严重得多。
     */
    private fun startDutyResources(shiftId: Long, startedAtMillis: Long) {
        when (val result = locationController.start(shiftId, startedAtMillis)) {
            is LocationStartResult.Started -> {
                onDutyShiftId = shiftId
                onDutyAtMillis = startedAtMillis
                locationController.updateShift(shiftId, startedAtMillis, 0)
            }

            is LocationStartResult.Rejected ->
                dutyAlert.value = "${result.message}。新单仍会正常提醒你,但调度台看不到你的位置,请尽快处理后重新上班。"
        }
        ActionSyncWorker.enqueuePeriodic(this)
        pushController.onDutyStarted()
    }

    private fun stopDutyResources() {
        onDutyShiftId = null
        onDutyAtMillis = null
        locationController.stop()
        locationController.updateShift(null, null, 0)
        locationController.bindTask(null, null)
        pushController.onDutyEnded()
    }

    private fun endSession() {
        onDutyAtMillis = null
        lifecycleScope.launch { sessionCoordinator.endSession() }
    }

    private fun currentLocation(): GeoPoint? = locationController.lastFix.value?.toGeoPoint()

    private fun handlePushIntent(intent: Intent?) {
        if (intent?.getStringExtra(PushNotifier.EXTRA_ROUTE) != PushNotifier.ROUTE_NEW_TASK) return
        // 骑手已经点开看了,循环播报和常驻的派单通知到此为止
        pushController.acknowledgeNewTask(pushController.resolveTaskVersionFromIntent(intent))
        newTaskRequests.value = pushController.resolveTaskIdFromIntent(intent)
        // 不清掉的话,进程被回收后系统重投同一个 Intent 会再跳一次
        intent.removeExtra(PushNotifier.EXTRA_ROUTE)
    }

    /** 骑行时屏幕熄灭要重新解锁才能看下一单,危险且慢。 */
    @androidx.compose.runtime.Composable
    private fun KeepScreenOn(enabled: Boolean) {
        DisposableEffect(enabled) {
            if (enabled) {
                window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            } else {
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
            onDispose { window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
        }
    }

    private companion object {
        /** 04 §1.4 位置回包里的搭车指令。 */
        const val COMMAND_REFRESH_TASKS = "REFRESH_TASKS"
    }
}
