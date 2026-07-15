import type { Router, RouteRecordRaw } from 'vue-router';
import type { AppRouteRecord } from '@/types/router';
import { ComponentLoader } from './ComponentLoader';
import { RouteValidator } from './RouteValidator';
import { RouteTransformer } from './RouteTransformer';
export class RouteRegistry {
    private router: Router;
    private componentLoader: ComponentLoader;
    private validator: RouteValidator;
    private transformer: RouteTransformer;
    private removeRouteFns: (() => void)[] = [];
    private registered = false;
    constructor(router: Router) {
        this.router = router;
        this.componentLoader = new ComponentLoader();
        this.validator = new RouteValidator();
        this.transformer = new RouteTransformer(this.componentLoader);
    }
    register(menuList: AppRouteRecord[]): void {
        if (this.registered) {
            console.warn('[RouteRegistry] 路由已注册，跳过重复注册');
            return;
        }
        const validationResult = this.validator.validate(menuList);
        if (!validationResult.valid) {
            throw new Error(`路由配置验证失败: ${validationResult.errors.join(', ')}`);
        }
        const removeRouteFns: (() => void)[] = [];
        menuList.forEach((route) => {
            if (route.name && !this.router.hasRoute(route.name)) {
                const routeConfig = this.transformer.transform(route);
                const removeRouteFn = this.router.addRoute(routeConfig as RouteRecordRaw);
                removeRouteFns.push(removeRouteFn);
            }
        });
        this.removeRouteFns = removeRouteFns;
        this.registered = true;
    }
    unregister(): void {
        this.removeRouteFns.forEach((fn) => fn());
        this.removeRouteFns = [];
        this.registered = false;
    }
    isRegistered(): boolean {
        return this.registered;
    }
    getRemoveRouteFns(): (() => void)[] {
        return this.removeRouteFns;
    }
    markAsRegistered(): void {
        this.registered = true;
    }
}
