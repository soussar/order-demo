package com.example.orders;

import com.example.orders.application.CalculateOrderUseCase;
import com.example.orders.adapter.rest.OrderRestMapper;
import com.example.orders.domain.OrderService;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
public class OrdersApplication {
    @Bean
    OrderRestMapper orderRestMapper(OrderService orderService) {
        return new OrderRestMapper(orderService);
    }

    @Bean
    OrderService orderService() {
        return new OrderService();
    }

    @Bean
    CalculateOrderUseCase calculateOrderUseCase(OrderService orderService) {
        return new CalculateOrderUseCase(orderService);
    }

    public static void main(String[] args) {
        SpringApplication.run(OrdersApplication.class, args);
    }
}
