import type { AppRouteRecord } from '@/types/router';
import { RoutesAlias } from '../routesAlias';
export interface ValidationResult {
    valid: boolean;
    errors: string[];
    warnings: string[];
}
export class RouteValidator {
    private warnedRoutes = new Set<string>();
    validate(routes: AppRouteRecord[]): ValidationResult {
        const errors: string[] = [];
        const warnings: string[] = [];
        this.checkDuplicates(routes, errors, warnings);
        this.checkComponents(routes, errors, warnings);
        this.checkNestedIndexComponent(routes);
        return {
            valid: errors.length === 0,
            errors,
            warnings
        };
    }
    private checkDuplicates(routes: AppRouteRecord[], errors: string[], warnings: string[], parentPath = ''): void {
        const routeNameMap = new Map<string, string>();
        const componentPathMap = new Map<string, string>();
        const checkRoutes = (routes: AppRouteRecord[], parentPath = '') => {
            routes.forEach((route) => {
                const currentPath = route.path || '';
                const fullPath = this.resolvePath(parentPath, currentPath);
                if (route.name) {
                    const routeName = String(route.name);
                    if (routeNameMap.has(routeName)) {
                        warnings.push(`路由名称重复: "${routeName}" (${fullPath})`);
                    }
                    else {
                        routeNameMap.set(routeName, fullPath);
                    }
                }
                if (route.component && typeof route.component === 'string') {
                    const componentPath = route.component;
                    if (componentPath !== RoutesAlias.Layout) {
                        const componentKey = `${parentPath}:${componentPath}`;
                        if (componentPathMap.has(componentKey)) {
                            warnings.push(`组件路径重复: "${componentPath}" (${fullPath})`);
                        }
                        else {
                            componentPathMap.set(componentKey, fullPath);
                        }
                    }
                }
                if (route.children?.length) {
                    checkRoutes(route.children, fullPath);
                }
            });
        };
        checkRoutes(routes, parentPath);
    }
    private checkComponents(routes: AppRouteRecord[], errors: string[], warnings: string[], parentPath = ''): void {
        routes.forEach((route) => {
            const hasExternalLink = !!route.meta?.link?.trim();
            const hasChildren = Array.isArray(route.children) && route.children.length > 0;
            const routePath = route.path || '[未定义路径]';
            const isIframe = route.meta?.isIframe;
            if (route.component) {
                if (route.children?.length) {
                    const fullPath = this.resolvePath(parentPath, route.path || '');
                    this.checkComponents(route.children, errors, warnings, fullPath);
                }
                return;
            }
            if (parentPath === '' && !hasExternalLink && !isIframe) {
                errors.push(`一级菜单(${routePath}) 缺少 component，必须指向 ${RoutesAlias.Layout}`);
                return;
            }
            if (!hasExternalLink && !isIframe && !hasChildren) {
                errors.push(`路由(${routePath}) 缺少 component 配置`);
            }
            if (route.children?.length) {
                const fullPath = this.resolvePath(parentPath, route.path || '');
                this.checkComponents(route.children, errors, warnings, fullPath);
            }
        });
    }
    private checkNestedIndexComponent(routes: AppRouteRecord[], level = 1): void {
        routes.forEach((route) => {
            if (level > 1 && route.component === RoutesAlias.Layout) {
                this.logLayoutError(route, level);
            }
            if (route.children?.length) {
                this.checkNestedIndexComponent(route.children, level + 1);
            }
        });
    }
    private logLayoutError(route: AppRouteRecord, level: number): void {
        const routeName = String(route.name || route.path || '未知路由');
        const routeKey = `${routeName}_${route.path}`;
        if (this.warnedRoutes.has(routeKey))
            return;
        this.warnedRoutes.add(routeKey);
        const menuTitle = route.meta?.title || routeName;
        const routePath = route.path || '/';
        console.error(`[路由配置错误] 菜单 "${menuTitle}" (name: ${routeName}, path: ${routePath}) 配置错误\n` +
            `  问题: ${level}级菜单不能使用 ${RoutesAlias.Layout} 作为 component\n` +
            `  说明: 只有一级菜单才能使用 ${RoutesAlias.Layout}，二级及以下菜单应该指向具体的组件路径\n` +
            `  当前配置: component: '${RoutesAlias.Layout}'\n` +
            `  应该改为: component: '/your/component/path' 或留空 ''（如果是目录菜单）`);
    }
    private resolvePath(parent: string, child: string): string {
        return [parent.replace(/\/$/, ''), child.replace(/^\//, '')].filter(Boolean).join('/');
    }
}
