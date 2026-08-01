import { ref, reactive, computed, onMounted, onUnmounted, nextTick, readonly } from 'vue';
import { useWindowSize } from '@vueuse/core';
import { useTableColumns } from './useTableColumns';
import type { ColumnOption } from '@/types/component';
import { TableCache, CacheInvalidationStrategy, type ApiResponse } from '../../utils/table/tableCache';
import { type TableError, defaultResponseAdapter, extractTableData, updatePaginationFromResponse, createSmartDebounce, createErrorHandler } from '../../utils/table/tableUtils';
import { tableConfig } from '../../utils/table/tableConfig';
type InferApiParams<T> = T extends (params: infer P) => any ? P : never;
type InferApiResponse<T> = T extends (params: any) => Promise<infer R> ? R : never;
type InferRecordType<T> = T extends Api.Common.PaginatedResponse<infer U> ? U : never;
export interface UseTableConfig<TApiFn extends (params: any) => Promise<any> = (params: any) => Promise<any>, TRecord = InferRecordType<InferApiResponse<TApiFn>>, TParams = InferApiParams<TApiFn>, TResponse = InferApiResponse<TApiFn>> {
    core: {
        apiFn: TApiFn;
        apiParams?: Partial<TParams>;
        excludeParams?: string[];
        immediate?: boolean;
        columnsFactory?: () => ColumnOption<TRecord>[];
        paginationKey?: {
            current?: string;
            size?: string;
        };
    };
    transform?: {
        dataTransformer?: (data: TRecord[]) => TRecord[];
        responseAdapter?: (response: TResponse) => ApiResponse<TRecord>;
    };
    performance?: {
        enableCache?: boolean;
        cacheTime?: number;
        debounceTime?: number;
        maxCacheSize?: number;
    };
    hooks?: {
        onSuccess?: (data: TRecord[], response: ApiResponse<TRecord>) => void;
        onError?: (error: TableError) => void;
        onCacheHit?: (data: TRecord[], response: ApiResponse<TRecord>) => void;
        onLoading?: (loading: boolean) => void;
        resetFormCallback?: () => void;
    };
    debug?: {
        enableLog?: boolean;
        logLevel?: 'info' | 'warn' | 'error';
    };
}
export function useTable<TApiFn extends (params: any) => Promise<any>>(config: UseTableConfig<TApiFn>) {
    return useTableImpl(config);
}
function useTableImpl<TApiFn extends (params: any) => Promise<any>>(config: UseTableConfig<TApiFn>) {
    type TRecord = InferRecordType<InferApiResponse<TApiFn>>;
    type TParams = InferApiParams<TApiFn>;
    const { core: { apiFn, apiParams = {} as Partial<TParams>, excludeParams = [], immediate = true, columnsFactory, paginationKey }, transform: { dataTransformer, responseAdapter = defaultResponseAdapter } = {}, performance: { enableCache = false, cacheTime = 5 * 60 * 1000, debounceTime = 300, maxCacheSize = 50 } = {}, hooks: { onSuccess, onError, onCacheHit, resetFormCallback } = {}, debug: { enableLog = false } = {} } = config;
    const pageKey = paginationKey?.current || tableConfig.paginationKey.current;
    const sizeKey = paginationKey?.size || tableConfig.paginationKey.size;
    const cacheUpdateTrigger = ref(0);
    const logger = {
        log: (message: string, ...args: unknown[]) => {
            if (enableLog) {
                console.log(`[useTable] ${message}`, ...args);
            }
        },
        warn: (message: string, ...args: unknown[]) => {
            if (enableLog) {
                console.warn(`[useTable] ${message}`, ...args);
            }
        },
        error: (message: string, ...args: unknown[]) => {
            if (enableLog) {
                console.error(`[useTable] ${message}`, ...args);
            }
        }
    };
    const cache = enableCache ? new TableCache<TRecord>(cacheTime, maxCacheSize, enableLog) : null;
    type LoadingState = 'idle' | 'loading' | 'success' | 'error';
    const loadingState = ref<LoadingState>('idle');
    const loading = computed(() => loadingState.value === 'loading');
    const error = ref<TableError | null>(null);
    const data = ref<TRecord[]>([]);
    let abortController: AbortController | null = null;
    let cacheCleanupTimer: NodeJS.Timeout | null = null;
    const searchParams = reactive(Object.assign({
        [pageKey]: 1,
        [sizeKey]: 10
    }, apiParams || {}) as TParams);
    const pagination = reactive<Api.Common.PaginationParams>({
        current: ((searchParams as Record<string, unknown>)[pageKey] as number) || 1,
        size: ((searchParams as Record<string, unknown>)[sizeKey] as number) || 10,
        total: 0
    });
    const { width } = useWindowSize();
    const mobilePagination = computed(() => ({
        ...pagination,
        small: width.value < 768
    }));
    const columnConfig = columnsFactory ? useTableColumns<TRecord>(columnsFactory) : null;
    const columns = columnConfig?.columns;
    const columnChecks = columnConfig?.columnChecks;
    const hasData = computed(() => data.value.length > 0);
    const cacheInfo = computed(() => {
        void cacheUpdateTrigger.value;
        if (!cache)
            return { total: 0, size: '0KB', hitRate: '0 avg hits' };
        return cache.getStats();
    });
    const handleError = createErrorHandler(onError, enableLog);
    const clearCache = (strategy: CacheInvalidationStrategy, context?: string): void => {
        if (!cache)
            return;
        let clearedCount = 0;
        switch (strategy) {
            case CacheInvalidationStrategy.CLEAR_ALL:
                cache.clear();
                logger.log(`清空所有缓存 - ${context || ''}`);
                break;
            case CacheInvalidationStrategy.CLEAR_CURRENT:
                clearedCount = cache.clearCurrentSearch(searchParams);
                logger.log(`清空当前搜索缓存 ${clearedCount} 条 - ${context || ''}`);
                break;
            case CacheInvalidationStrategy.CLEAR_PAGINATION:
                clearedCount = cache.clearPagination();
                logger.log(`清空分页缓存 ${clearedCount} 条 - ${context || ''}`);
                break;
            case CacheInvalidationStrategy.KEEP_ALL:
            default:
                logger.log(`保持缓存不变 - ${context || ''}`);
                break;
        }
        cacheUpdateTrigger.value++;
    };
    const fetchData = async (params?: Partial<TParams>, useCache = enableCache): Promise<ApiResponse<TRecord>> => {
        if (abortController) {
            abortController.abort();
        }
        const currentController = new AbortController();
        abortController = currentController;
        loadingState.value = 'loading';
        error.value = null;
        try {
            let requestParams = Object.assign({}, searchParams, {
                [pageKey]: pagination.current,
                [sizeKey]: pagination.size
            }, params || {}) as TParams;
            if (excludeParams.length > 0) {
                const filteredParams = { ...requestParams };
                excludeParams.forEach((key) => {
                    delete (filteredParams as Record<string, unknown>)[key];
                });
                requestParams = filteredParams as TParams;
            }
            if (useCache && cache) {
                const cachedItem = cache.get(requestParams);
                if (cachedItem) {
                    data.value = cachedItem.data;
                    updatePaginationFromResponse(pagination, cachedItem.response);
                    const paramsRecord = searchParams as Record<string, unknown>;
                    if (paramsRecord[pageKey] !== pagination.current) {
                        paramsRecord[pageKey] = pagination.current;
                    }
                    if (paramsRecord[sizeKey] !== pagination.size) {
                        paramsRecord[sizeKey] = pagination.size;
                    }
                    loadingState.value = 'success';
                    if (onCacheHit) {
                        onCacheHit(cachedItem.data, cachedItem.response);
                    }
                    logger.log(`缓存命中`);
                    return cachedItem.response;
                }
            }
            const response = await apiFn(requestParams);
            if (currentController.signal.aborted) {
                throw new Error('请求已取消');
            }
            const standardResponse = responseAdapter(response);
            let tableData = extractTableData(standardResponse);
            if (dataTransformer) {
                tableData = dataTransformer(tableData);
            }
            data.value = tableData;
            updatePaginationFromResponse(pagination, standardResponse);
            const paramsRecord = searchParams as Record<string, unknown>;
            if (paramsRecord[pageKey] !== pagination.current) {
                paramsRecord[pageKey] = pagination.current;
            }
            if (paramsRecord[sizeKey] !== pagination.size) {
                paramsRecord[sizeKey] = pagination.size;
            }
            if (useCache && cache) {
                cache.set(requestParams, tableData, standardResponse);
                cacheUpdateTrigger.value++;
                logger.log(`数据已缓存`);
            }
            loadingState.value = 'success';
            if (onSuccess) {
                onSuccess(tableData, standardResponse);
            }
            return standardResponse;
        }
        catch (err) {
            if (err instanceof Error && err.message === '请求已取消') {
                loadingState.value = 'idle';
                return { records: [], total: 0, current: 1, size: 10 };
            }
            loadingState.value = 'error';
            data.value = [];
            const tableError = handleError(err, '获取表格数据失败');
            throw tableError;
        }
        finally {
            if (abortController === currentController) {
                abortController = null;
            }
        }
    };
    const getData = async (params?: Partial<TParams>): Promise<ApiResponse<TRecord> | void> => {
        try {
            return await fetchData(params);
        }
        catch {
            return Promise.resolve();
        }
    };
    const getDataByPage = async (params?: Partial<TParams>): Promise<ApiResponse<TRecord> | void> => {
        pagination.current = 1;
        (searchParams as Record<string, unknown>)[pageKey] = 1;
        clearCache(CacheInvalidationStrategy.CLEAR_CURRENT, '搜索数据');
        try {
            return await fetchData(params, false);
        }
        catch {
            return Promise.resolve();
        }
    };
    const debouncedGetDataByPage = createSmartDebounce(getDataByPage, debounceTime);
    const resetSearchParams = async (): Promise<void> => {
        debouncedGetDataByPage.cancel();
        const paramsRecord = searchParams as Record<string, unknown>;
        const defaultPagination = {
            [pageKey]: 1,
            [sizeKey]: (paramsRecord[sizeKey] as number) || 10
        };
        Object.keys(searchParams).forEach((key) => {
            delete paramsRecord[key];
        });
        Object.assign(searchParams, apiParams || {}, defaultPagination);
        pagination.current = 1;
        pagination.size = defaultPagination[sizeKey] as number;
        error.value = null;
        clearCache(CacheInvalidationStrategy.CLEAR_ALL, '重置搜索');
        await getData();
        if (resetFormCallback) {
            await nextTick();
            resetFormCallback();
        }
    };
    let isCurrentChanging = false;
    const handleSizeChange = async (newSize: number): Promise<void> => {
        if (newSize <= 0)
            return;
        debouncedGetDataByPage.cancel();
        const paramsRecord = searchParams as Record<string, unknown>;
        pagination.size = newSize;
        pagination.current = 1;
        paramsRecord[sizeKey] = newSize;
        paramsRecord[pageKey] = 1;
        clearCache(CacheInvalidationStrategy.CLEAR_CURRENT, '分页大小变化');
        await getData();
    };
    const handleCurrentChange = async (newCurrent: number): Promise<void> => {
        if (newCurrent <= 0)
            return;
        if (isCurrentChanging) {
            return;
        }
        if (pagination.current === newCurrent) {
            logger.log('分页页码未变化，跳过请求');
            return;
        }
        try {
            isCurrentChanging = true;
            const paramsRecord = searchParams as Record<string, unknown>;
            pagination.current = newCurrent;
            if (paramsRecord[pageKey] !== newCurrent) {
                paramsRecord[pageKey] = newCurrent;
            }
            await getData();
        }
        finally {
            isCurrentChanging = false;
        }
    };
    const refreshCreate = async (): Promise<void> => {
        debouncedGetDataByPage.cancel();
        pagination.current = 1;
        (searchParams as Record<string, unknown>)[pageKey] = 1;
        clearCache(CacheInvalidationStrategy.CLEAR_PAGINATION, '新增数据');
        await getData();
    };
    const refreshUpdate = async (): Promise<void> => {
        clearCache(CacheInvalidationStrategy.CLEAR_CURRENT, '编辑数据');
        await getData();
    };
    const refreshRemove = async (): Promise<void> => {
        const { current } = pagination;
        clearCache(CacheInvalidationStrategy.CLEAR_CURRENT, '删除数据');
        await getData();
        if (data.value.length === 0 && current > 1) {
            pagination.current = current - 1;
            (searchParams as Record<string, unknown>)[pageKey] = current - 1;
            await getData();
        }
    };
    const refreshData = async (): Promise<void> => {
        debouncedGetDataByPage.cancel();
        clearCache(CacheInvalidationStrategy.CLEAR_ALL, '手动刷新');
        await getData();
    };
    const refreshSoft = async (): Promise<void> => {
        clearCache(CacheInvalidationStrategy.CLEAR_CURRENT, '软刷新');
        await getData();
    };
    const cancelRequest = (): void => {
        if (abortController) {
            abortController.abort();
        }
        debouncedGetDataByPage.cancel();
    };
    const clearData = (): void => {
        data.value = [];
        error.value = null;
        clearCache(CacheInvalidationStrategy.CLEAR_ALL, '清空数据');
    };
    const clearExpiredCache = (): number => {
        if (!cache)
            return 0;
        const cleanedCount = cache.cleanupExpired();
        if (cleanedCount > 0) {
            cacheUpdateTrigger.value++;
        }
        return cleanedCount;
    };
    if (enableCache && cache) {
        cacheCleanupTimer = setInterval(() => {
            const cleanedCount = cache.cleanupExpired();
            if (cleanedCount > 0) {
                logger.log(`自动清理 ${cleanedCount} 条过期缓存`);
                cacheUpdateTrigger.value++;
            }
        }, cacheTime / 2);
    }
    if (immediate) {
        onMounted(async () => {
            await getData();
        });
    }
    onUnmounted(() => {
        cancelRequest();
        if (cache) {
            cache.clear();
        }
        if (cacheCleanupTimer) {
            clearInterval(cacheCleanupTimer);
        }
    });
    return {
        data,
        loading: readonly(loading),
        error: readonly(error),
        isEmpty: computed(() => data.value.length === 0),
        hasData,
        pagination: readonly(pagination),
        paginationMobile: mobilePagination,
        handleSizeChange,
        handleCurrentChange,
        searchParams,
        resetSearchParams,
        fetchData: getData,
        getData: getDataByPage,
        getDataDebounced: debouncedGetDataByPage,
        clearData,
        refreshData,
        refreshSoft,
        refreshCreate,
        refreshUpdate,
        refreshRemove,
        cacheInfo,
        clearCache,
        clearExpiredCache,
        cancelRequest,
        ...(columnConfig && {
            columns,
            columnChecks,
            addColumn: columnConfig.addColumn,
            removeColumn: columnConfig.removeColumn,
            toggleColumn: columnConfig.toggleColumn,
            updateColumn: columnConfig.updateColumn,
            batchUpdateColumns: columnConfig.batchUpdateColumns,
            reorderColumns: columnConfig.reorderColumns,
            getColumnConfig: columnConfig.getColumnConfig,
            getAllColumns: columnConfig.getAllColumns,
            resetColumns: columnConfig.resetColumns
        })
    };
}
export { CacheInvalidationStrategy } from '../../utils/table/tableCache';
export type { ApiResponse, CacheItem } from '../../utils/table/tableCache';
export type { BaseRequestParams, TableError } from '../../utils/table/tableUtils';
