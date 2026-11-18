package ru.itmo.highload.common.domain;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class PageQueryTest {
    @Test void boundsAndOffsetAreIndependentOfPersistence() {
        var page = PageQuery.of(3, 20).withSort("createdAt DESC,id DESC");
        assertThat(page.getOffset()).isEqualTo(60);
        assertThat(page.getPageNumber()).isEqualTo(3);
        assertThat(page.getPageSize()).isEqualTo(20);
        assertThat(page.sort()).isEqualTo("createdAt DESC,id DESC");
        assertThatThrownBy(() -> PageQuery.of(-1, 20)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PageQuery.of(0, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PageQuery.of(0, 51)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void pageResultDoesNotExposeMutableStorageCollections() {
        var original = new ArrayList<>(List.of("first"));
        var page = new PageResult<>(original, 1, 1, 3, true);
        original.clear();
        assertThat(page.getContent()).containsExactly("first");
        assertThat(page.getNumber()).isEqualTo(1);
        assertThat(page.getSize()).isEqualTo(1);
        assertThat(page.getTotalElements()).isEqualTo(3);
        assertThat(page.hasNext()).isTrue();
        assertThatThrownBy(() -> page.items().clear()).isInstanceOf(UnsupportedOperationException.class);
    }
}
