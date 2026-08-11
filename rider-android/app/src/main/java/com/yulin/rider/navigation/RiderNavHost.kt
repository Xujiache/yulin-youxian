package com.yulin.rider.navigation

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.yulin.rider.core.datastore.RiderSettingsStore
import com.yulin.rider.core.datastore.RiderTokenStore
import com.yulin.rider.core.designsystem.FreshIcon
import com.yulin.rider.core.designsystem.FreshIconType
import com.yulin.rider.core.location.KeepAliveGuideScreen
import com.yulin.rider.core.location.LocationController
import com.yulin.rider.core.network.SessionEvent
import com.yulin.rider.core.network.SessionEvents
import com.yulin.rider.feature.auth.ChangePasswordRoute
import com.yulin.rider.feature.auth.LocationConsentRoute
import com.yulin.rider.feature.auth.LoginRoute
import com.yulin.rider.feature.auth.PermissionGuideRoute
import com.yulin.rider.feature.earning.TodayStatsScreen
import com.yulin.rider.feature.exception.ExceptionReportScreen
import com.yulin.rider.feature.message.MessageCenterScreen
import com.yulin.rider.feature.profile.AboutScreen
import com.yulin.rider.feature.profile.ProfileScreen
import com.yulin.rider.feature.profile.SettingsScreen
import com.yulin.rider.feature.shift.ShiftSwitchBar
import com.yulin.rider.feature.task.ui.DeliverScreen
import com.yulin.rider.feature.task.ui.PickupScreen
import com.yulin.rider.feature.task.ui.TaskDetailScreen
import com.yulin.rider.feature.task.ui.TaskHomeScreen
import com.yulin.rider.feature.task.ui.WaveDetailScreen
import com.yulin.rider.feature.task.ui.camera.CameraCapture
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 全局导航图。所有跨模块跳转都在这里发生:各 feature 只暴露回调,互相不认识。
 */
@Composable
fun RiderNavHost(
    tokenStore: RiderTokenStore,
    settingsStore: RiderSettingsStore,
    sessionEvents: SessionEvents,
    locationController: LocationController,
    /** 派单通知点开后要打开的任务;由 MainActivity 从 Intent 里解出来。 */
    newTaskRequests: StateFlow<Long?>,
    onNewTaskHandled: () -> Unit,
    /** 登录态失效:定位与轮询要跟着停,否则骑手已被登出而手机还在后台跑定位。 */
    onSessionEnded: () -> Unit,
    modifier: Modifier = Modifier,
    navController: NavHostController = rememberNavController(),
) {
    val scope = rememberCoroutineScope()

    // 定位同意状态由权限引导页读取,签署后要能立刻反映出来
    var consentVersionKey by remember { mutableStateOf(0) }
    val hasLocationConsent by produceState(initialValue = false, consentVersionKey) {
        value = tokenStore.current()?.hasLocationConsent == true
    }

    SessionEventHandler(
        sessionEvents = sessionEvents,
        navController = navController,
        onSessionEnded = onSessionEnded,
    )
    NewTaskIntentHandler(
        newTaskRequests = newTaskRequests,
        navController = navController,
        onHandled = onNewTaskHandled,
    )

    NavHost(
        navController = navController,
        startDestination = RiderRoutes.SPLASH,
        modifier = modifier,
    ) {
        composable(RiderRoutes.SPLASH) {
            SplashScreen(
                tokenStore = tokenStore,
                settingsStore = settingsStore,
                onResolved = { destination ->
                    navController.navigate(destination.route) {
                        popUpTo(RiderRoutes.SPLASH) { inclusive = true }
                    }
                },
            )
        }

        composable(RiderRoutes.LOGIN) {
            LoginRoute(
                onLoggedIn = { navController.toHome() },
                onNeedChangePassword = { navController.navigate(RiderRoutes.CHANGE_PASSWORD) },
                onNeedLocationConsent = { navController.navigate(RiderRoutes.LOCATION_CONSENT) },
            )
        }

        composable(RiderRoutes.CHANGE_PASSWORD) {
            ChangePasswordRoute(
                onDone = {
                    scope.launch {
                        val session = tokenStore.current()
                        if (session?.hasLocationConsent == true) {
                            navController.toHome()
                        } else {
                            navController.navigate(RiderRoutes.LOCATION_CONSENT) {
                                popUpTo(RiderRoutes.CHANGE_PASSWORD) { inclusive = true }
                            }
                        }
                    }
                },
            )
        }

        composable(RiderRoutes.LOCATION_CONSENT) {
            LocationConsentRoute(
                onAgreed = {
                    consentVersionKey++
                    navController.navigate(RiderRoutes.PERMISSION_GUIDE) {
                        popUpTo(RiderRoutes.LOCATION_CONSENT) { inclusive = true }
                    }
                },
                // 拒绝不等于退出登录:骑手仍可查看历史与设置,只是不能上班
                onDeclined = {
                    consentVersionKey++
                    navController.toHome()
                },
            )
        }

        composable(RiderRoutes.PERMISSION_GUIDE) {
            PermissionGuideRoute(
                hasLocationConsent = hasLocationConsent,
                onNeedLocationConsent = { navController.navigate(RiderRoutes.LOCATION_CONSENT) },
                onCompleted = {
                    scope.launch { settingsStore.setPermissionGuideDone(true) }
                    navController.toHome()
                },
            )
        }

        composable(RiderRoutes.KEEPALIVE_GUIDE) {
            KeepAliveGuideScreen(
                onBack = { navController.popBackStack() },
                onCompleted = { navController.popBackStack() },
            )
        }

        composable(RiderRoutes.HOME) {
            RiderTabScaffold(navController = navController, currentRoute = RiderRoutes.HOME) { inset ->
                TaskHomeScreen(
                    modifier = inset,
                    // 不传这个插槽首页就没有上下班开关,骑手永远收不到派单
                    shiftHeader = { ShiftSwitchBar() },
                    onOpenTask = { navController.navigate(RiderRoutes.taskDetail(it)) },
                    onOpenWave = { navController.navigate(RiderRoutes.waveDetail(it)) },
                    onOpenPickup = { navController.navigate(RiderRoutes.pickup(it)) },
                    onOpenDeliver = { navController.navigate(RiderRoutes.deliver(it)) },
                )
            }
        }

        composable(
            route = RiderRoutes.WAVE_DETAIL,
            arguments = listOf(navArgument(RiderRoutes.ARG_WAVE_ID) { type = NavType.LongType }),
        ) { entry ->
            val waveId = entry.arguments?.getLong(RiderRoutes.ARG_WAVE_ID) ?: 0L
            WaveDetailScreen(
                waveId = waveId,
                onOpenTask = { navController.navigate(RiderRoutes.taskDetail(it)) },
            )
        }

        composable(
            route = RiderRoutes.TASK_DETAIL,
            arguments = listOf(navArgument(RiderRoutes.ARG_TASK_ID) { type = NavType.LongType }),
        ) { entry ->
            val taskId = entry.arguments?.getLong(RiderRoutes.ARG_TASK_ID) ?: 0L
            TaskDetailScreen(
                taskId = taskId,
                onOpenPickup = { navController.navigate(RiderRoutes.pickup(it)) },
                onOpenDeliver = { navController.navigate(RiderRoutes.deliver(it)) },
                onOpenException = { navController.navigate(RiderRoutes.exceptionReport(it)) },
                onOpenMap = { navController.navigate(RiderRoutes.MAP) },
            )
        }

        composable(
            route = RiderRoutes.PICKUP,
            arguments = listOf(navArgument(RiderRoutes.ARG_WAVE_ID) { type = NavType.LongType }),
        ) { entry ->
            val waveId = entry.arguments?.getLong(RiderRoutes.ARG_WAVE_ID) ?: 0L
            PickupScreen(waveId = waveId, onDone = { navController.popBackStack() })
        }

        composable(
            route = RiderRoutes.DELIVER,
            arguments = listOf(navArgument(RiderRoutes.ARG_TASK_ID) { type = NavType.LongType }),
        ) { entry ->
            val taskId = entry.arguments?.getLong(RiderRoutes.ARG_TASK_ID) ?: 0L
            DeliverScreen(taskId = taskId, onDone = { navController.popBackStack() })
        }

        composable(
            route = RiderRoutes.EXCEPTION_REPORT,
            arguments = listOf(navArgument(RiderRoutes.ARG_TASK_ID) { type = NavType.LongType }),
        ) { entry ->
            val taskId = entry.arguments?.getLong(RiderRoutes.ARG_TASK_ID) ?: 0L
            ExceptionReportScreen(taskId = taskId, onClose = { navController.popBackStack() })
        }

        composable(
            route = RiderRoutes.CAMERA_PATTERN,
            arguments = listOf(
                navArgument(RiderRoutes.ARG_TASK_ID) {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
                navArgument(RiderRoutes.ARG_PURPOSE) {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
            ),
        ) { entry ->
            val purpose = entry.arguments?.getString(RiderRoutes.ARG_PURPOSE)
            CameraCapture(
                hint = cameraHintOf(purpose),
                // 送达页与异常页各自内嵌了相机,这条独立路由留给从别处跳进来的场景:
                // 结果回传给上一屏,拍完即退,拍照页自己不持有业务状态
                onCaptured = { path ->
                    navController.previousBackStackEntry
                        ?.savedStateHandle
                        ?.set(RiderRoutes.RESULT_PHOTO_PATH, path)
                    navController.popBackStack()
                },
                onCancel = { navController.popBackStack() },
            )
        }

        composable(RiderRoutes.MAP) {
            RiderTabScaffold(navController = navController, currentRoute = RiderRoutes.MAP) { inset ->
                RiderMapRoute(
                    locationController = locationController,
                    modifier = inset,
                    onBack = { navController.popBackStack() },
                    onOpenTask = { navController.navigate(RiderRoutes.taskDetail(it)) },
                )
            }
        }

        composable(RiderRoutes.EARNING) {
            TodayStatsScreen()
        }

        composable(RiderRoutes.MESSAGE) {
            RiderTabScaffold(navController = navController, currentRoute = RiderRoutes.MESSAGE) { inset ->
                MessageCenterScreen(modifier = inset)
            }
        }

        composable(RiderRoutes.PROFILE) {
            RiderTabScaffold(navController = navController, currentRoute = RiderRoutes.PROFILE) { inset ->
                ProfileScreen(
                    modifier = inset,
                    onOpenSettings = { navController.navigate(RiderRoutes.SETTINGS) },
                    onOpenAbout = { navController.navigate(RiderRoutes.ABOUT) },
                    onOpenMessages = { navController.switchTab(RiderRoutes.MESSAGE) },
                    onOpenStats = { navController.navigate(RiderRoutes.EARNING) },
                )
            }
        }

        composable(RiderRoutes.SETTINGS) {
            SettingsScreen(
                onOpenKeepAliveGuide = { navController.navigate(RiderRoutes.KEEPALIVE_GUIDE) },
                onLoggedOut = {
                    navController.navigate(RiderRoutes.LOGIN) {
                        popUpTo(navController.graph.id) { inclusive = true }
                    }
                },
            )
        }

        composable(RiderRoutes.ABOUT) {
            AboutScreen()
        }
    }
}

/**
 * 会话事件统一消费。1003 / 1004 / 401 可能由任意接口抛出,
 * 集中在这里跳转,业务页只管展示自己的错误文案。
 */
@Composable
private fun SessionEventHandler(
    sessionEvents: SessionEvents,
    navController: NavHostController,
    onSessionEnded: () -> Unit,
) {
    LaunchedEffect(sessionEvents, navController) {
        sessionEvents.events.collect { event ->
            when (event) {
                is SessionEvent.RequireLogin, is SessionEvent.AccountSuspended -> {
                    onSessionEnded()
                    navController.navigate(RiderRoutes.LOGIN) {
                        popUpTo(navController.graph.id) { inclusive = true }
                    }
                }

                is SessionEvent.RequireLocationConsent ->
                    navController.navigate(RiderRoutes.LOCATION_CONSENT)

                // 未上班时统一回首页:上下班开关在首页,这里不越过 feature 去开服务
                is SessionEvent.RequireOnDuty -> navController.toHome()
            }
        }
    }
}

/** 派单通知点开后跳任务详情。 */
@Composable
private fun NewTaskIntentHandler(
    newTaskRequests: StateFlow<Long?>,
    navController: NavHostController,
    onHandled: () -> Unit,
) {
    LaunchedEffect(newTaskRequests, navController) {
        newTaskRequests.collect { taskId ->
            if (taskId == null) return@collect
            // 从通知冷启动时导航图还停在启动页,得等它落到业务页再跳,不能绕过登录与授权前置;
            // 等不到就放弃,免得骑手登录半天之后被一条旧通知拽走
            val ready = withTimeoutOrNull(WAIT_FOR_BUSINESS_MILLIS) {
                navController.currentBackStackEntryFlow.first { entry ->
                    entry.destination.route?.let { it !in RiderRoutes.PRE_BUSINESS_ROUTES } == true
                }
            }
            if (ready != null) {
                navController.navigate(RiderRoutes.taskDetail(taskId)) { launchSingleTop = true }
            }
            onHandled()
        }
    }
}

private const val WAIT_FOR_BUSINESS_MILLIS = 30_000L

private data class RiderTab(val route: String, val icon: FreshIconType, val label: String)

/**
 * 四个常驻入口。首页之外的页面若不给固定入口,骑手就只能靠返回键找路——
 * 设置、保活向导、关于(备案号)全挂在「我的」下面,必须始终一步可达。
 */
private val RiderTabs = listOf(
    RiderTab(RiderRoutes.HOME, FreshIconType.HOME, "首页"),
    RiderTab(RiderRoutes.MAP, FreshIconType.MAP, "路线"),
    RiderTab(RiderRoutes.MESSAGE, FreshIconType.MESSAGE, "消息"),
    RiderTab(RiderRoutes.PROFILE, FreshIconType.PROFILE, "我的"),
)

@Composable
private fun RiderTabScaffold(
    navController: NavHostController,
    currentRoute: String,
    content: @Composable (Modifier) -> Unit,
) {
    val backStackEntry by navController.currentBackStackEntryAsState()
    val activeRoute = backStackEntry?.destination?.route ?: currentRoute

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        bottomBar = {
            NavigationBar {
                RiderTabs.forEach { tab ->
                    NavigationBarItem(
                        selected = tab.route == activeRoute,
                        onClick = { if (tab.route != activeRoute) navController.switchTab(tab.route) },
                        icon = {
                            FreshIcon(
                                type = tab.icon,
                                contentDescription = "${tab.label}标签",
                            )
                        },
                        label = {
                            Text(
                                text = tab.label,
                                style = MaterialTheme.typography.labelMedium,
                            )
                        },
                    )
                }
            }
        },
    ) { padding ->
        content(Modifier.padding(padding))
    }
}

private fun cameraHintOf(purpose: String?): String = when (purpose) {
    "DELIVERY" -> "拍摄送达凭证:请拍到门牌或货品"
    "EXCEPTION" -> "拍摄现场照片:请拍到能说明问题的画面"
    else -> "请拍摄现场照片"
}

private fun NavHostController.toHome() {
    navigate(RiderRoutes.HOME) {
        popUpTo(graph.id) { inclusive = true }
        launchSingleTop = true
    }
}

/** 常驻入口之间横向切换:回到首页那一层,不在栈里堆四个页面。 */
private fun NavHostController.switchTab(route: String) {
    navigate(route) {
        popUpTo(RiderRoutes.HOME) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
