package com.rupeek.hotel_booking_system.api.dto.search;

import java.time.LocalDate;
import java.util.List;

public record PropertySearchResponse(LocalDate checkIn,
                                     LocalDate checkOut,
                                     long nights,
                                     int guests,
                                     int rooms,
                                     int resultCount,
                                     List<PropertySearchResult> results) {
}
