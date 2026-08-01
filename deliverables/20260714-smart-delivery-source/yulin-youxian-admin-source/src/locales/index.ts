import { createI18n } from 'vue-i18n';
import type { I18n, I18nOptions } from 'vue-i18n';
import { LanguageEnum } from '@/enums/appEnum';
import { getSystemStorage } from '@/utils/storage';
import { StorageKeyManager } from '@/utils/storage/storage-key-manager';
import enMessages from './langs/en.json';
import zhMessages from './langs/zh.json';
const storageKeyManager = new StorageKeyManager();
const messages = {
    [LanguageEnum.EN]: enMessages,
    [LanguageEnum.ZH]: zhMessages
};
export const languageOptions = [
    { value: LanguageEnum.ZH, label: '简体中文' },
    { value: LanguageEnum.EN, label: 'English' }
];
const getDefaultLanguage = (): LanguageEnum => {
    try {
        const storageKey = storageKeyManager.getStorageKey('user');
        const userStore = localStorage.getItem(storageKey);
        if (userStore) {
            const { language } = JSON.parse(userStore);
            if (language && Object.values(LanguageEnum).includes(language)) {
                return language;
            }
        }
    }
    catch (error) {
        console.warn('[i18n] 从版本化存储获取语言设置失败:', error);
    }
    try {
        const sys = getSystemStorage();
        if (sys) {
            const { user } = JSON.parse(sys);
            if (user?.language && Object.values(LanguageEnum).includes(user.language)) {
                return user.language;
            }
        }
    }
    catch (error) {
        console.warn('[i18n] 从系统存储获取语言设置失败:', error);
    }
    console.debug('[i18n] 使用默认语言:', LanguageEnum.ZH);
    return LanguageEnum.ZH;
};
const i18nOptions: I18nOptions = {
    locale: getDefaultLanguage(),
    legacy: false,
    globalInjection: true,
    fallbackLocale: LanguageEnum.ZH,
    messages
};
const i18n: I18n = createI18n(i18nOptions);
interface Translation {
    (key: string): string;
}
export const $t = i18n.global.t as Translation;
export default i18n;
