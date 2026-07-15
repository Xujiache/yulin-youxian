


<template>
  <section class="art-search-bar art-card-xs" :class="{ 'is-expanded': isExpanded }">
    <ElForm
      ref="formRef"
      :model="modelValue"
      :label-position="labelPosition"
      v-bind="{ ...$attrs }"
    >
      <ElRow :gutter="gutter">
        <ElCol
          v-for="item in visibleFormItems"
          :key="item.key"
          :xs="getColSpan(item.span, 'xs')"
          :sm="getColSpan(item.span, 'sm')"
          :md="getColSpan(item.span, 'md')"
          :lg="getColSpan(item.span, 'lg')"
          :xl="getColSpan(item.span, 'xl')"
        >
          <ElFormItem
            :prop="item.key"
            :label-width="item.label ? item.labelWidth || labelWidth : undefined"
          >
            <template #label v-if="item.label">
              <component v-if="typeof item.label !== 'string'" :is="item.label" />
              <span v-else>{{ item.label }}</span>
            </template>
            <slot :name="item.key" :item="item" :modelValue="modelValue">
              <component
                :is="getComponent(item)"
                v-model="modelValue[item.key]"
                v-bind="getProps(item)"
              >
                
                <template v-if="item.type === 'select' && getProps(item)?.options">
                  <ElOption
                    v-for="option in getProps(item).options"
                    v-bind="option"
                    :key="option.value"
                  />
                </template>

                
                <template v-if="item.type === 'checkboxgroup' && getProps(item)?.options">
                  <ElCheckbox
                    v-for="option in getProps(item).options"
                    v-bind="option"
                    :key="option.value"
                  />
                </template>

                
                <template v-if="item.type === 'radiogroup' && getProps(item)?.options">
                  <ElRadio
                    v-for="option in getProps(item).options"
                    v-bind="option"
                    :key="option.value"
                  />
                </template>

                
                <template v-for="(slotFn, slotName) in getSlots(item)" :key="slotName" #[slotName]>
                  <component :is="slotFn" />
                </template>
              </component>
            </slot>
          </ElFormItem>
        </ElCol>
        <ElCol :xs="24" :sm="24" :md="span" :lg="span" :xl="span" class="action-column">
          <div class="action-buttons-wrapper" :style="actionButtonsStyle">
            <div class="form-buttons">
              <ElButton v-if="showReset" class="reset-button" @click="handleReset" v-ripple>
                {{ t('table.searchBar.reset') }}
              </ElButton>
              <ElButton
                v-if="showSearch"
                type="primary"
                class="search-button"
                @click="handleSearch"
                v-ripple
                :disabled="disabledSearch"
              >
                {{ t('table.searchBar.search') }}
              </ElButton>
            </div>
            <div v-if="shouldShowExpandToggle" class="filter-toggle" @click="toggleExpand">
              <span>{{ expandToggleText }}</span>
              <div class="icon-wrapper">
                <ElIcon>
                  <ArrowUpBold v-if="isExpanded" />
                  <ArrowDownBold v-else />
                </ElIcon>
              </div>
            </div>
          </div>
        </ElCol>
      </ElRow>
    </ElForm>
  </section>
</template>

<script setup lang="ts">
import { ArrowUpBold, ArrowDownBold } from '@element-plus/icons-vue';
import { useWindowSize } from '@vueuse/core';
import { useI18n } from 'vue-i18n';
import type { Component } from 'vue';
import { ElCascader, ElCheckbox, ElCheckboxGroup, ElDatePicker, ElInput, ElInputTag, ElInputNumber, ElRadioGroup, ElRate, ElSelect, ElSlider, ElSwitch, ElTimePicker, ElTimeSelect, ElTreeSelect, type FormInstance } from 'element-plus';
import { calculateResponsiveSpan, type ResponsiveBreakpoint } from '@/utils/form/responsive';
defineOptions({ name: 'ArtSearchBar' });
const componentMap = {
    input: ElInput,
    inputTag: ElInputTag,
    number: ElInputNumber,
    select: ElSelect,
    switch: ElSwitch,
    checkbox: ElCheckbox,
    checkboxgroup: ElCheckboxGroup,
    radiogroup: ElRadioGroup,
    date: ElDatePicker,
    daterange: ElDatePicker,
    datetime: ElDatePicker,
    datetimerange: ElDatePicker,
    rate: ElRate,
    slider: ElSlider,
    cascader: ElCascader,
    timepicker: ElTimePicker,
    timeselect: ElTimeSelect,
    treeselect: ElTreeSelect
};
const { width } = useWindowSize();
const { t } = useI18n();
const isMobile = computed(() => width.value < 500);
const formInstance = useTemplateRef<FormInstance>('formRef');
export interface SearchFormItem {
    key: string;
    label: string | (() => VNode) | Component;
    labelWidth?: string | number;
    type?: keyof typeof componentMap | string;
    render?: (() => VNode) | Component;
    hidden?: boolean;
    span?: number;
    options?: Record<string, any>;
    props?: Record<string, any>;
    slots?: Record<string, (() => any) | undefined>;
    placeholder?: string;
}
interface SearchBarProps {
    items: SearchFormItem[];
    span?: number;
    gutter?: number;
    isExpand?: boolean;
    defaultExpanded?: boolean;
    labelPosition?: 'left' | 'right' | 'top';
    labelWidth?: string | number;
    showExpand?: boolean;
    buttonLeftLimit?: number;
    showReset?: boolean;
    showSearch?: boolean;
    disabledSearch?: boolean;
}
const props = withDefaults(defineProps<SearchBarProps>(), {
    items: () => [],
    span: 6,
    gutter: 12,
    isExpand: false,
    labelPosition: 'right',
    labelWidth: '70px',
    showExpand: true,
    defaultExpanded: false,
    buttonLeftLimit: 2,
    showReset: true,
    showSearch: true,
    disabledSearch: false
});
interface SearchBarEmits {
    reset: [
    ];
    search: [
    ];
}
const emit = defineEmits<SearchBarEmits>();
const modelValue = defineModel<Record<string, any>>({ default: {} });
const isExpanded = ref(props.defaultExpanded);
const rootProps = ['label', 'labelWidth', 'key', 'type', 'hidden', 'span', 'slots'];
const getProps = (item: SearchFormItem) => {
    if (item.props)
        return item.props;
    const props = { ...item };
    rootProps.forEach((key) => delete (props as Record<string, any>)[key]);
    return props;
};
const getSlots = (item: SearchFormItem) => {
    if (!item.slots)
        return {};
    const validSlots: Record<string, () => any> = {};
    Object.entries(item.slots).forEach(([key, slotFn]) => {
        if (slotFn) {
            validSlots[key] = slotFn;
        }
    });
    return validSlots;
};
const getColSpan = (itemSpan: number | undefined, breakpoint: ResponsiveBreakpoint): number => {
    return calculateResponsiveSpan(itemSpan, span.value, breakpoint);
};
const getComponent = (item: SearchFormItem) => {
    if (item.render) {
        return item.render;
    }
    const { type } = item;
    return componentMap[type as keyof typeof componentMap] || componentMap['input'];
};
const visibleFormItems = computed(() => {
    const filteredItems = props.items.filter((item) => !item.hidden);
    const shouldShowLess = !props.isExpand && !isExpanded.value;
    if (shouldShowLess) {
        const maxItemsPerRow = Math.floor(24 / props.span) - 1;
        return filteredItems.slice(0, maxItemsPerRow);
    }
    return filteredItems;
});
const shouldShowExpandToggle = computed(() => {
    const filteredItems = props.items.filter((item) => !item.hidden);
    return (!props.isExpand && props.showExpand && filteredItems.length > Math.floor(24 / props.span) - 1);
});
const expandToggleText = computed(() => {
    return isExpanded.value ? t('table.searchBar.collapse') : t('table.searchBar.expand');
});
const actionButtonsStyle = computed(() => ({
    'justify-content': isMobile.value
        ? 'flex-end'
        : props.items.filter((item) => !item.hidden).length <= props.buttonLeftLimit
            ? 'flex-start'
            : 'flex-end'
}));
const toggleExpand = () => {
    isExpanded.value = !isExpanded.value;
};
const handleReset = () => {
    formInstance.value?.resetFields();
    Object.assign(modelValue.value, Object.fromEntries(props.items.map(({ key }) => [key, undefined])));
    emit('reset');
};
const handleSearch = () => {
    emit('search');
};
defineExpose({
    ref: formInstance,
    validate: (...args: any[]) => formInstance.value?.validate(...args),
    reset: handleReset
});
const { span, gutter, labelPosition, labelWidth } = toRefs(props);
</script>

<style lang="scss" scoped>

  .art-search-bar {
    padding: 15px 20px 0;

    .action-column {
      flex: 1;
      max-width: 100%;

      .action-buttons-wrapper {
        display: flex;
        flex-wrap: wrap;
        align-items: center;
        justify-content: flex-end;
        margin-bottom: 12px;
      }

      .form-buttons {
        display: flex;
        gap: 8px;
      }

      .filter-toggle {
        display: flex;
        align-items: center;
        margin-left: 10px;
        line-height: 32px;
        color: var(--theme-color);
        cursor: pointer;
        transition: color 0.2s ease;

        &:hover {
          color: var(--ElColor-primary);
        }

        span {
          font-size: 14px;
          user-select: none;
        }

        .icon-wrapper {
          display: flex;
          align-items: center;
          margin-left: 4px;
          font-size: 14px;
          transition: transform 0.2s ease;
        }
      }
    }
  }

  
  @media (width <= 768px) {
    .art-search-bar {
      padding: 16px 16px 0;

      .action-column {
        .action-buttons-wrapper {
          flex-direction: column;
          gap: 8px;
          align-items: stretch;

          .form-buttons {
            justify-content: center;
          }

          .filter-toggle {
            justify-content: center;
            margin-left: 0;
          }
        }
      }
    }
  }
</style>
