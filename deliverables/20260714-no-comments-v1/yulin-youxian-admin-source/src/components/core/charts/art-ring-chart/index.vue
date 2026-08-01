
<template>
  <div
    ref="chartRef"
    class="relative w-full"
    :style="{ height: props.height }"
    v-loading="props.loading"
  >
  </div>
</template>

<script setup lang="ts">
import type { EChartsOption } from '@/plugins/echarts';
import { useChartOps, useChartComponent } from '@/hooks/core/useChart';
import type { RingChartProps } from '@/types/component/chart';
defineOptions({ name: 'ArtRingChart' });
const props = withDefaults(defineProps<RingChartProps>(), {
    height: useChartOps().chartHeight,
    loading: false,
    isEmpty: false,
    colors: () => useChartOps().colors,
    data: () => [],
    radius: () => ['50%', '80%'],
    borderRadius: 10,
    centerText: '',
    showLabel: false,
    showTooltip: true,
    showLegend: false,
    legendPosition: 'right'
});
const { chartRef, isDark, getAnimationConfig, getTooltipStyle, getLegendStyle } = useChartComponent({
    props,
    checkEmpty: () => {
        return !props.data?.length || props.data.every((item) => item.value === 0);
    },
    watchSources: [() => props.data, () => props.centerText],
    generateOptions: (): EChartsOption => {
        const getCenterPosition = (): [
            string,
            string
        ] => {
            if (!props.showLegend)
                return ['50%', '50%'];
            switch (props.legendPosition) {
                case 'left':
                    return ['60%', '50%'];
                case 'right':
                    return ['40%', '50%'];
                case 'top':
                    return ['50%', '60%'];
                case 'bottom':
                    return ['50%', '40%'];
                default:
                    return ['50%', '50%'];
            }
        };
        const option: EChartsOption = {
            tooltip: props.showTooltip
                ? getTooltipStyle('item', {
                    formatter: '{b}: {c} ({d}%)'
                })
                : undefined,
            legend: props.showLegend ? getLegendStyle(props.legendPosition) : undefined,
            series: [
                {
                    name: '数据占比',
                    type: 'pie',
                    radius: props.radius,
                    center: getCenterPosition(),
                    avoidLabelOverlap: false,
                    itemStyle: {
                        borderRadius: props.borderRadius,
                        borderColor: isDark.value ? '#2c2c2c' : '#fff',
                        borderWidth: 0
                    },
                    label: {
                        show: props.showLabel,
                        formatter: '{b}\n{d}%',
                        position: 'outside',
                        color: isDark.value ? '#ccc' : '#999',
                        fontSize: 12
                    },
                    emphasis: {
                        label: {
                            show: false,
                            fontSize: 14,
                            fontWeight: 'bold'
                        }
                    },
                    labelLine: {
                        show: props.showLabel,
                        length: 15,
                        length2: 25,
                        smooth: true
                    },
                    data: props.data,
                    color: props.colors,
                    ...getAnimationConfig(),
                    animationType: 'expansion'
                }
            ]
        };
        if (props.centerText) {
            const centerPos = getCenterPosition();
            option.title = {
                text: props.centerText,
                left: centerPos[0],
                top: centerPos[1],
                textAlign: 'center',
                textVerticalAlign: 'middle',
                textStyle: {
                    fontSize: 18,
                    fontWeight: 500,
                    color: isDark.value ? '#999' : '#ADB0BC'
                }
            };
        }
        return option;
    }
});
</script>
