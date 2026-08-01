import { useSettingStore } from '@/store/modules/setting';
import { SystemThemeEnum } from '@/enums/appEnum';
import AppConfig from '@/config';
import { SystemThemeTypes } from '@/types/store';
import { getDarkColor, getLightColor, setElementThemeColor } from '@/utils/ui';
import { usePreferredDark } from '@vueuse/core';
import { watch } from 'vue';
export function useTheme() {
    const settingStore = useSettingStore();
    const disableTransitions = () => {
        const style = document.createElement('style');
        style.setAttribute('id', 'disable-transitions');
        style.textContent = '* { transition: none !important; }';
        document.head.appendChild(style);
    };
    const enableTransitions = () => {
        const style = document.getElementById('disable-transitions');
        if (style) {
            style.remove();
        }
    };
    const setSystemTheme = (theme: SystemThemeEnum, themeMode?: SystemThemeEnum) => {
        disableTransitions();
        const el = document.getElementsByTagName('html')[0];
        const isDark = theme === SystemThemeEnum.DARK;
        if (!themeMode) {
            themeMode = theme;
        }
        const currentTheme = AppConfig.systemThemeStyles[theme as keyof SystemThemeTypes];
        if (currentTheme) {
            el.setAttribute('class', currentTheme.className);
        }
        const primary = settingStore.systemThemeColor;
        for (let i = 1; i <= 9; i++) {
            document.documentElement.style.setProperty(`--el-color-primary-light-${i}`, isDark ? `${getDarkColor(primary, i / 10)}` : `${getLightColor(primary, i / 10)}`);
        }
        settingStore.setGlopTheme(theme, themeMode);
        requestAnimationFrame(() => {
            requestAnimationFrame(() => {
                enableTransitions();
            });
        });
    };
    const prefersDark = usePreferredDark();
    const setSystemAutoTheme = () => {
        const theme = prefersDark.value ? SystemThemeEnum.DARK : SystemThemeEnum.LIGHT;
        setSystemTheme(theme, SystemThemeEnum.AUTO);
    };
    const switchThemeStyles = (theme: SystemThemeEnum) => {
        if (theme === SystemThemeEnum.AUTO) {
            setSystemAutoTheme();
        }
        else {
            setSystemTheme(theme);
        }
    };
    return {
        setSystemTheme,
        setSystemAutoTheme,
        switchThemeStyles,
        prefersDark
    };
}
export function initializeTheme() {
    const settingStore = useSettingStore();
    const prefersDark = usePreferredDark();
    const applyThemeByMode = () => {
        const el = document.getElementsByTagName('html')[0];
        let actualTheme = settingStore.systemThemeType;
        if (settingStore.systemThemeMode === SystemThemeEnum.AUTO) {
            actualTheme = prefersDark.value ? SystemThemeEnum.DARK : SystemThemeEnum.LIGHT;
            settingStore.systemThemeType = actualTheme;
        }
        const currentTheme = AppConfig.systemThemeStyles[actualTheme as keyof SystemThemeTypes];
        if (currentTheme) {
            el.setAttribute('class', currentTheme.className);
        }
        setElementThemeColor(settingStore.systemThemeColor);
        document.documentElement.style.setProperty('--custom-radius', `${settingStore.customRadius}rem`);
    };
    applyThemeByMode();
    if (settingStore.systemThemeMode === SystemThemeEnum.AUTO) {
        watch(prefersDark, () => {
            if (settingStore.systemThemeMode === SystemThemeEnum.AUTO) {
                applyThemeByMode();
            }
        }, { immediate: false });
    }
}
