package com.rupeek.hotel_booking_system.domain.model;

import com.rupeek.hotel_booking_system.domain.common.DomainPreconditions;
import com.rupeek.hotel_booking_system.domain.enums.Amenity;
import com.rupeek.hotel_booking_system.domain.enums.CancellationPolicyType;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

@Entity
@Table(name = "property", indexes = {
        @Index(name = "idx_property_search_city_locality", columnList = "search_city, search_locality"),
        @Index(name = "idx_property_owner", columnList = "owner_account_id")
})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Property extends BaseEntity {

    public static final int MIN_STAR_RATING = 1;
    public static final int MAX_STAR_RATING = 5;
    public static final int NAME_MAX = 150;
    public static final ZoneId DEFAULT_TIME_ZONE = ZoneOffset.UTC;
    public static final int TIME_ZONE_MAX = 50;

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "owner_account_id", nullable = false,
            foreignKey = @ForeignKey(name = "fk_property_owner_account"))
    private OwnerAccount owner;

    @NotBlank
    @Size(max = NAME_MAX)
    @Column(nullable = false, length = NAME_MAX)
    private String name;

    @Valid
    @NotNull
    @Embedded
    private Location location;

    /**
     * Lower-cased copies of city and locality. Equality lookups on these use the index,
     * whereas {@code lower(city) = ?} would force a full scan.
     */
    @Getter(AccessLevel.NONE)
    @Column(name = "search_city", nullable = false, length = 100)
    private String searchCity;

    @Getter(AccessLevel.NONE)
    @Column(name = "search_locality", nullable = false, length = 100)
    private String searchLocality;

    @Min(MIN_STAR_RATING)
    @Max(MAX_STAR_RATING)
    @Column(name = "star_rating", nullable = false)
    private int starRating;

    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(name = "property_amenity",
            joinColumns = @JoinColumn(name = "property_id"),
            foreignKey = @ForeignKey(name = "fk_property_amenity_property"))
    @Enumerated(EnumType.STRING)
    @Column(name = "amenity", nullable = false, length = 40)
    private Set<Amenity> amenities = new HashSet<>();

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "cancellation_policy", nullable = false, length = 30)
    private CancellationPolicyType cancellationPolicyType;

    /**
     * IANA zone of the property. Check-in times, refund windows and "today" are evaluated in this zone.
     */
    @Getter(AccessLevel.NONE)
    @NotBlank
    @Column(name = "time_zone", nullable = false, length = TIME_ZONE_MAX)
    private String timeZone;

    public Property(OwnerAccount owner,
                    String name,
                    Location location,
                    int starRating,
                    Collection<Amenity> amenities,
                    CancellationPolicyType cancellationPolicyType) {
        this(owner, name, location, starRating, amenities, cancellationPolicyType, DEFAULT_TIME_ZONE);
    }

    public Property(OwnerAccount owner,
                    String name,
                    Location location,
                    int starRating,
                    Collection<Amenity> amenities,
                    CancellationPolicyType cancellationPolicyType,
                    ZoneId timeZone) {
        this.timeZone = DomainPreconditions.requireNonNull(timeZone, "timeZone").getId();
        this.owner = DomainPreconditions.requireNonNull(owner, "owner");
        this.name = DomainPreconditions.requireText(name, "property name", NAME_MAX);
        this.location = DomainPreconditions.requireNonNull(location, "location");
        this.searchCity = searchKey(location.city());
        this.searchLocality = searchKey(location.locality());
        this.starRating = DomainPreconditions.requireInRange(starRating, MIN_STAR_RATING, MAX_STAR_RATING, "starRating");
        this.cancellationPolicyType = DomainPreconditions.requireNonNull(cancellationPolicyType, "cancellationPolicyType");
        replaceAmenities(amenities);
    }

    public ZoneId getTimeZone() {
        return ZoneId.of(timeZone);
    }

    /**
     * The current calendar date at the property, which can differ from the server's date.
     */
    public LocalDate today(Clock clock) {
        return LocalDate.now(clock.withZone(getTimeZone()));
    }

    public Set<Amenity> getAmenities() {
        return Collections.unmodifiableSet(amenities);
    }

    public void replaceAmenities(Collection<Amenity> newAmenities) {
        amenities.clear();
        if (newAmenities != null && !newAmenities.isEmpty()) {
            amenities.addAll(EnumSet.copyOf(newAmenities));
        }
    }

    public boolean offersAll(Collection<Amenity> requiredAmenities) {
        return requiredAmenities == null || amenities.containsAll(requiredAmenities);
    }

    /**
     * Normalises a city or locality for case- and whitespace-insensitive lookups.
     */
    public static String searchKey(String value) {
        return value == null ? null : value.strip().toLowerCase(Locale.ROOT);
    }
}
