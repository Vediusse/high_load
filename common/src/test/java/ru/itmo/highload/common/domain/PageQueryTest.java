package ru.itmo.highload.common.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class PageQueryTest {
    @Test
    void boundsAndOffsetAreIndependentOfPersistence() {
        var page = PageQuery.of(3, 20).withSort("createdAt DESC,id DESC");
        assertThat(page.offset()).isEqualTo(60);
        assertThat(page.page()).isEqualTo(3);
        assertThat(page.size()).isEqualTo(20);
        assertThat(page.sort()).isEqualTo("createdAt DESC,id DESC");
        assertThatThrownBy(() -> PageQuery.of(-1, 20)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PageQuery.of(0, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PageQuery.of(0, 51)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void pageResultDoesNotExposeMutableStorageCollections() {
        var original = new ArrayList<>(List.of("first"));
        var page = new PageResult<>(original, 1, 1, 3, true);
        original.clear();
        assertThat(page.items()).containsExactly("first");
        assertThat(page.page()).isEqualTo(1);
        assertThat(page.size()).isEqualTo(1);
        assertThat(page.total()).isEqualTo(3);
        assertThat(page.hasNext()).isTrue();
        assertThatThrownBy(() -> page.items().clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
