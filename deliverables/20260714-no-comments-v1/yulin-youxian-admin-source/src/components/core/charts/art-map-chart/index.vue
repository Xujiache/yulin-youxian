
<template>
  <div class="relative w-full" :style="{ height: 'calc(100vh - 120px)' }">
    <div v-if="isEmpty" class="h-full flex-cc">
      <ElEmpty description="暂无地图数据" />
    </div>

    <div v-else id="china-map" ref="chinaMapRef" class="h-full w-full overflow-hidden rounded-lg" />
  </div>
</template>

<script setup lang="ts">
import { echarts } from '@/plugins/echarts';
import { useSettingStore } from '@/store/modules/setting';
import chinaMapJson from '@/mock/json/chinaMap.json';
import type { MapChartProps } from '@/types/component/chart';
defineOptions({ name: 'ArtMapChart' });
const chinaMapRef = ref<HTMLElement | null>(null);
const chartInstance = shallowRef<echarts.ECharts | null>(null);
const settingStore = useSettingStore();
const { isDark } = storeToRefs(settingStore);
const props = withDefaults(defineProps<MapChartProps>(), {
    mapData: () => [],
    selectedRegion: '',
    showLabels: true,
    showScatter: true,
    isEmpty: false
});
const emit = defineEmits<{
    renderComplete: [
    ];
    regionClick: [
        region: {
            name: string;
            adcode: string;
            level: string;
        }
    ];
}>();
const isEmpty = computed(() => {
    return props.isEmpty || (!props.mapData?.length && !chinaMapJson);
});
const prepareMapData = (geoJson: {
    features: Array<{
        properties: Record<string, unknown>;
    }>;
}) => {
    return geoJson.features.map((feature) => ({
        name: feature.properties.name as string,
        value: Math.round(Math.random() * 1000),
        adcode: feature.properties.adcode as string,
        level: feature.properties.level as string,
        selected: false
    }));
};
const getThemeStyles = () => ({
    borderColor: isDark.value ? 'rgba(255,255,255,0.6)' : 'rgba(147,235,248,1)',
    shadowColor: isDark.value ? 'rgba(0,0,0,0.8)' : 'rgba(128,217,248,1)',
    labelColor: isDark.value ? '#fff' : '#333',
    backgroundColor: isDark.value ? 'rgba(0,0,0,0.8)' : 'rgba(255,255,255,0.9)'
});
const createChartOption = (mapData: Array<Record<string, unknown>>) => {
    const themeStyles = getThemeStyles();
    return {
        animation: false,
        tooltip: {
            show: true,
            backgroundColor: themeStyles.backgroundColor,
            borderColor: isDark.value ? '#333' : '#ddd',
            borderWidth: 1,
            textStyle: {
                color: themeStyles.labelColor
            },
            formatter: ({ data }: {
                data?: Record<string, unknown>;
            }) => {
                const { name, adcode, level } = data || {};
                return `
            <div style="padding: 8px;">
              <div><strong>名称:</strong> ${name || '未知区域'}</div>
              <div><strong>代码:</strong> ${adcode || '暂无'}</div>
              <div><strong>级别:</strong> ${level || '暂无'}</div>
            </div>
          `;
            }
        },
        geo: {
            map: 'china',
            zoom: 1,
            show: true,
            roam: false,
            scaleLimit: {
                min: 0.8,
                max: 3
            },
            layoutSize: '100%',
            emphasis: {
                label: { show: props.showLabels },
                itemStyle: {
                    areaColor: 'rgba(82,180,255,0.9)',
                    borderColor: '#fff',
                    borderWidth: 3
                }
            },
            itemStyle: {
                borderColor: themeStyles.borderColor,
                borderWidth: 2,
                shadowColor: themeStyles.shadowColor,
                shadowOffsetX: 2,
                shadowOffsetY: 15,
                shadowBlur: 15
            }
        },
        series: [
            {
                type: 'map',
                map: 'china',
                aspectScale: 0.75,
                zoom: 1,
                label: {
                    show: props.showLabels,
                    color: '#fff',
                    fontSize: 10
                },
                itemStyle: {
                    borderColor: 'rgba(147,235,248,0.8)',
                    borderWidth: 2,
                    areaColor: {
                        type: 'linear',
                        x: 0,
                        y: 0,
                        x2: 0,
                        y2: 1,
                        colorStops: [
                            { offset: 0, color: 'rgba(147,235,248,0.3)' },
                            { offset: 1, color: 'rgba(32,120,207,0.9)' }
                        ]
                    },
                    shadowColor: 'rgba(32,120,207,1)',
                    shadowOffsetY: 15,
                    shadowBlur: 20
                },
                emphasis: {
                    label: {
                        show: true,
                        color: '#fff',
                        fontSize: 12
                    },
                    itemStyle: {
                        areaColor: 'rgba(82,180,255,0.9)',
                        borderColor: '#fff',
                        borderWidth: 3
                    }
                },
                select: {
                    label: {
                        show: true,
                        color: '#fff',
                        fontWeight: 'bold'
                    },
                    itemStyle: {
                        areaColor: '#4FAEFB',
                        borderColor: '#fff',
                        borderWidth: 2
                    }
                },
                data: mapData
            },
            ...(props.showScatter
                ? [
                    {
                        name: '城市',
                        type: 'scatter',
                        coordinateSystem: 'geo',
                        symbol: 'pin',
                        symbolSize: 15,
                        label: { show: false },
                        itemStyle: {
                            color: '#F99020',
                            shadowBlur: 10,
                            shadowColor: '#333'
                        },
                        data: [
                            { name: '北京', value: [116.405285, 39.904989, 100] },
                            { name: '上海', value: [121.472644, 31.231706, 100] },
                            { name: '深圳', value: [114.085947, 22.547, 100] }
                        ]
                    }
                ]
                : [])
        ]
    };
};
const initMap = async (): Promise<void> => {
    if (!chinaMapRef.value)
        return;
    chartInstance.value = echarts.init(chinaMapRef.value);
    echarts.registerMap('china', chinaMapJson as any);
    const mapData = props.mapData.length > 0 ? props.mapData : prepareMapData(chinaMapJson);
    const option = createChartOption(mapData);
    chartInstance.value.setOption(option);
    chartInstance.value.on('click', handleMapClick);
    emit('renderComplete');
};
const handleMapClick = (params: Record<string, unknown>) => {
    if (params.componentType === 'series') {
        const data = params.data as Record<string, unknown> | undefined;
        const regionData = {
            name: params.name as string,
            adcode: (data?.adcode as string) || '',
            level: (data?.level as string) || ''
        };
        console.log(`选中区域: ${params.name}`, params);
        chartInstance.value?.dispatchAction({
            type: 'select',
            seriesIndex: 0,
            dataIndex: params.dataIndex as number
        });
        emit('regionClick', regionData);
    }
};
const resizeChart = () => {
    chartInstance.value?.resize();
};
const cleanupChart = () => {
    if (chartInstance.value) {
        chartInstance.value.off('click', handleMapClick);
        chartInstance.value.dispose();
        chartInstance.value = null;
    }
    window.removeEventListener('resize', resizeChart);
};
onMounted(() => {
    if (!isEmpty.value) {
        initMap().then(() => {
            setTimeout(resizeChart, 100);
        });
    }
    window.addEventListener('resize', resizeChart);
});
onUnmounted(cleanupChart);
watch(isDark, (newVal, oldVal) => {
    if (newVal !== oldVal && chartInstance.value) {
        cleanupChart();
        nextTick(() => {
            if (!isEmpty.value) {
                initMap();
            }
        });
    }
});
watch(() => props.mapData, () => {
    if (chartInstance.value && !isEmpty.value) {
        const mapData = props.mapData.length > 0 ? props.mapData : prepareMapData(chinaMapJson);
        const option = createChartOption(mapData);
        chartInstance.value.setOption(option);
    }
}, { deep: true });
</script>
