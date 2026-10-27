package com.yulin.rider.navigation

import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.yulin.rider.core.datastore.RiderSession
import com.yulin.rider.core.datastore.RiderSettingsStore
import com.yulin.rider.core.datastore.RiderTokenStore
import com.yulin.rider.core.designsystem.FreshIcon
import com.yulin.rider.core.designsystem.FreshIconType
import com.yulin.rider.core.location.KeepAliveGuideScreen
import com.yulin.rider.core.location.LocationController
import com.yulin.rider.core.network.SessionEvent
import com.yulin.rider.core.network.SessionEvents
import com.yulin.rider.core.update.AppUpdateScreen
import com.yulin.rider.core.update.UpdateController
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
import com.yulin.rider.feature.shift.ShiftDutyBar
import com.yulin.rider.feature.shift.ShiftStatusPill
import com.yulin.rider.feature.task.ui.DeliverScreen
import com.yulin.rider.feature.task.ui.DispatchScreen
import com.yulin.rider.feature.task.ui.PickupScreen
import com.yulin.rider.feature.task.ui.TaskDetailScreen
import com.yulin.rider.feature.task.ui.TaskHomeScreen
import com.yulin.rider.feature.task.ui.WaveDetailScreen
import com.yulin.rider.feature.task.ui.camera.CameraCapture
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
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
    updateController: UpdateController,
    /** 派单通知点开后要打开的任务;由 MainActivity 从 Intent 里解出来。 */
    newTaskRequests: StateFlow<Long?>,
    onNewTaskHandled: () -> Unit,
    appUpdateRequests: StateFlow<Boolean>,
    onAppUpdateHandled: () -> Unit,
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

    LastRouteRecorder(navController = navController, settingsStore = settingsStore)

    SessionEventHandler(
        sessionEvents = sessionEvents,
        navController = navController,
        settingsStore = settingsStore,
        onSessionEnded = onSessionEnded,
    )
    NewTaskIntentHandler(
        newTaskRequests = newTaskRequests,
        navController = navController,
        onHandled = onNewTaskHandled,
    )
    AppUpdateIntentHandler(
        requests = appUpdateRequests,
        navController = navController,
        onHandled = onAppUpdateHandled,
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
                updateController = updateController,
                onResolved = { destination, resumeRoute ->
                    navController.navigate(destination.route) {
                        popUpTo(RiderRoutes.SPLASH) { inclusive = true }
                    }
                    // 先落首页再压上次那一屏，返回键才退得回首页而不是直接退出 App。
                    // 存的路由可能已经失效(任务被取消、版本更新改了路由)，跳不过去就算了，
                    // 骑手停在首页总比崩在启动阶段强。
                    if (resumeRoute != null && resumeRoute != destination.route) {
                        runCatching { navController.navigate(resumeRoute) }
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
            RiderHomeShell(
                tokenStore = tokenStore,
                onNavigate = { route -> navController.navigate(route) },
            ) { openMenu ->
                TaskHomeScreen(
                    onOpenMenu = openMenu,
                    onOpenMessages = { navController.navigate(RiderRoutes.MESSAGE) },
                    onOpenRoute = { navController.navigate(RiderRoutes.MAP) },
                    // 这两个插槽是上下班的唯一入口，不传骑手就永远收不到派单
                    statusPill = { ShiftStatusPill() },
                    shiftHeader = { onRefresh -> ShiftDutyBar(onRefresh = onRefresh) },
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
                onBack = { navController.popBackStack() },
                onOpenTask = { navController.navigate(RiderRoutes.taskDetail(it)) },
            )
        }

        composable(
            route = RiderRoutes.DISPATCH,
            arguments = listOf(navArgument(RiderRoutes.ARG_TASK_ID) { type = NavType.LongType }),
        ) { entry ->
            val taskId = entry.arguments?.getLong(RiderRoutes.ARG_TASK_ID) ?: 0L
            DispatchScreen(
                taskId = taskId,
                onDismiss = { navController.popBackStack() },
                onAccepted = { accepted ->
                    navController.popBackStack()
                    navController.navigate(RiderRoutes.taskDetail(accepted))
                },
            )
        }

        composable(
            route = RiderRoutes.TASK_DETAIL,
            arguments = listOf(navArgument(RiderRoutes.ARG_TASK_ID) { type = NavType.LongType }),
        ) { entry ->
            val taskId = entry.arguments?.getLong(RiderRoutes.ARG_TASK_ID) ?: 0L
            TaskDetailScreen(
                taskId = taskId,
                onBack = { navController.popBackStack() },
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
            PickupScreen(
                waveId = waveId,
                onBack = { navController.popBackStack() },
                onDone = { navController.popBackStack() },
            )
        }

        composable(
            route = RiderRoutes.DELIVER,
            arguments = listOf(navArgument(RiderRoutes.ARG_TASK_ID) { type = NavType.LongType }),
        ) { entry ->
            val taskId = entry.arguments?.getLong(RiderRoutes.ARG_TASK_ID) ?: 0L
            DeliverScreen(
                taskId = taskId,
                onBack = { navController.popBackStack() },
                onDone = { navController.popBackStack() },
            )
        }

        composable(
            route = RiderRoutes.EXCEPTION_REPORT,
            arguments = listOf(navArgument(RiderRoutes.ARG_TASK_ID) { type = NavType.LongType }),
        ) { entry ->
            val taskId = entry.arguments?.getLong(RiderRoutes.ARG_TASK_ID) ?: 0L
            ExceptionReportScreen(
                taskId = taskId,
                onBack = { navController.popBackStack() },
                onClose = { navController.popBackStack() },
            )
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

        // 路线、消息、我的、账户都从抽屉或顶栏进入，属于普通栈页面，各自带返回。
        composable(RiderRoutes.MAP) {
            RiderMapRoute(
                locationController = locationController,
                onBack = { navController.popBackStack() },
                onOpenTask = { navController.navigate(RiderRoutes.taskDetail(it)) },
            )
        }

        composable(RiderRoutes.EARNING) {
            TodayStatsScreen(onBack = { navController.popBackStack() })
        }

        composable(RiderRoutes.MESSAGE) {
            MessageCenterScreen(
                onBack = { navController.popBackStack() },
                onOpenAppUpdate = { navController.navigate(RiderRoutes.APP_UPDATE) },
            )
        }

        composable(RiderRoutes.PROFILE) {
            ProfileScreen(
                onBack = { navController.popBackStack() },
                onOpenSettings = { navController.navigate(RiderRoutes.SETTINGS) },
                onOpenAbout = { navController.navigate(RiderRoutes.ABOUT) },
                onOpenMessages = { navController.navigate(RiderRoutes.MESSAGE) },
                onOpenStats = { navController.navigate(RiderRoutes.EARNING) },
            )
        }

        composable(RiderRoutes.SETTINGS) {
            SettingsScreen(
                onBack = { navController.popBackStack() },
                onOpenKeepAliveGuide = { navController.navigate(RiderRoutes.KEEPALIVE_GUIDE) },
                onOpenChangePassword = { navController.navigate(RiderRoutes.CHANGE_PASSWORD) },
                onOpenAbout = { navController.navigate(RiderRoutes.ABOUT) },
                onLoggedOut = {
                    scope.launch { settingsStore.setLastRoute(null) }
                    navController.navigate(RiderRoutes.LOGIN) {
                        popUpTo(navController.graph.id) { inclusive = true }
                    }
                },
            )
        }

        composable(RiderRoutes.ABOUT) {
            AboutScreen(
                onBack = { navController.popBackStack() },
                onCheckUpdate = { navController.navigate(RiderRoutes.APP_UPDATE) },
            )
        }

        composable(RiderRoutes.APP_UPDATE) {
            AppUpdateScreen(
                controller = updateController,
                onBack = { navController.popBackStack() },
            )
        }
    }
}

/** 路由里的 {参数名} 占位符。右花括号必须转义：Android 的 ICU 正则不接受裸的 `}`。 */
private val ROUTE_ARG_PATTERN = Regex("\\{(\\w+)\\}")

/**
 * 不做记忆的页面。
 *
 * 前置流程页由启动页按会话状态自己判定，恢复它们会绕开判定；
 * 拍照页是一次性动作，重启后把骑手直接丢进取景框只会让人莫名其妙。
 */
private val NON_RESUMABLE_ROUTES = RiderRoutes.PRE_BUSINESS_ROUTES + RiderRoutes.CAMERA

/**
 * 记住骑手停在哪一屏。
 *
 * 骑手被电话、微信、导航打断是常态，进程被后台杀掉更是家常便饭。
 * 没有这个的话每次回来都要从首页重新点进那一单，一天几十次。
 */
@Composable
private fun LastRouteRecorder(
    navController: NavHostController,
    settingsStore: RiderSettingsStore,
) {
    LaunchedEffect(navController, settingsStore) {
        navController.currentBackStackEntryFlow
            .map { it.resumableRoute() }
            .distinctUntilChanged()
            .collect { route ->
                // 只在停到可恢复页面时覆盖，不可恢复时保持原值不动。
                // 冷启动第一帧一定停在启动页，如果这里顺手清空，
                // 上一次记下的页面会在启动页读到它之前就被抹掉 —— 记忆功能等于没有。
                // 会话失效时的清理走 SessionEventHandler。
                if (route != null) settingsStore.setLastRoute(route)
            }
    }
}

/** 把注册用的模式串还原成带实参的完整路由；不该记忆的页面返回 null。 */
private fun NavBackStackEntry.resumableRoute(): String? {
    val pattern = destination.route ?: return null
    if (NON_RESUMABLE_ROUTES.any { pattern == it || pattern.startsWith("$it?") }) return null
    if (!pattern.contains('{')) return pattern
    val args = arguments ?: return null
    var concrete = pattern
    ROUTE_ARG_PATTERN.findAll(pattern).forEach { match ->
        val value = args.get(match.groupValues[1])?.toString() ?: return null
        concrete = concrete.replace(match.value, value)
    }
    return concrete
}

/**
 * 会话事件统一消费。1003 / 1004 / 401 可能由任意接口抛出,
 * 集中在这里跳转,业务页只管展示自己的错误文案。
 */
@Composable
private fun SessionEventHandler(
    sessionEvents: SessionEvents,
    navController: NavHostController,
    settingsStore: RiderSettingsStore,
    onSessionEnded: () -> Unit,
) {
    LaunchedEffect(sessionEvents, navController) {
        sessionEvents.events.collect { event ->
            when (event) {
                is SessionEvent.RequireLogin, is SessionEvent.AccountSuspended -> {
                    onSessionEnded()
                    // 换人或掉线后不能再恢复上一个人的页面
                    settingsStore.setLastRoute(null)
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

/** 派单通知点开后跳派单页，接单后才落详情。 */
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
                navController.navigate(RiderRoutes.dispatch(taskId)) { launchSingleTop = true }
            }
            onHandled()
        }
    }
}

@Composable
private fun AppUpdateIntentHandler(
    requests: StateFlow<Boolean>,
    navController: NavHostController,
    onHandled: () -> Unit,
) {
    LaunchedEffect(requests, navController) {
        requests.collect { requested ->
            if (!requested) return@collect
            val ready = withTimeoutOrNull(WAIT_FOR_BUSINESS_MILLIS) {
                navController.currentBackStackEntryFlow.first { entry ->
                    entry.destination.route?.let { it !in RiderRoutes.PRE_BUSINESS_ROUTES } == true
                }
            }
            if (ready != null) {
                navController.navigate(RiderRoutes.APP_UPDATE) { launchSingleTop = true }
            }
            onHandled()
        }
    }
}

private const val WAIT_FOR_BUSINESS_MILLIS = 30_000L

/**
 * 首页外壳。
 *
 * 主界面把整屏留给任务列表，其余入口收进左侧抽屉，与美团骑手端一致。
 * [content] 拿到的是「打开抽屉」回调，由首页顶栏的菜单按钮触发。
 */
@Composable
private fun RiderHomeShell(
    tokenStore: RiderTokenStore,
    onNavigate: (String) -> Unit,
    content: @Composable (openMenu: () -> Unit) -> Unit,
) {
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val session by produceState<RiderSession?>(initialValue = null, tokenStore) {
        value = tokenStore.current()
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            RiderDrawerSheet(
                riderName = session?.riderName?.takeIf { it.isNotBlank() } ?: "骑手",
                riderNo = session?.riderPhone?.takeIf { it.isNotBlank() },
                onOpenProfile = {
                    scope.launch { drawerState.close() }
                    onNavigate(RiderRoutes.PROFILE)
                },
                onNavigate = { route ->
                    scope.launch { drawerState.close() }
                    onNavigate(route)
                },
            )
        },
    ) {
        content { scope.launch { drawerState.open() } }
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
