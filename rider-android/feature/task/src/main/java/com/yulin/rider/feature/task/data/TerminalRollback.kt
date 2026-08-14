package com.yulin.rider.feature.task.data

import com.yulin.rider.core.database.PendingActionEntity

/**
 * 终态回滚要连坐哪些动作、哪些快照还能写回去。
 *
 * 抽成纯函数不是为了好看,是为了能在 JVM 单测里钉住:算错的表现是任务状态被一份过期快照
 * 改成服务端从来没有过的样子,骑手和调度台看到的不是一回事,而且从界面上看不出来。
 */
internal object TerminalRollback {

    /**
     * 被服务端终态拒绝后必须一起丢掉的动作。
     *
     * [sameTaskActions] 只覆盖任务级动作 —— 波次级动作(整波接单 / 整波取货 / 回店)的
     * taskId 恒为 null,按任务查永远查不到。整波接单被拒之后残留的整波取货会在下一轮重放,
     * 再用它自己入队时的旧快照把任务恢复成「已接单」,而服务端根本没接过这一波。
     * 所以同波次里晚于失败那条入队的波次级动作也要一起丢:它们的前提已经不成立了。
     */
    fun doomed(
        failed: PendingActionEntity,
        sameTaskActions: List<PendingActionEntity>,
        waveLevelActions: List<PendingActionEntity>,
    ): List<PendingActionEntity> {
        val cascaded = waveLevelActions.filter {
            it.clientEventId != failed.clientEventId && it.createdAt > failed.createdAt
        }
        return (listOf(failed) + sameTaskActions + cascaded).distinctBy { it.clientEventId }
    }

    /**
     * 快照能不能写回缓存。
     *
     * 只有当前状态还等于这条动作当初乐观写下的目标值时,才说明这一格确实是它改的。
     * 对不上就意味着中间还有别的动作动过这一单(或者服务端已经给出了真值),
     * 这时候拿旧快照覆盖等于把状态改错,只能把 localDirty 摘掉让下一次刷新对齐。
     */
    fun canRestore(
        actionType: String,
        snapshot: OptimisticTaskSnapshot,
        currentStatus: String?,
    ): Boolean = currentStatus != null &&
        currentStatus == optimisticStatusAfter(actionType, snapshot.status)
}
