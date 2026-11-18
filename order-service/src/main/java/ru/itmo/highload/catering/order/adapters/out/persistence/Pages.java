package ru.itmo.highload.catering.order.adapters.out.persistence;

import java.util.*;
import org.springframework.data.domain.*;
import ru.itmo.highload.common.domain.*;
public final class Pages {
    private Pages() { }
    public static Pageable request(PageQuery query) {
        List<Sort.Order> orders=new ArrayList<>();
        for (String field:query.sort().split(",")) {
            String[] bits=field.trim().split("\\s+");
            orders.add(bits.length>1 && bits[1].equalsIgnoreCase("DESC") ? Sort.Order.desc(bits[0]) : Sort.Order.asc(bits[0]));
        }
        return PageRequest.of(query.page(),query.size(),Sort.by(orders));
    }
    public static <T> PageResult<T> result(Slice<T> page) {
        return new PageResult<>(page.getContent(),page.getNumber(),page.getSize(),page instanceof Page<T> full ? full.getTotalElements() : -1,page.hasNext());
    }
}
