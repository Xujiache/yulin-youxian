import { h } from 'vue';
export class ComponentLoader {
    private modules: Record<string, () => Promise<any>>;
    constructor() {
        this.modules = import.meta.glob([
            '../../views/auth/**/*.vue',
            '../../views/exception/**/*.vue',
            '../../views/fresh/**/*.vue',
            '../../views/index/**/*.vue',
            '../../views/outside/**/*.vue'
        ]);
    }
    load(componentPath: string): () => Promise<any> {
        if (!componentPath) {
            return this.createEmptyComponent();
        }
        const fullPath = `../../views${componentPath}.vue`;
        const fullPathWithIndex = `../../views${componentPath}/index.vue`;
        const module = this.modules[fullPath] || this.modules[fullPathWithIndex];
        if (!module) {
            console.error(`[ComponentLoader] 未找到组件 ${componentPath}`);
            return this.createErrorComponent(componentPath);
        }
        return module;
    }
    loadLayout(): () => Promise<any> {
        return () => import('@/views/index/index.vue');
    }
    loadIframe(): () => Promise<any> {
        return () => import('@/views/outside/Iframe.vue');
    }
    private createEmptyComponent(): () => Promise<any> {
        return () => Promise.resolve({
            render() {
                return h('div', {});
            }
        });
    }
    private createErrorComponent(componentPath: string): () => Promise<any> {
        return () => Promise.resolve({
            render() {
                return h('div', { class: 'route-error' }, `组件未找到：${componentPath}`);
            }
        });
    }
}
