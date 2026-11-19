package ru.itmo.highload.catering.order.domain.model;

import java.math.BigDecimal;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OrderLine {

    private UUID id;

    @Getter(AccessLevel.NONE)
    private CorporateOrder order;

    private UUID dishId;

    private String dishNameSnapshot;

    private int quantity;

    private BigDecimal unitPriceSnapshot;

    OrderLine(CorporateOrder order, UUID dishId, int quantity) {
        if (order == null) {
            throw new IllegalArgumentException("Заказ обязателен");
        }
        if (dishId == null) {
            throw new IllegalArgumentException("Блюдо обязательно");
        }
        validateQuantity(quantity);
        this.id = UUID.randomUUID();
        this.order = order;
        this.dishId = dishId;
        this.quantity = quantity;
    }

    void updateQuantity(int quantity) {
        validateQuantity(quantity);
        this.quantity = quantity;
    }

    void captureSnapshot(String dishName, BigDecimal unitPrice) {
        if (dishName == null || dishName.isBlank() || dishName.trim().length() > 200) {
            throw new IllegalArgumentException("Название блюда для снимка некорректно");
        }
        if (unitPrice == null || unitPrice.signum() <= 0 || unitPrice.scale() > 2) {
            throw new IllegalArgumentException("Цена блюда для снимка должна быть положительной");
        }
        this.dishNameSnapshot = dishName.trim();
        this.unitPriceSnapshot = unitPrice;
    }

    public BigDecimal getLineAmount() {
        return unitPriceSnapshot == null
                ? null
                : unitPriceSnapshot.multiply(BigDecimal.valueOf(quantity));
    }

    private static void validateQuantity(int quantity) {
        if (quantity < 1 || quantity > 100000) {
            throw new IllegalArgumentException("Количество порций должно быть от 1 до 100000");
        }
    }
}
