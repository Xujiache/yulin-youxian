package com.xianda.freshdelivery;

import com.xianda.freshdelivery.common.PageResult;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class PageResultTests {
    @Test
    void capsPageSizeAndKeepsTotal() {
        PageResult<Integer> result = PageResult.page(List.of(1, 2, 3, 4, 5), 2, 2);
        assertThat(result.items()).containsExactly(3, 4);
        assertThat(result.total()).isEqualTo(5);
        assertThat(result.page()).isEqualTo(2);
        assertThat(result.pageSize()).isEqualTo(2);
    }
}
