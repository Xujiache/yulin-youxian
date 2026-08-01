import { computed, type Ref } from 'vue';
interface TableHeightOptions {
    showTableHeader: Ref<boolean>;
    paginationHeight: Ref<number>;
    tableHeaderHeight: Ref<number>;
    paginationSpacing: Ref<number>;
}
class TableHeightCalculator {
    private static readonly DEFAULT_TABLE_HEADER_HEIGHT = 44;
    private static readonly TABLE_HEADER_SPACING = 12;
    constructor(private options: TableHeightOptions) { }
    calculate(): {
        height: string;
    } {
        const offset = this.calculateOffset();
        return {
            height: offset === 0 ? '100%' : `calc(100% - ${offset}px)`
        };
    }
    private calculateOffset(): number {
        if (!this.options.showTableHeader.value) {
            return this.calculatePaginationOffset();
        }
        const headerHeight = this.getHeaderHeight();
        const paginationOffset = this.calculatePaginationOffset();
        return headerHeight + paginationOffset + TableHeightCalculator.TABLE_HEADER_SPACING;
    }
    private getHeaderHeight(): number {
        return this.options.tableHeaderHeight.value || TableHeightCalculator.DEFAULT_TABLE_HEADER_HEIGHT;
    }
    private calculatePaginationOffset(): number {
        const { paginationHeight, paginationSpacing } = this.options;
        return paginationHeight.value === 0 ? 0 : paginationHeight.value + paginationSpacing.value;
    }
}
export function useTableHeight(options: TableHeightOptions) {
    const containerHeight = computed(() => {
        const calculator = new TableHeightCalculator(options);
        return calculator.calculate();
    });
    return {
        containerHeight
    };
}
