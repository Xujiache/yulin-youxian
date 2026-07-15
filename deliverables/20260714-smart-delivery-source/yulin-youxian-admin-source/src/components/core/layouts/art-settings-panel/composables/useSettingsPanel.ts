import { ref, computed, watch } from 'vue';
import { useSettingStore } from '@/store/modules/setting';
import { storeToRefs } from 'pinia';
import { useBreakpoints } from '@vueuse/core';
import AppConfig from '@/config';
import { SystemThemeEnum, MenuTypeEnum } from '@/enums/appEnum';
import { mittBus } from '@/utils/sys';
import { useTheme } from '@/hooks/core/useTheme';
import { useCeremony } from '@/hooks/core/useCeremony';
import { useSettingsState } from './useSettingsState';
import { useSettingsHandlers } from './useSettingsHandlers';
export function useSettingsPanel() {
    const settingStore = useSettingStore();
    const { systemThemeType, systemThemeMode, menuType } = storeToRefs(settingStore);
    const { openFestival, cleanup } = useCeremony();
    const { setSystemTheme, setSystemAutoTheme } = useTheme();
    const { initColorWeak } = useSettingsState();
    const { domOperations } = useSettingsHandlers();
    const showDrawer = ref(false);
    const breakpoints = useBreakpoints({ tablet: 1000 });
    const isMobile = breakpoints.smaller('tablet');
    const beforeMenuType = ref<MenuTypeEnum>();
    const hasChangedMenu = ref(false);
    const systemThemeColor = computed(() => settingStore.systemThemeColor as string);
    const useThemeHandlers = () => {
        const initSystemColor = () => {
            if (!AppConfig.systemMainColor.includes(systemThemeColor.value)) {
                settingStore.setElementTheme(AppConfig.systemMainColor[0]);
                settingStore.reload();
            }
        };
        const initSystemTheme = () => {
            if (systemThemeMode.value === SystemThemeEnum.AUTO) {
                setSystemAutoTheme();
            }
            else {
                setSystemTheme(systemThemeType.value);
            }
        };
        const listenerSystemTheme = () => {
            const mediaQuery = window.matchMedia('(prefers-color-scheme: dark)');
            mediaQuery.addEventListener('change', initSystemTheme);
            return () => {
                mediaQuery.removeEventListener('change', initSystemTheme);
            };
        };
        return {
            initSystemColor,
            initSystemTheme,
            listenerSystemTheme
        };
    };
    const useResponsiveLayout = () => {
        const stopWatch = watch(isMobile, (mobile: boolean) => {
            if (mobile) {
                if (!hasChangedMenu.value) {
                    beforeMenuType.value = menuType.value;
                    useSettingsState().switchMenuLayouts(MenuTypeEnum.LEFT);
                    settingStore.setMenuOpen(false);
                    hasChangedMenu.value = true;
                }
            }
            else {
                if (hasChangedMenu.value && beforeMenuType.value) {
                    useSettingsState().switchMenuLayouts(beforeMenuType.value);
                    settingStore.setMenuOpen(true);
                    hasChangedMenu.value = false;
                }
            }
        }, { immediate: true });
        return { stopWatch };
    };
    const useDrawerControl = () => {
        let themeChangeTimer: ReturnType<typeof setTimeout> | null = null;
        const handleOpen = () => {
            if (themeChangeTimer) {
                clearTimeout(themeChangeTimer);
            }
            themeChangeTimer = setTimeout(() => {
                domOperations.setBodyClass('theme-change', true);
                themeChangeTimer = null;
            }, 500);
        };
        const handleClose = () => {
            if (themeChangeTimer) {
                clearTimeout(themeChangeTimer);
                themeChangeTimer = null;
            }
            domOperations.setBodyClass('theme-change', false);
        };
        const openSetting = () => {
            showDrawer.value = true;
        };
        const closeDrawer = () => {
            showDrawer.value = false;
        };
        return {
            handleOpen,
            handleClose,
            openSetting,
            closeDrawer
        };
    };
    const usePropsWatcher = (props: {
        open?: boolean;
    }) => {
        watch(() => props.open, (val: boolean | undefined) => {
            if (val !== undefined) {
                showDrawer.value = val;
            }
        });
    };
    const useSettingsInitializer = () => {
        const themeHandlers = useThemeHandlers();
        const { openSetting } = useDrawerControl();
        const { stopWatch } = useResponsiveLayout();
        let themeCleanup: (() => void) | null = null;
        const initializeSettings = () => {
            mittBus.on('openSetting', openSetting);
            themeHandlers.initSystemColor();
            themeCleanup = themeHandlers.listenerSystemTheme();
            initColorWeak();
            const boxMode = settingStore.boxBorderMode ? 'border-mode' : 'shadow-mode';
            domOperations.setRootAttribute('data-box-mode', boxMode);
            themeHandlers.initSystemTheme();
            openFestival();
        };
        const cleanupSettings = () => {
            stopWatch();
            themeCleanup?.();
            cleanup();
        };
        return {
            initializeSettings,
            cleanupSettings
        };
    };
    return {
        showDrawer,
        useThemeHandlers,
        useResponsiveLayout,
        useDrawerControl,
        usePropsWatcher,
        useSettingsInitializer
    };
}
