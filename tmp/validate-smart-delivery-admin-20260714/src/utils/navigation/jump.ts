import { AppRouteRecord } from '@/types/router';
import { router } from '@/router';
export const openExternalLink = (link: string) => {
    window.open(link, '_blank');
};
export const handleMenuJump = (item: AppRouteRecord, jumpToFirst: boolean = false) => {
    const { link, isIframe } = item.meta;
    if (link && !isIframe) {
        return openExternalLink(link);
    }
    if (!jumpToFirst || !item.children?.length) {
        return router.push(item.path);
    }
    const findFirstLeafMenu = (items: AppRouteRecord[]): AppRouteRecord => {
        for (const child of items) {
            if (!child.meta.isHide) {
                return child.children?.length ? findFirstLeafMenu(child.children) : child;
            }
        }
        return items[0];
    };
    const firstChild = findFirstLeafMenu(item.children);
    if (firstChild.meta?.link) {
        return openExternalLink(firstChild.meta.link);
    }
    router.push(firstChild.path);
};
