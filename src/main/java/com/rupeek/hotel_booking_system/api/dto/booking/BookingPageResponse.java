package com.rupeek.hotel_booking_system.api.dto.booking;

import java.util.List;

public record BookingPageResponse(List<BookingResponse> content,
                                  int page,
                                  int size,
                                  long totalElements,
                                  int totalPages) {
}
