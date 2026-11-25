package com.rupeek.hotel_booking_system.api.controller;

import com.rupeek.hotel_booking_system.api.dto.onboarding.CreateRoomTypeRequest;
import com.rupeek.hotel_booking_system.api.dto.onboarding.CreateStandalonePropertyRequest;
import com.rupeek.hotel_booking_system.api.dto.onboarding.OwnerDetailsResponse;
import com.rupeek.hotel_booking_system.api.dto.onboarding.PropertyDetailsResponse;
import com.rupeek.hotel_booking_system.api.dto.onboarding.RoomTypeResponse;
import com.rupeek.hotel_booking_system.service.onboarding.PropertyOnboardingService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;

@RestController
@RequestMapping(PropertyController.BASE_PATH)
public class PropertyController {

    static final String BASE_PATH = "/api/v1/properties";

    private final PropertyOnboardingService onboardingService;

    public PropertyController(PropertyOnboardingService onboardingService) {
        this.onboardingService = onboardingService;
    }

    /**
     * Convenience endpoint for an independent hotel: creates its owner account and the property together.
     */
    @PostMapping("/standalone")
    public ResponseEntity<OwnerDetailsResponse> onboardStandaloneProperty(
            @Valid @RequestBody CreateStandalonePropertyRequest request) {
        OwnerDetailsResponse owner = onboardingService.onboardStandaloneProperty(request);
        return ResponseEntity.created(URI.create(OwnerController.BASE_PATH + "/" + owner.id())).body(owner);
    }

    @GetMapping("/{propertyId}")
    public PropertyDetailsResponse getProperty(@PathVariable @Positive Long propertyId) {
        return onboardingService.getProperty(propertyId);
    }

    @PostMapping("/{propertyId}/room-types")
    public ResponseEntity<RoomTypeResponse> addRoomType(@PathVariable @Positive Long propertyId,
                                                        @Valid @RequestBody CreateRoomTypeRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(onboardingService.addRoomType(propertyId, request));
    }
}
