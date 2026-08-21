package com.yulin.rider.core.location

/**
 * 各品牌保活设置项清单。没有截图资源,全部用清晰的文字步骤代替。
 *
 * 所有厂商深链都只是 best-effort:类名随 ROM 大版本变动是常态,
 * 因此每项都列了多个历史候选,并统一由 [KeepAliveIntents] 兜底到应用详情页。
 */
object KeepAliveCatalog {

    fun sectionsFor(vendor: KeepAliveVendor): List<KeepAliveSection> = listOf(
        KeepAliveSection("第一步 · 系统权限(可自动检测)", systemSteps()),
        KeepAliveSection("第二步 · ${vendor.displayName} 专项设置", vendorSteps(vendor)),
        KeepAliveSection("第三步 · 日常使用习惯", habitSteps(vendor)),
    )

    fun allSteps(vendor: KeepAliveVendor): List<KeepAliveStep> =
        sectionsFor(vendor).flatMap { it.steps }

    // ---------------------------------------------------------------- 系统层

    private fun systemSteps(): List<KeepAliveStep> = listOf(
        KeepAliveStep(
            id = "fine_location",
            title = "精确定位权限",
            summary = "没有它完全无法配送:上班、接单、送达都要定位。",
            instructions = listOf(
                "点「去设置」进入应用信息页",
                "选择「权限」→「位置信息」",
                "选择「使用应用时允许」或「始终允许」,并打开「使用精确位置」",
            ),
            action = KeepAliveAction.AppDetails,
            autoDetect = KeepAliveAutoDetect.FINE_LOCATION,
        ),
        KeepAliveStep(
            id = "background_location",
            title = "后台定位「始终允许」",
            summary = "只给「仅使用时允许」的话,锁屏或切到微信几分钟后位置就停了,调度台会把你标成掉线。",
            instructions = listOf(
                "点「去设置」进入应用信息页",
                "选择「权限」→「位置信息」",
                "选择「始终允许」(部分手机写作「一直允许」「后台允许」)",
                "找不到该选项时,先选「使用应用时允许」,再回到本页点「重新检测」,系统会二次询问",
            ),
            action = KeepAliveAction.AppDetails,
            autoDetect = KeepAliveAutoDetect.BACKGROUND_LOCATION,
        ),
        KeepAliveStep(
            id = "notification",
            title = "通知权限",
            summary = "关闭通知就收不到新单提醒,也看不到配送中的常驻通知。",
            instructions = listOf(
                "点「去设置」进入通知设置页",
                "打开「允许通知」总开关",
                "确保「新派单」「紧急提醒」「配送服务」三个分类都是开启状态",
                "把「新派单」设为「重要通知 / 横幅提醒」,并允许响铃",
            ),
            action = KeepAliveAction.NotificationSettings,
            autoDetect = KeepAliveAutoDetect.NOTIFICATION,
        ),
        KeepAliveStep(
            id = "battery_optimization",
            title = "电池优化白名单",
            summary = "系统的省电策略会在锁屏一段时间后冻结应用,定位随之中断。",
            instructions = listOf(
                "点「去设置」进入「电池优化 / 忽略电池优化」列表",
                "把筛选条件切到「所有应用」",
                "找到「禹邻优鲜骑手」,选择「不优化 / 允许」",
                "返回本页点「重新检测」,状态会自动变成已完成",
            ),
            action = KeepAliveAction.BatteryOptimization,
            autoDetect = KeepAliveAutoDetect.BATTERY_OPTIMIZATION,
        ),
    )

    // ---------------------------------------------------------------- 厂商层

    private fun vendorSteps(vendor: KeepAliveVendor): List<KeepAliveStep> = when (vendor) {
        KeepAliveVendor.XIAOMI -> xiaomi()
        KeepAliveVendor.HUAWEI -> huawei()
        KeepAliveVendor.HONOR -> honor()
        KeepAliveVendor.OPPO -> oppo()
        KeepAliveVendor.VIVO -> vivo()
        KeepAliveVendor.SAMSUNG -> samsung()
        KeepAliveVendor.MEIZU -> meizu()
        KeepAliveVendor.NUBIA -> nubia()
        KeepAliveVendor.ZTE -> zte()
        KeepAliveVendor.TRANSSION -> transsion()
        KeepAliveVendor.LENOVO -> lenovo()
        KeepAliveVendor.GENERIC -> generic()
    }

    private fun xiaomi() = listOf(
        KeepAliveStep(
            id = "xiaomi_autostart",
            title = "自启动",
            summary = "MIUI / 澎湃系统默认禁止自启动,App 被清理后无法自行恢复定位。",
            instructions = listOf(
                "点「去设置」进入「手机管家 → 应用管理 → 权限 → 自启动」",
                "在列表里找到「禹邻优鲜骑手」并打开开关",
                "若提示风险,选择「允许」",
            ),
            action = KeepAliveAction.Vendor(
                listOf(
                    ComponentTarget("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"),
                    ComponentTarget("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartDetailActivity"),
                )
            ),
        ),
        KeepAliveStep(
            id = "xiaomi_battery",
            title = "省电策略 → 无限制",
            summary = "MIUI 的「智能省电」会在息屏后限制后台联网,位置上报会整段丢失。",
            instructions = listOf(
                "点「去设置」进入「应用智能省电 / 省电策略」",
                "找到「禹邻优鲜骑手」,选择「无限制」",
                "同时关闭「休眠时始终保持网络连接」的反向限制(保持网络开启)",
            ),
            action = KeepAliveAction.Vendor(
                listOf(
                    ComponentTarget(
                        "com.miui.powerkeeper",
                        "com.miui.powerkeeper.ui.HiddenAppsConfigActivity",
                        mapOf("package_name" to "{pkg}", "package_label" to "{label}"),
                    ),
                    ComponentTarget("com.miui.securitycenter", "com.miui.powercenter.PowerSettings"),
                )
            ),
        ),
        KeepAliveStep(
            id = "xiaomi_background_popup",
            title = "后台弹出界面",
            summary = "关闭这一项,新单的全屏提醒页拉不起来,锁屏时会直接错过订单。",
            instructions = listOf(
                "点「去设置」进入本应用的权限管理页",
                "找到「后台弹出界面」并设为「允许」",
                "顺便把「锁屏显示」「显示悬浮窗」也设为允许",
            ),
            action = KeepAliveAction.Vendor(
                listOf(
                    ComponentTarget(
                        "com.miui.securitycenter",
                        "com.miui.permcenter.permissions.PermissionsEditorActivity",
                        mapOf("extra_pkgname" to "{pkg}"),
                    ),
                    ComponentTarget(
                        "com.miui.securitycenter",
                        "com.miui.permcenter.permissions.AppPermissionsEditorActivity",
                        mapOf("extra_pkgname" to "{pkg}"),
                    ),
                )
            ),
        ),
    )

    private fun huawei() = listOf(
        KeepAliveStep(
            id = "huawei_startup",
            title = "应用启动管理 → 手动管理(三项全开)",
            summary = "华为默认自动管理,会在息屏后直接关闭应用,这是华为机型掉线的头号原因。",
            instructions = listOf(
                "点「去设置」进入「手机管家 → 应用启动管理」",
                "找到「禹邻优鲜骑手」,关闭「自动管理」",
                "在弹出的对话框里把「允许自启动」「允许关联启动」「允许后台活动」三项全部打开",
                "点击「确定」保存",
            ),
            action = KeepAliveAction.Vendor(
                listOf(
                    ComponentTarget("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
                    ComponentTarget("com.huawei.systemmanager", "com.huawei.systemmanager.appcontrol.activity.StartupAppControlActivity"),
                    ComponentTarget("com.huawei.systemmanager", "com.huawei.systemmanager.optimize.process.ProtectActivity"),
                )
            ),
        ),
        KeepAliveStep(
            id = "huawei_lock_screen_cleanup",
            title = "关闭「锁屏清理应用」",
            summary = "开着这一项,一锁屏应用就被清掉,前台服务也保不住。",
            instructions = listOf(
                "打开「设置 → 应用 → 应用启动管理」右上角菜单",
                "或进入「手机管家 → 右下角设置 → 锁屏清理应用」",
                "把「禹邻优鲜骑手」从清理列表中移除(开关设为关闭)",
            ),
            action = KeepAliveAction.ManualOnly,
        ),
    )

    private fun honor() = listOf(
        KeepAliveStep(
            id = "honor_startup",
            title = "应用启动管理 → 手动管理(三项全开)",
            summary = "荣耀沿用了华为的启动管理策略,自动管理下息屏即被关闭。",
            instructions = listOf(
                "点「去设置」进入「手机管家 → 应用启动管理」",
                "找到「禹邻优鲜骑手」,关闭「自动管理」",
                "把「允许自启动」「允许关联启动」「允许后台活动」三项全部打开",
            ),
            action = KeepAliveAction.Vendor(
                listOf(
                    ComponentTarget("com.hihonor.systemmanager", "com.hihonor.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
                    ComponentTarget("com.hihonor.systemmanager", "com.hihonor.systemmanager.appcontrol.activity.StartupAppControlActivity"),
                    ComponentTarget("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
                )
            ),
        ),
        KeepAliveStep(
            id = "honor_power_genie",
            title = "关闭「智能省电 / 超级省电」",
            summary = "省电模式下后台应用会被批量冻结,定位与推送同时失效。",
            instructions = listOf(
                "进入「设置 → 电池」",
                "关闭「智能省电模式」与「超级省电」",
                "在「更多电池设置」里关闭「休眠时始终保持网络连接」的限制(保持连接为开启)",
            ),
            action = KeepAliveAction.ManualOnly,
        ),
    )

    private fun oppo() = listOf(
        KeepAliveStep(
            id = "oppo_autostart",
            title = "允许自启动",
            summary = "ColorOS / realme UI / 氢氧 OS 默认禁止自启动,应用被清理后无法恢复。",
            instructions = listOf(
                "点「去设置」进入「手机管家 → 权限隐私 → 自启动管理」",
                "找到「禹邻优鲜骑手」并打开开关",
                "同时打开「允许被其他应用启动」",
            ),
            action = KeepAliveAction.Vendor(
                listOf(
                    ComponentTarget("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"),
                    ComponentTarget("com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppListActivity"),
                    ComponentTarget("com.oppo.safe", "com.oppo.safe.permission.startup.StartupAppListActivity"),
                )
            ),
        ),
        KeepAliveStep(
            id = "oppo_power",
            title = "耗电保护 → 允许后台运行",
            summary = "耗电管理默认「智能限制后台」,骑行途中会静默冻结应用。",
            instructions = listOf(
                "点「去设置」进入「电池 → 耗电保护 / 应用耗电管理」",
                "找到「禹邻优鲜骑手」,关闭「智能省电」,选择「允许后台运行」",
                "同时关闭「深度睡眠 / 应用速冻」对本应用的限制",
            ),
            action = KeepAliveAction.Vendor(
                listOf(
                    ComponentTarget("com.coloros.oppoguardelf", "com.coloros.powermanager.fuelgaue.PowerUsageModelActivity"),
                    ComponentTarget("com.coloros.oppoguardelf", "com.coloros.powermanager.fuelgaue.PowerConsumptionActivity"),
                    ComponentTarget("com.coloros.safecenter", "com.coloros.privacypermissionsentry.PermissionTopActivity"),
                )
            ),
        ),
        KeepAliveStep(
            id = "oppo_lock_recents",
            title = "在最近任务里锁定应用",
            summary = "不锁定的话,一键清理会把配送服务一起清掉。",
            instructions = listOf(
                "点击手机的「最近任务」键",
                "找到「禹邻优鲜骑手」的卡片,下拉卡片(部分机型为长按)",
                "点击出现的锁形图标,卡片上出现小锁即成功",
            ),
            action = KeepAliveAction.ManualOnly,
        ),
    )

    private fun vivo() = listOf(
        KeepAliveStep(
            id = "vivo_high_power",
            title = "后台高耗电 → 允许",
            summary = "vivo 会在应用后台耗电偏高时直接杀掉,持续定位必然触发这条规则。",
            instructions = listOf(
                "点「去设置」进入「i 管家 → 应用管理 → 权限管理 → 后台高耗电」",
                "找到「禹邻优鲜骑手」并打开开关",
            ),
            action = KeepAliveAction.Vendor(
                listOf(
                    ComponentTarget("com.vivo.abe", "com.vivo.applicationbehaviorengine.ui.ExcessivePowerManagerActivity"),
                    ComponentTarget("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager"),
                )
            ),
        ),
        KeepAliveStep(
            id = "vivo_autostart",
            title = "自启动 + 关联启动",
            summary = "关闭自启动后,应用被清理就再也起不来。",
            instructions = listOf(
                "点「去设置」进入「i 管家 → 应用管理 → 自启动管理」",
                "找到「禹邻优鲜骑手」并打开开关",
                "在「权限管理 → 自启动」里同时允许「关联启动」",
            ),
            action = KeepAliveAction.Vendor(
                listOf(
                    ComponentTarget("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"),
                    ComponentTarget("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity"),
                    ComponentTarget("com.iqoo.secure", "com.iqoo.secure.MainActivity"),
                )
            ),
        ),
        KeepAliveStep(
            id = "vivo_lock_recents",
            title = "在最近任务里锁定应用",
            summary = "vivo 的一键加速会清掉未锁定的全部后台应用。",
            instructions = listOf(
                "点击手机的「最近任务」键",
                "长按「禹邻优鲜骑手」的卡片,或下拉卡片",
                "点击锁形图标完成锁定",
            ),
            action = KeepAliveAction.ManualOnly,
        ),
    )

    private fun samsung() = listOf(
        KeepAliveStep(
            id = "samsung_unmonitored",
            title = "未监视的应用 → 添加本应用",
            summary = "三星的「深度睡眠应用」会把长时间后台运行的应用直接休眠。",
            instructions = listOf(
                "点「去设置」进入「电池和设备维护 → 电池」",
                "进入「后台使用限制」→「永不休眠的应用」",
                "点「+」把「禹邻优鲜骑手」加进去",
                "同时确认它不在「休眠应用」与「深度休眠应用」列表里",
            ),
            action = KeepAliveAction.Vendor(
                listOf(
                    ComponentTarget("com.samsung.android.lool", "com.samsung.android.sm.ui.battery.BatteryActivity"),
                    ComponentTarget("com.samsung.android.lool", "com.samsung.android.sm.battery.ui.BatteryActivity"),
                    ComponentTarget("com.samsung.android.sm_cn", "com.samsung.android.sm.ui.battery.BatteryActivity"),
                    ComponentTarget("com.samsung.android.sm", "com.samsung.android.sm.ui.battery.BatteryActivity"),
                )
            ),
        ),
        KeepAliveStep(
            id = "samsung_adaptive_battery",
            title = "关闭自适应电池",
            summary = "自适应电池会根据使用习惯限制后台,新装的应用最容易被限。",
            instructions = listOf(
                "进入「设置 → 电池和设备维护 → 电池 → 更多电池设置」",
                "关闭「自适应电池」",
                "关闭「自动优化设置」中的每日自动重启与自动关闭后台应用",
            ),
            action = KeepAliveAction.ManualOnly,
        ),
    )

    private fun meizu() = listOf(
        KeepAliveStep(
            id = "meizu_background",
            title = "后台管理 → 允许后台运行",
            summary = "Flyme 默认按「智能后台」管理,长时间定位会被判定为异常耗电。",
            instructions = listOf(
                "点「去设置」进入「手机管家 → 权限管理 → 后台管理」",
                "找到「禹邻优鲜骑手」,选择「允许后台运行」",
                "在「省电管理」里把本应用设为「不优化」",
            ),
            action = KeepAliveAction.Vendor(
                listOf(
                    ComponentTarget("com.meizu.safe", "com.meizu.safe.permission.SmartBGActivity"),
                    ComponentTarget("com.meizu.safe", "com.meizu.safe.permission.PermissionMainActivity"),
                    ComponentTarget("com.meizu.safe", "com.meizu.safe.SecurityMainActivity"),
                )
            ),
        ),
        KeepAliveStep(
            id = "meizu_autostart",
            title = "自启动管理",
            summary = "关闭自启动后应用被清理无法自行恢复。",
            instructions = listOf(
                "进入「手机管家 → 权限管理 → 自启动管理」",
                "找到「禹邻优鲜骑手」并打开开关",
            ),
            action = KeepAliveAction.Vendor(
                listOf(
                    ComponentTarget("com.meizu.safe", "com.meizu.safe.permission.PermissionMainActivity"),
                )
            ),
        ),
    )

    private fun nubia() = listOf(
        KeepAliveStep(
            id = "nubia_selfstart",
            title = "自启动管理",
            summary = "努比亚安全中心默认禁止第三方应用自启动。",
            instructions = listOf(
                "点「去设置」进入「安全中心 → 自启动管理」",
                "找到「禹邻优鲜骑手」并打开开关",
            ),
            action = KeepAliveAction.Vendor(
                listOf(
                    ComponentTarget("cn.nubia.security2", "cn.nubia.security.appmanage.selfstart.ui.SelfStartActivity"),
                    ComponentTarget("cn.nubia.security", "cn.nubia.security.appmanage.selfstart.ui.SelfStartActivity"),
                )
            ),
        ),
        KeepAliveStep(
            id = "nubia_power",
            title = "省电策略 → 不限制",
            summary = "省电模式会在息屏后切断后台联网,位置点只能堆在本地。",
            instructions = listOf(
                "进入「设置 → 电池 → 省电管理」",
                "把「禹邻优鲜骑手」设为「不限制 / 允许后台运行」",
                "关闭「超级省电」与「智能省电」",
            ),
            action = KeepAliveAction.ManualOnly,
        ),
    )

    private fun zte() = listOf(
        KeepAliveStep(
            id = "zte_autorun",
            title = "自启动管理",
            summary = "中兴安全管家默认拦截第三方自启动。",
            instructions = listOf(
                "点「去设置」进入「安全管家 → 自启动管理 / 权限管理」",
                "找到「禹邻优鲜骑手」并允许自启动",
            ),
            action = KeepAliveAction.Vendor(
                listOf(
                    ComponentTarget("com.zte.heartyservice", "com.zte.heartyservice.autorun.AppAutoRunManager"),
                    ComponentTarget("com.zte.heartyservice", "com.zte.heartyservice.setting.ClearAppSettingsActivity"),
                )
            ),
        ),
        KeepAliveStep(
            id = "zte_clean_whitelist",
            title = "一键清理白名单",
            summary = "不加白名单,一键清理会把配送服务一并清掉。",
            instructions = listOf(
                "进入「安全管家 → 一键清理 → 设置 / 忽略列表」",
                "把「禹邻优鲜骑手」加入忽略列表",
            ),
            action = KeepAliveAction.Vendor(
                listOf(
                    ComponentTarget("com.zte.heartyservice", "com.zte.heartyservice.setting.ClearAppSettingsActivity"),
                )
            ),
        ),
    )

    private fun transsion() = listOf(
        KeepAliveStep(
            id = "transsion_autostart",
            title = "自启动管理",
            summary = "TECNO / Infinix / itel 的手机管家默认限制后台自启动。",
            instructions = listOf(
                "点「去设置」进入「手机管家 / Phone Master → 自启动管理(Auto-start)」",
                "找到「禹邻优鲜骑手」并打开开关",
            ),
            action = KeepAliveAction.Vendor(
                listOf(
                    ComponentTarget("com.transsion.phonemaster", "com.cyin.himgr.autostart.AutoStartActivity"),
                    ComponentTarget("com.transsion.phonemanager", "com.cyin.himgr.autostart.AutoStartActivity"),
                )
            ),
        ),
        KeepAliveStep(
            id = "transsion_power",
            title = "省电白名单",
            summary = "省电助手会在息屏后限制后台应用联网。",
            instructions = listOf(
                "进入「手机管家 → 省电 / Power Saving」",
                "把「禹邻优鲜骑手」加入白名单或设为「不受限制」",
                "在系统「设置 → 电池」中同样关闭对本应用的后台限制",
            ),
            action = KeepAliveAction.BatteryOptimization,
        ),
    )

    private fun lenovo() = listOf(
        KeepAliveStep(
            id = "lenovo_pure_background",
            title = "后台管理 → 允许后台运行",
            summary = "联想安全中心的「纯净后台」会关闭长期驻留的应用。",
            instructions = listOf(
                "点「去设置」进入「安全中心 → 后台管理 / 纯净后台」",
                "把「禹邻优鲜骑手」设为「允许后台运行」",
            ),
            action = KeepAliveAction.Vendor(
                listOf(
                    ComponentTarget("com.lenovo.security", "com.lenovo.security.purebackground.PureBackgroundActivity"),
                    ComponentTarget("com.lenovo.safecenter", "com.lenovo.safecenter.MainTab.LeSafeMainActivity"),
                )
            ),
        ),
        KeepAliveStep(
            id = "lenovo_battery",
            title = "电池优化 → 不优化",
            summary = "摩托罗拉基本是原生策略,关掉电池优化即可。",
            instructions = listOf(
                "点「去设置」进入电池优化列表",
                "把筛选切到「所有应用」,选中「禹邻优鲜骑手」→「不优化」",
            ),
            action = KeepAliveAction.BatteryOptimization,
        ),
    )

    private fun generic() = listOf(
        KeepAliveStep(
            id = "generic_background_restriction",
            title = "取消后台限制",
            summary = "原生 Android 的「受限制」状态会阻止应用使用后台网络。",
            instructions = listOf(
                "点「去设置」进入应用信息页",
                "进入「电池 / 流量使用」,把后台限制改为「不受限制」",
                "确认「后台数据」与「无限制的数据使用」都是开启状态",
            ),
            action = KeepAliveAction.AppDetails,
        ),
        KeepAliveStep(
            id = "generic_vendor_manager",
            title = "手机自带的管家类应用",
            summary = "定制系统通常还有一层自己的后台管理,系统设置里看不到。",
            instructions = listOf(
                "打开手机自带的「手机管家 / 安全中心 / 优化大师」",
                "找到「自启动管理」「后台管理」「省电管理」三类设置",
                "把「禹邻优鲜骑手」全部设为允许 / 不限制",
                "在「一键清理」的忽略列表里也加上本应用",
            ),
            action = KeepAliveAction.ManualOnly,
        ),
    )

    // ---------------------------------------------------------------- 习惯层

    private fun habitSteps(vendor: KeepAliveVendor): List<KeepAliveStep> = buildList {
        if (vendor != KeepAliveVendor.OPPO && vendor != KeepAliveVendor.VIVO) {
            add(
                KeepAliveStep(
                    id = "habit_lock_recents",
                    title = "在最近任务里锁定应用",
                    summary = "一键清理会清掉所有未锁定的后台应用,配送服务也不例外。",
                    instructions = listOf(
                        "点击手机的「最近任务」键",
                        "找到「禹邻优鲜骑手」卡片,下拉或长按",
                        "点击锁形图标,卡片上出现小锁即成功",
                    ),
                    action = KeepAliveAction.ManualOnly,
                )
            )
        }
        add(
            KeepAliveStep(
                id = "habit_no_power_save",
                title = "配送期间不要开省电模式",
                summary = "任何厂商的省电模式都会限制后台定位与网络,这是最常见的掉线原因。",
                instructions = listOf(
                    "下拉通知栏,确认「省电模式 / 超级省电」处于关闭状态",
                    "电量低于 20% 时系统可能自动开启,请及时充电或手动关闭",
                ),
                action = KeepAliveAction.ManualOnly,
            )
        )
        add(
            KeepAliveStep(
                id = "habit_gps_on",
                title = "保持系统定位总开关打开",
                summary = "系统定位总开关关闭时,任何应用都拿不到位置。",
                instructions = listOf(
                    "点「去设置」进入系统定位设置页",
                    "打开「使用位置信息」总开关",
                    "定位模式选择「高精确度 / GPS + 网络」",
                ),
                action = KeepAliveAction.LocationSourceSettings,
            )
        )
        add(
            KeepAliveStep(
                id = "habit_wifi_scan",
                title = "允许扫描 WiFi 与蓝牙以提升定位精度",
                summary = "楼宇内 GPS 信号弱,WiFi 扫描是室内定位的主要依据。",
                instructions = listOf(
                    "进入「设置 → 位置信息 → 定位服务 / WLAN 扫描」",
                    "打开「WLAN 扫描」与「蓝牙扫描」",
                ),
                action = KeepAliveAction.LocationSourceSettings,
                required = false,
            )
        )
    }
}
