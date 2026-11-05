package com.example.orders.adapter.rest;

import com.example.orders.application.CalculateOrderUseCase;
import com.example.orders.domain.Order;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.Objects;

@RestController
public class CalculateOrderController {
    private final CalculateOrderUseCase useCase;
    private final OrderRestMapper mapper;

    public CalculateOrderController(CalculateOrderUseCase useCase, OrderRestMapper mapper) {
        this.useCase = Objects.requireNonNull(useCase, "useCase must not be null");
        this.mapper = Objects.requireNonNull(mapper, "mapper must not be null");
    }

    @PostMapping(value = "/api/orders/calculate", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public CalculateOrderResponse calculate(@RequestBody CalculateOrderRequest request) {
        Order order = mapper.toOrder(request);
        BigDecimal discount = mapper.toDiscount(request);
        BigDecimal total = useCase.calculateTotal(order);
        BigDecimal payable = useCase.calculatePayableAmount(order, discount);
        return mapper.toResponse(order, total, payable);
    }
}
