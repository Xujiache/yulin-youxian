<template>
  <div v-loading="loading" class="task-timeline">
    <ElEmpty v-if="!loading && events.length === 0" description="暂无事件记录" />
    <ElTimeline v-else>
      <ElTimelineItem
        v-for="event in events"
        :key="event.id"
        :timestamp="fullTimeText(event.createdAt)"
        :type="dotType(event.eventType)"
        placement="top"
      >
        <div class="task-timeline__head">
          <strong>{{ eventTypeText(event.eventType) }}</strong>
          <ElTag v-if="event.fromStatus || event.toStatus" size="small" effect="plain">
            {{ taskStatusText(event.fromStatus) }} → {{ taskStatusText(event.toStatus) }}
          </ElTag>
          <span>{{ operatorTypeText(event.operatorType) }} {{ event.operatorName || '' }}</span>
        </div>
        <p v-if="event.reason" class="task-timeline__reason">{{ event.reason }}</p>
        <p
          v-if="event.clientEventAt && event.clientEventAt !== event.createdAt"
          class="task-timeline__muted"
        >
          客户端时间 {{ fullTimeText(event.clientEventAt) }}（离线补传）
        </p>
      </ElTimelineItem>
    </ElTimeline>
  </div>
</template>

<script setup lang="ts">
  import type { DeliveryTaskEvent } from '@/api/delivery'
  import { eventTypeText, fullTimeText, operatorTypeText, taskStatusText } from '../utils'

  defineOptions({ name: 'TaskTimeline' })

  withDefaults(
    defineProps<{
      events: DeliveryTaskEvent[]
      loading?: boolean
    }>(),
    { loading: false }
  )

  type DotType = 'primary' | 'success' | 'warning' | 'danger' | 'info'

  const DOT_TYPES: Record<string, DotType> = {
    STATUS_CHANGE: 'primary',
    ASSIGN: 'success',
    REASSIGN: 'warning',
    EXCEPTION: 'danger',
    ETA_UPDATE: 'info',
    NOTE: 'info'
  }

  const dotType = (eventType: string): DotType => DOT_TYPES[eventType] || 'info'
</script>

<style scoped lang="scss">
  .task-timeline {
    min-height: 120px;
    max-height: 52vh;
    overflow-y: auto;
    padding-right: 6px;
  }

  .task-timeline__head {
    display: flex;
    flex-wrap: wrap;
    align-items: center;
    gap: 8px;

    strong {
      color: var(--art-gray-900);
    }

    span {
      color: var(--art-gray-600);
      font-size: 12px;
    }
  }

  .task-timeline__reason {
    margin: 6px 0 0;
    color: var(--art-gray-800);
    font-size: 13px;
  }

  .task-timeline__muted {
    margin: 4px 0 0;
    color: var(--art-gray-600);
    font-size: 12px;
  }
</style>
