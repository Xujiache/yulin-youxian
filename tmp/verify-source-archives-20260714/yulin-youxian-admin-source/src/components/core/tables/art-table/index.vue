



<template>
  <div class="art-table" :class="{ 'is-empty': isEmpty }" :style="containerHeight">
    <ElTable
      ref="elTableRef"
      v-loading="!!loading"
      v-bind="{ ...$attrs, ...props, height, stripe, border, size, headerCellStyle }"
    >
      <template v-for="col in columns" :key="col.prop || col.type">
        
        <ElTableColumn v-if="col.type === 'globalIndex'" v-bind="{ ...col }">
          <template #default="{ $index }">
            <span>{{ getGlobalIndex($index) }}</span>
          </template>
        </ElTableColumn>

        
        <ElTableColumn v-else-if="col.type === 'expand'" v-bind="cleanColumnProps(col)">
          <template #default="{ row }">
            <component :is="col.formatter ? col.formatter(row) : null" />
          </template>
        </ElTableColumn>

        
        <ElTableColumn v-else v-bind="cleanColumnProps(col)">
          <template v-if="col.useHeaderSlot && col.prop" #header="headerScope">
            <slot
              :name="col.headerSlotName || `${col.prop}-header`"
              v-bind="{ ...headerScope, prop: col.prop, label: col.label }"
            >
              {{ col.label }}
            </slot>
          </template>
          <template v-if="col.useSlot && col.prop" #default="slotScope">
            <slot
              :name="col.slotName || col.prop"
              v-bind="{
                ...slotScope,
                prop: col.prop,
                value: col.prop ? slotScope.row[col.prop] : undefined
              }"
            />
          </template>
        </ElTableColumn>
      </template>

      <template v-if="$slots.default" #default><slot /></template>

      <template #empty>
        <div v-if="loading"></div>
        <ElEmpty v-else :description="emptyText" :image-size="120" />
      </template>
    </ElTable>

    <div
      class="pagination custom-pagination"
      v-if="showPagination"
      :class="mergedPaginationOptions?.align"
      ref="paginationRef"
    >
      <ElPagination
        v-bind="mergedPaginationOptions"
        :total="pagination?.total"
        :disabled="loading"
        :page-size="pagination?.size"
        :current-page="pagination?.current"
        @size-change="handleSizeChange"
        @current-change="handleCurrentChange"
      />
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, nextTick, watchEffect } from 'vue';
import type { ElTable, TableProps } from 'element-plus';
import { storeToRefs } from 'pinia';
import { ColumnOption } from '@/types';
import { useTableStore } from '@/store/modules/table';
import { useCommon } from '@/hooks/core/useCommon';
import { useTableHeight } from '@/hooks/core/useTableHeight';
import { useResizeObserver, useWindowSize } from '@vueuse/core';
defineOptions({ name: 'ArtTable' });
const { width } = useWindowSize();
const elTableRef = ref<InstanceType<typeof ElTable> | null>(null);
const paginationRef = ref<HTMLElement>();
const tableHeaderRef = ref<HTMLElement>();
const tableStore = useTableStore();
const { isBorder, isZebra, tableSize, isFullScreen, isHeaderBackground } = storeToRefs(tableStore);
interface PaginationConfig {
    current: number;
    size: number;
    total: number;
}
interface PaginationOptions {
    pageSizes?: number[];
    align?: 'left' | 'center' | 'right';
    layout?: string;
    background?: boolean;
    hideOnSinglePage?: boolean;
    size?: 'small' | 'default' | 'large';
    pagerCount?: number;
}
interface ArtTableProps extends TableProps<Record<string, any>> {
    loading?: boolean;
    columns?: ColumnOption[];
    pagination?: PaginationConfig;
    paginationOptions?: PaginationOptions;
    emptyHeight?: string;
    emptyText?: string;
    showTableHeader?: boolean;
}
const props = withDefaults(defineProps<ArtTableProps>(), {
    columns: () => [],
    fit: true,
    showHeader: true,
    stripe: undefined,
    border: undefined,
    size: undefined,
    emptyHeight: '100%',
    emptyText: '暂无数据',
    showTableHeader: true
});
const LAYOUT = {
    MOBILE: 'prev, pager, next, sizes, jumper, total',
    IPAD: 'prev, pager, next, jumper, total',
    DESKTOP: 'total, prev, pager, next, sizes, jumper'
};
const layout = computed(() => {
    if (width.value < 768) {
        return LAYOUT.MOBILE;
    }
    else if (width.value < 1024) {
        return LAYOUT.IPAD;
    }
    else {
        return LAYOUT.DESKTOP;
    }
});
const DEFAULT_PAGINATION_OPTIONS: PaginationOptions = {
    pageSizes: [10, 20, 30, 50, 100],
    align: 'center',
    background: true,
    layout: layout.value,
    hideOnSinglePage: false,
    size: 'default',
    pagerCount: width.value > 1200 ? 7 : 5
};
const mergedPaginationOptions = computed(() => ({
    ...DEFAULT_PAGINATION_OPTIONS,
    ...props.paginationOptions
}));
const border = computed(() => props.border ?? isBorder.value);
const stripe = computed(() => props.stripe ?? isZebra.value);
const size = computed(() => props.size ?? tableSize.value);
const isEmpty = computed(() => props.data?.length === 0);
const paginationHeight = ref(0);
const tableHeaderHeight = ref(0);
useResizeObserver(paginationRef, (entries) => {
    const entry = entries[0];
    if (entry) {
        requestAnimationFrame(() => {
            paginationHeight.value = entry.contentRect.height;
        });
    }
});
useResizeObserver(tableHeaderRef, (entries) => {
    const entry = entries[0];
    if (entry) {
        requestAnimationFrame(() => {
            tableHeaderHeight.value = entry.contentRect.height;
        });
    }
});
const PAGINATION_SPACING = computed(() => (props.showTableHeader ? 6 : 15));
const { containerHeight } = useTableHeight({
    showTableHeader: computed(() => props.showTableHeader),
    paginationHeight,
    tableHeaderHeight,
    paginationSpacing: PAGINATION_SPACING
});
const height = computed(() => {
    if (isFullScreen.value)
        return '100%';
    if (isEmpty.value && !props.loading)
        return props.emptyHeight;
    if (props.height)
        return props.height;
    return '100%';
});
const headerCellStyle = computed(() => ({
    background: isHeaderBackground.value
        ? 'var(--el-fill-color-lighter)'
        : 'var(--default-box-color)',
    ...(props.headerCellStyle || {})
}));
const showPagination = computed(() => props.pagination && !isEmpty.value);
const cleanColumnProps = (col: ColumnOption) => {
    const columnProps = { ...col };
    delete columnProps.useHeaderSlot;
    delete columnProps.headerSlotName;
    delete columnProps.useSlot;
    delete columnProps.slotName;
    return columnProps;
};
const handleSizeChange = (val: number) => {
    emit('pagination:size-change', val);
};
const handleCurrentChange = (val: number) => {
    emit('pagination:current-change', val);
    scrollToTop();
};
const { scrollToTop: scrollPageToTop } = useCommon();
const scrollToTop = () => {
    nextTick(() => {
        elTableRef.value?.setScrollTop(0);
        scrollPageToTop();
    });
};
const getGlobalIndex = (index: number) => {
    if (!props.pagination)
        return index + 1;
    const { current, size } = props.pagination;
    return (current - 1) * size + index + 1;
};
const emit = defineEmits<{
    (e: 'pagination:size-change', val: number): void;
    (e: 'pagination:current-change', val: number): void;
}>();
const findTableHeader = () => {
    if (!props.showTableHeader) {
        tableHeaderRef.value = undefined;
        return;
    }
    const tableHeader = document.getElementById('art-table-header');
    if (tableHeader) {
        tableHeaderRef.value = tableHeader;
    }
    else {
        tableHeaderRef.value = undefined;
    }
};
watchEffect(() => {
    void props.data?.length;
    const shouldShow = props.showTableHeader;
    if (shouldShow) {
        nextTick(() => {
            findTableHeader();
        });
    }
    else {
        tableHeaderRef.value = undefined;
    }
}, { flush: 'post' });
defineExpose({
    scrollToTop,
    elTableRef
});
</script>

<style lang="scss" scoped>

  @use './style';
</style>
