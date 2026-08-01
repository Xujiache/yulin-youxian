import { useSettingStore } from '@/store/modules/setting';
import { MenuThemeEnum, MenuTypeEnum } from '@/enums/appEnum';
export function useSettingsState() {
    const settingStore = useSettingStore();
    const initColorWeak = () => {
        if (settingStore.colorWeak) {
            const el = document.getElementsByTagName('html')[0];
            setTimeout(() => {
                el.classList.add('color-weak');
            }, 100);
        }
    };
    const switchMenuLayouts = (type: MenuTypeEnum) => {
        if (type === MenuTypeEnum.LEFT || type === MenuTypeEnum.TOP_LEFT) {
            settingStore.setMenuOpen(true);
        }
        settingStore.switchMenuLayouts(type);
        if (type === MenuTypeEnum.DUAL_MENU) {
            settingStore.switchMenuStyles(MenuThemeEnum.DESIGN);
            settingStore.setMenuOpen(true);
        }
    };
    return {
        initColorWeak,
        switchMenuLayouts
    };
}
