import type { App } from 'vue';
export function vueErrorHandler(err: unknown, instance: any, info: string) {
    console.error('[VueError]', err, info, instance);
}
export function scriptErrorHandler(_message: Event | string, _source?: string, _lineno?: number, _colno?: number, _error?: Error): boolean {
    return true;
}
export function registerPromiseErrorHandler() {
    window.addEventListener('unhandledrejection', (event) => {
        console.error('[PromiseError]', event.reason);
    });
}
export function registerResourceErrorHandler() {
    window.addEventListener('error', (event: Event) => {
        const target = event.target as HTMLElement;
        if (target &&
            (target.tagName === 'IMG' || target.tagName === 'SCRIPT' || target.tagName === 'LINK')) {
            console.error('[ResourceError]', {
                tagName: target.tagName,
                src: (target as HTMLImageElement).src ||
                    (target as HTMLScriptElement).src ||
                    (target as HTMLLinkElement).href
            });
        }
    }, true);
}
export function setupErrorHandle(app: App) {
    app.config.errorHandler = vueErrorHandler;
    window.onerror = scriptErrorHandler;
    registerPromiseErrorHandler();
    registerResourceErrorHandler();
}
