import { useSettingStore } from '@/store/modules/setting';
import { storeToRefs } from 'pinia';
import type { ContainerWidthEnum } from '@/enums/appEnum';
export function useSettingsHandlers() {
    const settingStore = useSettingStore();
    const domOperations = {
        setHtmlClass: (className: string, add: boolean) => {
            const el = document.getElementsByTagName('html')[0];
            if (add) {
                el.classList.add(className);
            }
            else {
                el.classList.remove(className);
            }
        },
        setRootAttribute: (attribute: string, value: string) => {
            const el = document.documentElement;
            el.setAttribute(attribute, value);
        },
        setBodyClass: (className: string, add: boolean) => {
            const el = document.getElementsByTagName('body')[0];
            if (add) {
                el.classList.add(className);
            }
            else {
                el.classList.remove(className);
            }
        }
    };
    const createToggleHandler = (storeMethod: () => void, callback?: () => void) => {
        return () => {
            storeMethod();
            callback?.();
        };
    };
    const createValueHandler = <T>(storeMethod: (value: T) => void, callback?: (value: T) => void) => {
        return (value: T) => {
            if (value !== undefined && value !== null) {
                storeMethod(value);
                callback?.(value);
            }
        };
    };
    const basicHandlers = {
        workTab: createToggleHandler(() => settingStore.setWorkTab(!settingStore.showWorkTab)),
        uniqueOpened: createToggleHandler(() => settingStore.setUniqueOpened()),
        menuButton: createToggleHandler(() => settingStore.setButton()),
        fastEnter: createToggleHandler(() => settingStore.setFastEnter()),
        refreshButton: createToggleHandler(() => settingStore.setShowRefreshButton()),
        crumbs: createToggleHandler(() => settingStore.setCrumbs()),
        language: createToggleHandler(() => settingStore.setLanguage()),
        nprogress: createToggleHandler(() => settingStore.setNprogress()),
        colorWeak: createToggleHandler(() => settingStore.setColorWeak(), () => {
            domOperations.setHtmlClass('color-weak', settingStore.colorWeak);
        }),
        watermark: createToggleHandler(() => settingStore.setWatermarkVisible(!settingStore.watermarkVisible)),
        menuOpenWidth: createValueHandler<number>((width: number) => settingStore.setMenuOpenWidth(width)),
        tabStyle: createValueHandler<string>((style: string) => settingStore.setTabStyle(style)),
        pageTransition: createValueHandler<string>((transition: string) => settingStore.setPageTransition(transition)),
        customRadius: createValueHandler<string>((radius: string) => settingStore.setCustomRadius(radius))
    };
    const boxStyleHandlers = {
        setBoxMode: (type: 'border-mode' | 'shadow-mode') => {
            const { boxBorderMode } = storeToRefs(settingStore);
            if ((type === 'shadow-mode' && boxBorderMode.value === false) ||
                (type === 'border-mode' && boxBorderMode.value === true)) {
                return;
            }
            setTimeout(() => {
                domOperations.setRootAttribute('data-box-mode', type);
                settingStore.setBorderMode();
            }, 50);
        }
    };
    const colorHandlers = {
        selectColor: (theme: string) => {
            settingStore.setElementTheme(theme);
            settingStore.reload();
        }
    };
    const containerHandlers = {
        setWidth: (type: ContainerWidthEnum) => {
            settingStore.setContainerWidth(type);
            settingStore.reload();
        }
    };
    return {
        domOperations,
        basicHandlers,
        boxStyleHandlers,
        colorHandlers,
        containerHandlers,
        createToggleHandler,
        createValueHandler
    };
}
