import { useWorktabStore } from '@/store/modules/worktab';
import { RouteLocationNormalized } from 'vue-router';
import { isIframe } from './route';
import { useSettingStore } from '@/store/modules/setting';
import { IframeRouteManager } from '@/router/core';
import { useCommon } from '@/hooks/core/useCommon';
export const setWorktab = (to: RouteLocationNormalized): void => {
    const worktabStore = useWorktabStore();
    const { meta, path, name, params, query } = to;
    if (!meta.isHideTab) {
        if (isIframe(path)) {
            const iframeRoute = IframeRouteManager.getInstance().findByPath(to.path);
            if (iframeRoute?.meta) {
                worktabStore.openTab({
                    title: iframeRoute.meta.title,
                    icon: meta.icon as string,
                    path,
                    name: name as string,
                    keepAlive: meta.keepAlive as boolean,
                    params,
                    query
                });
            }
        }
        else if (useSettingStore().showWorkTab || path === useCommon().homePath.value) {
            worktabStore.openTab({
                title: meta.title as string,
                icon: meta.icon as string,
                path,
                name: name as string,
                keepAlive: meta.keepAlive as boolean,
                params,
                query,
                fixedTab: meta.fixedTab as boolean
            });
        }
    }
};
