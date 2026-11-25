package com.rupeek.hotel_booking_system.service.exception;

public class ResourceNotFoundException extends RuntimeException {

    public ResourceNotFoundException(String resource, Object id) {
        this(resource, "id", id);
    }

    public ResourceNotFoundException(String resource, String field, Object value) {
        super(resource + " with " + field + " " + value + " was not found");
    }
}
