package com.rupeek.hotel_booking_system.api.controller;

import com.rupeek.hotel_booking_system.api.dto.search.PropertySearchRequest;
import com.rupeek.hotel_booking_system.api.dto.search.PropertySearchResponse;
import com.rupeek.hotel_booking_system.service.search.PropertySearchService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(PropertyController.BASE_PATH)
public class PropertySearchController {

    private final PropertySearchService searchService;

    public PropertySearchController(PropertySearchService searchService) {
        this.searchService = searchService;
    }

    /**
     * Example: {@code GET /api/v1/properties/search?city=Bengaluru&checkIn=2026-12-20&checkOut=2026-12-23
     * &guests=2&minPrice=1000&maxPrice=5000&amenities=WIFI,SWIMMING_POOL&minStarRating=4}
     */
    @GetMapping("/search")
    public PropertySearchResponse search(@Valid @ModelAttribute PropertySearchRequest request) {
        return searchService.search(request);
    }
}
