package com.rupeek.hotel_booking_system.api.controller;

import com.rupeek.hotel_booking_system.api.dto.onboarding.CreateOwnerRequest;
import com.rupeek.hotel_booking_system.api.dto.onboarding.CreatePropertiesRequest;
import com.rupeek.hotel_booking_system.api.dto.onboarding.CreatePropertyRequest;
import com.rupeek.hotel_booking_system.api.dto.onboarding.OwnerDetailsResponse;
import com.rupeek.hotel_booking_system.api.dto.onboarding.OwnerResponse;
import com.rupeek.hotel_booking_system.api.dto.onboarding.PropertySummaryResponse;
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
import java.util.List;

@RestController
@RequestMapping(OwnerController.BASE_PATH)
public class OwnerController {

    static final String BASE_PATH = "/api/v1/owners";

    private final PropertyOnboardingService onboardingService;

    public OwnerController(PropertyOnboardingService onboardingService) {
        this.onboardingService = onboardingService;
    }

    @PostMapping
    public ResponseEntity<OwnerResponse> createOwner(@Valid @RequestBody CreateOwnerRequest request) {
        OwnerResponse owner = onboardingService.createOwner(request);
        return ResponseEntity.created(URI.create(BASE_PATH + "/" + owner.id())).body(owner);
    }

    @GetMapping("/{ownerId}")
    public OwnerDetailsResponse getOwner(@PathVariable @Positive Long ownerId) {
        return onboardingService.getOwner(ownerId);
    }

    @PostMapping("/{ownerId}/properties")
    public ResponseEntity<PropertySummaryResponse> addProperty(@PathVariable @Positive Long ownerId,
                                                               @Valid @RequestBody CreatePropertyRequest request) {
        PropertySummaryResponse property = onboardingService.addProperty(ownerId, request);
        return ResponseEntity.created(URI.create(PropertyController.BASE_PATH + "/" + property.id())).body(property);
    }

    @PostMapping("/{ownerId}/properties/batch")
    public ResponseEntity<List<PropertySummaryResponse>> addProperties(@PathVariable @Positive Long ownerId,
                                                                       @Valid @RequestBody CreatePropertiesRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(onboardingService.addProperties(ownerId, request));
    }
}
