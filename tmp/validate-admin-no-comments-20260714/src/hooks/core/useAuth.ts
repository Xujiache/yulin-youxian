import { useRoute } from 'vue-router';
import { storeToRefs } from 'pinia';
import { useUserStore } from '@/store/modules/user';
import { useAppMode } from '@/hooks/core/useAppMode';
import type { AppRouteRecord } from '@/types/router';
type AuthItem = NonNullable<AppRouteRecord['meta']['authList']>[number];
const userStore = useUserStore();
export const useAuth = () => {
    const route = useRoute();
    const { isFrontendMode } = useAppMode();
    const { info } = storeToRefs(userStore);
    const frontendAuthList = info.value?.buttons ?? [];
    const backendAuthList: AuthItem[] = Array.isArray(route.meta.authList)
        ? (route.meta.authList as AuthItem[])
        : [];
    const hasAuth = (auth: string): boolean => {
        if (isFrontendMode.value) {
            return frontendAuthList.includes(auth);
        }
        return backendAuthList.some((item) => item?.authMark === auth);
    };
    return {
        hasAuth
    };
};
