package com.yulin.rider.core.database

/**
 * 队列取队头的那条 SQL。
 *
 * 单独拎出来是为了能在 JVM 单测里直接对着真 SQLite 跑 —— 顺序性写在 SQL 里,
 * 写错的表现是「没取货就送达」这种不可逆的业务事故,不能只靠肉眼审。
 */
object PendingActionQueries {

    /**
     * 每个组只放出最早的一条,失败的队头不删除,于是自动阻塞本组后续动作,其他组照常推进。
     *
     * 分组以 taskId 为先、waveId 兜底:任务级动作入队时也会补上所属 waveId,
     * 若按 waveId 优先分组,整波十几单就串成一条链,任何一条卡住整波都动不了。
     *
     * 第二条约束是波次闸门。波次级动作(taskId 为空的整波接单 / 整波取货 / 回店)
     * 在本波次里是一道栅栏:它之前入队的同波次动作没走完它不能走,
     * 它自己没走完,之后入队的同波次任务级动作也不能走。
     * 少了这条就会出现「没取货就送达」和「还没送完就回店」。
     * 任务级动作之间不设栅栏,这才是并行的来源。
     */
    const val CLAIM_HEAD_OF_EACH_GROUP = """
        SELECT * FROM pending_action AS p
        WHERE NOT EXISTS (
            SELECT 1 FROM pending_action AS q
            WHERE (CASE WHEN q.taskId IS NOT NULL THEN 'T' || q.taskId
                        WHEN q.waveId IS NOT NULL THEN 'W' || q.waveId ELSE 'G' END)
                = (CASE WHEN p.taskId IS NOT NULL THEN 'T' || p.taskId
                        WHEN p.waveId IS NOT NULL THEN 'W' || p.waveId ELSE 'G' END)
              AND (q.createdAt < p.createdAt
                   OR (q.createdAt = p.createdAt AND q.rowid < p.rowid))
        )
        AND NOT EXISTS (
            SELECT 1 FROM pending_action AS b
            WHERE p.waveId IS NOT NULL
              AND b.waveId = p.waveId
              AND (b.taskId IS NULL OR p.taskId IS NULL)
              AND (b.createdAt < p.createdAt
                   OR (b.createdAt = p.createdAt AND b.rowid < p.rowid))
        )
        ORDER BY p.createdAt ASC, p.rowid ASC
        LIMIT :limit
    """
}
