export type SearchComponentType = 'input' | 'select' | 'radio' | 'checkbox' | 'date' | 'datetime' | 'daterange' | 'datetimerange' | 'month' | 'monthrange' | 'year' | 'yearrange' | 'week' | 'time' | 'timerange';
export interface SearchChangeParams {
    prop: string;
    val: unknown;
}
export interface ColumnOption<T = any> {
    type?: 'selection' | 'expand' | 'index' | 'globalIndex';
    prop?: string;
    label?: string;
    width?: string | number;
    minWidth?: string | number;
    fixed?: boolean | 'left' | 'right';
    sortable?: boolean;
    filters?: any[];
    filterMethod?: (value: any, row: any) => boolean;
    filterPlacement?: string;
    disabled?: boolean;
    visible?: boolean;
    checked?: boolean;
    formatter?: (row: T) => any;
    useSlot?: boolean;
    slotName?: string;
    useHeaderSlot?: boolean;
    headerSlotName?: string;
    [key: string]: any;
}
export interface PaginationConfig {
    currentPage: number;
    pageSize: number;
    total: number;
    pageSizes?: number[];
    layout?: string;
    small?: boolean;
}
export interface FormRule {
    required?: boolean;
    message?: string;
    trigger?: string | string[];
    min?: number;
    max?: number;
    pattern?: RegExp;
    validator?: (rule: any, value: any, callback: any) => void;
}
export interface DialogConfig {
    title: string;
    visible: boolean;
    width?: string | number;
    closeOnClickModal?: boolean;
    closeOnPressEscape?: boolean;
    showClose?: boolean;
    lockScroll?: boolean;
    modal?: boolean;
    customClass?: string;
}
