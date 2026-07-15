import { defineAsyncComponent } from 'vue';
export const globalComponentsConfig: GlobalComponentConfig[] = [
    {
        name: '设置面板',
        key: 'settings-panel',
        component: defineAsyncComponent(() => import('@/components/core/layouts/art-settings-panel/index.vue')),
        enabled: true
    },
    {
        name: '全局搜索',
        key: 'global-search',
        component: defineAsyncComponent(() => import('@/components/core/layouts/art-global-search/index.vue')),
        enabled: true
    },
    {
        name: '锁屏',
        key: 'screen-lock',
        component: defineAsyncComponent(() => import('@/components/core/layouts/art-screen-lock/index.vue')),
        enabled: true
    },
    {
        name: '聊天窗口',
        key: 'chat-window',
        component: defineAsyncComponent(() => import('@/components/core/layouts/art-chat-window/index.vue')),
        enabled: true
    },
    {
        name: '礼花效果',
        key: 'fireworks-effect',
        component: defineAsyncComponent(() => import('@/components/core/layouts/art-fireworks-effect/index.vue')),
        enabled: true
    },
    {
        name: '水印效果',
        key: 'watermark',
        component: defineAsyncComponent(() => import('@/components/core/others/art-watermark/index.vue')),
        enabled: true
    }
];
export interface GlobalComponentConfig {
    name: string;
    key: string;
    component: any;
    enabled?: boolean;
    description?: string;
}
export const getEnabledGlobalComponents = () => {
    return globalComponentsConfig.filter((config) => config.enabled !== false);
};
export const getGlobalComponentByKey = (key: string) => {
    return globalComponentsConfig.find((config) => config.key === key);
};
