package com.example.orders.adapter.rest;

final class InvalidCalculationRequestException extends RuntimeException {
    InvalidCalculationRequestException(String message) {
        super(message);
    }
}
