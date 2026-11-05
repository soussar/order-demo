package com.example.orders.adapter.rest;

public record CalculateOrderResponse(String currency, String total, String payableAmount) {
}
