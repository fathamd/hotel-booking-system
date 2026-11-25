package com.rupeek.hotel_booking_system.service.onboarding;

import com.rupeek.hotel_booking_system.api.dto.onboarding.CreateOwnerRequest;
import com.rupeek.hotel_booking_system.api.dto.onboarding.CreatePropertiesRequest;
import com.rupeek.hotel_booking_system.api.dto.onboarding.CreatePropertyRequest;
import com.rupeek.hotel_booking_system.api.dto.onboarding.CreateRoomTypeRequest;
import com.rupeek.hotel_booking_system.api.dto.onboarding.CreateStandalonePropertyRequest;
import com.rupeek.hotel_booking_system.api.dto.onboarding.LocationRequest;
import com.rupeek.hotel_booking_system.api.dto.onboarding.OwnerDetailsResponse;
import com.rupeek.hotel_booking_system.api.dto.onboarding.OwnerResponse;
import com.rupeek.hotel_booking_system.api.dto.onboarding.PropertyDetailsResponse;
import com.rupeek.hotel_booking_system.api.dto.onboarding.PropertySummaryResponse;
import com.rupeek.hotel_booking_system.api.dto.onboarding.RoomTypeResponse;
import com.rupeek.hotel_booking_system.config.PropertyDefaults;
import com.rupeek.hotel_booking_system.domain.common.DomainPreconditions;
import com.rupeek.hotel_booking_system.domain.exception.DomainValidationException;
import com.rupeek.hotel_booking_system.domain.model.Location;
import com.rupeek.hotel_booking_system.domain.model.OwnerAccount;
import com.rupeek.hotel_booking_system.domain.model.Property;
import com.rupeek.hotel_booking_system.domain.model.RoomType;
import com.rupeek.hotel_booking_system.repository.OwnerAccountRepository;
import com.rupeek.hotel_booking_system.repository.PropertyRepository;
import com.rupeek.hotel_booking_system.repository.RoomTypeRepository;
import com.rupeek.hotel_booking_system.service.exception.DuplicateResourceException;
import com.rupeek.hotel_booking_system.service.exception.ResourceNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.List;

/**
 * Onboards owners, their properties and room types.
 * Standalone hotels and chains share one path: an owner account with one or many properties.
 * Field-level rules are enforced by the domain constructors as well as by request validation,
 * so the service stays correct when it is called from somewhere other than the REST layer.
 */
@Service
public class PropertyOnboardingService {

    private final OwnerAccountRepository ownerAccountRepository;
    private final PropertyRepository propertyRepository;
    private final RoomTypeRepository roomTypeRepository;
    private final RoomInventoryProvisioner roomInventoryProvisioner;
    private final PropertyDefaults propertyDefaults;

    public PropertyOnboardingService(OwnerAccountRepository ownerAccountRepository,
                                     PropertyRepository propertyRepository,
                                     RoomTypeRepository roomTypeRepository,
                                     RoomInventoryProvisioner roomInventoryProvisioner,
                                     PropertyDefaults propertyDefaults) {
        this.ownerAccountRepository = ownerAccountRepository;
        this.propertyRepository = propertyRepository;
        this.roomTypeRepository = roomTypeRepository;
        this.roomInventoryProvisioner = roomInventoryProvisioner;
        this.propertyDefaults = propertyDefaults;
    }

    @Transactional
    public OwnerResponse createOwner(CreateOwnerRequest request) {
        return OnboardingResponseMapper.toOwnerResponse(registerOwner(request));
    }

    @Transactional
    public PropertySummaryResponse addProperty(Long ownerId, CreatePropertyRequest request) {
        OwnerAccount owner = findOwner(ownerId);
        Property property = propertyRepository.save(newProperty(owner, request));
        return OnboardingResponseMapper.toPropertySummary(property);
    }

    /**
     * Adds every property or none: a single invalid entry rolls back the whole batch.
     */
    @Transactional
    public List<PropertySummaryResponse> addProperties(Long ownerId, CreatePropertiesRequest request) {
        DomainPreconditions.requireNonNull(request, "request");
        OwnerAccount owner = findOwner(ownerId);
        List<Property> properties = request.properties().stream()
                .map(propertyRequest -> newProperty(owner, propertyRequest))
                .toList();
        return propertyRepository.saveAll(properties).stream()
                .map(OnboardingResponseMapper::toPropertySummary)
                .toList();
    }

    @Transactional
    public OwnerDetailsResponse onboardStandaloneProperty(CreateStandalonePropertyRequest request) {
        DomainPreconditions.requireNonNull(request, "request");
        OwnerAccount owner = registerOwner(request.owner());
        Property property = propertyRepository.save(newProperty(owner, request.property()));
        return OnboardingResponseMapper.toOwnerDetails(owner, List.of(property));
    }

    @Transactional
    public RoomTypeResponse addRoomType(Long propertyId, CreateRoomTypeRequest request) {
        DomainPreconditions.requireNonNull(request, "request");
        Property property = findProperty(propertyId);
        RoomType roomType = new RoomType(property, request.name(), request.description(),
                requireValue(request.maxGuests(), "maxGuests"),
                requireValue(request.totalInventory(), "totalInventory"),
                request.basePrice());
        if (roomTypeRepository.existsByPropertyIdAndNameIgnoreCase(propertyId, roomType.getName())) {
            throw new DuplicateResourceException("Room type '" + roomType.getName()
                    + "' already exists for property " + propertyId);
        }
        RoomType saved = roomTypeRepository.save(roomType);
        roomInventoryProvisioner.provision(saved);
        return OnboardingResponseMapper.toRoomTypeResponse(saved);
    }

    @Transactional(readOnly = true)
    public OwnerDetailsResponse getOwner(Long ownerId) {
        OwnerAccount owner = findOwner(ownerId);
        return OnboardingResponseMapper.toOwnerDetails(owner, propertyRepository.findByOwnerIdOrderByNameAsc(ownerId));
    }

    @Transactional(readOnly = true)
    public PropertyDetailsResponse getProperty(Long propertyId) {
        Property property = findProperty(propertyId);
        return OnboardingResponseMapper.toPropertyDetails(property,
                roomTypeRepository.findByPropertyIdOrderByBasePriceAsc(propertyId));
    }

    private OwnerAccount registerOwner(CreateOwnerRequest request) {
        DomainPreconditions.requireNonNull(request, "owner");
        OwnerAccount owner = new OwnerAccount(request.name(), request.email(), request.phone());
        if (ownerAccountRepository.existsByEmailIgnoreCase(owner.getEmail())) {
            throw new DuplicateResourceException("An owner account with email " + owner.getEmail() + " already exists");
        }
        return ownerAccountRepository.save(owner);
    }

    private Property newProperty(OwnerAccount owner, CreatePropertyRequest request) {
        DomainPreconditions.requireNonNull(request, "property");
        return new Property(owner, request.name(), toLocation(request.location()),
                requireValue(request.starRating(), "starRating"),
                request.amenities(), request.cancellationPolicy(), timeZone(request.timeZone()));
    }

    private ZoneId timeZone(String requested) {
        if (requested == null || requested.isBlank()) {
            return propertyDefaults.defaultTimeZone();
        }
        try {
            return ZoneId.of(requested.strip());
        } catch (DateTimeException ex) {
            throw new DomainValidationException("timeZone '" + requested + "' is not a valid IANA time zone");
        }
    }

    private static Location toLocation(LocationRequest request) {
        DomainPreconditions.requireNonNull(request, "location");
        return new Location(request.addressLine(), request.locality(), request.city());
    }

    private static int requireValue(Integer value, String fieldName) {
        return DomainPreconditions.requireNonNull(value, fieldName);
    }

    private OwnerAccount findOwner(Long ownerId) {
        return ownerAccountRepository.findById(ownerId)
                .orElseThrow(() -> new ResourceNotFoundException("Owner account", ownerId));
    }

    private Property findProperty(Long propertyId) {
        return propertyRepository.findById(propertyId)
                .orElseThrow(() -> new ResourceNotFoundException("Property", propertyId));
    }
}
