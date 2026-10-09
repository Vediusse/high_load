package ru.itmo.highload.catering.order.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.UUID;

@Entity
@Table(
        name = "order_line",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_order_line_order_dish",
                columnNames = {"order_id", "dish_id"}))
public class OrderLine {

    @Id
    private UUID id;

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "order_id", nullable = false)
    private CorporateOrder order;

    @NotNull
    @Column(name = "dish_id", nullable = false)
    private UUID dishId;

    @Size(max = 200)
    @Column(name = "dish_name_snapshot", length = 200)
    private String dishNameSnapshot;

    @Min(1)
    @Max(100000)
    @Column(nullable = false)
    private int quantity;

    @DecimalMin(value = "0.00", inclusive = false)
    @Digits(integer = 17, fraction = 2)
    @Column(name = "unit_price_snapshot", precision = 19, scale = 2)
    private BigDecimal unitPriceSnapshot;

    protected OrderLine() {
    }

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

    public UUID getId() {
        return id;
    }

    public UUID getDishId() {
        return dishId;
    }

    public String getDishNameSnapshot() {
        return dishNameSnapshot;
    }

    public int getQuantity() {
        return quantity;
    }

    public BigDecimal getUnitPriceSnapshot() {
        return unitPriceSnapshot;
    }

    public BigDecimal getLineAmount() {
        return unitPriceSnapshot == null ? null : unitPriceSnapshot.multiply(BigDecimal.valueOf(quantity));
    }

    private static void validateQuantity(int quantity) {
        if (quantity < 1 || quantity > 100000) {
            throw new IllegalArgumentException("Количество порций должно быть от 1 до 100000");
        }
    }
}
