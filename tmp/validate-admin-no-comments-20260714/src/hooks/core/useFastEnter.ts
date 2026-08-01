import { computed } from 'vue';
import appConfig from '@/config';
import type { FastEnterApplication, FastEnterQuickLink } from '@/types/config';
export function useFastEnter() {
    const fastEnterConfig = computed(() => appConfig.fastEnter);
    const enabledApplications = computed<FastEnterApplication[]>(() => {
        if (!fastEnterConfig.value?.applications)
            return [];
        return fastEnterConfig.value.applications
            .filter((app) => app.enabled !== false)
            .sort((a, b) => (a.order || 0) - (b.order || 0));
    });
    const enabledQuickLinks = computed<FastEnterQuickLink[]>(() => {
        if (!fastEnterConfig.value?.quickLinks)
            return [];
        return fastEnterConfig.value.quickLinks
            .filter((link) => link.enabled !== false)
            .sort((a, b) => (a.order || 0) - (b.order || 0));
    });
    const minWidth = computed(() => {
        return fastEnterConfig.value?.minWidth || 1200;
    });
    return {
        fastEnterConfig,
        enabledApplications,
        enabledQuickLinks,
        minWidth
    };
}
