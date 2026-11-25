package com.rupeek.hotel_booking_system.domain.model;

import com.rupeek.hotel_booking_system.domain.common.DomainPreconditions;
import com.rupeek.hotel_booking_system.domain.exception.DomainValidationException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * An account that owns one or more properties.
 * An independent hotel is an owner account with a single property and a hotel chain is
 * an owner account with many. Both use this same model and the same code paths.
 * Properties reference their owner (many-to-one), which keeps the graph acyclic;
 * an owner's portfolio is retrieved through the property repository.
 */
@Entity
@Table(name = "owner_account",
        uniqueConstraints = @UniqueConstraint(name = "uk_owner_account_email", columnNames = "email"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OwnerAccount extends BaseEntity {

    public static final int NAME_MAX = 150;
    public static final int EMAIL_MAX = 254;
    public static final int PHONE_MAX = 20;

    @NotBlank
    @Size(max = NAME_MAX)
    @Column(nullable = false, length = NAME_MAX)
    private String name;

    @NotBlank
    @Email
    @Size(max = EMAIL_MAX)
    @Column(nullable = false, length = EMAIL_MAX)
    private String email;

    @Size(max = PHONE_MAX)
    @Column(length = PHONE_MAX)
    private String phone;

    public OwnerAccount(String name, String email, String phone) {
        this.name = DomainPreconditions.requireText(name, "owner name", NAME_MAX);
        this.email = normalizeEmail(email);
        this.phone = DomainPreconditions.optionalText(phone, "owner phone", PHONE_MAX);
    }

    private static String normalizeEmail(String email) {
        String value = DomainPreconditions.requireText(email, "owner email", EMAIL_MAX).toLowerCase();
        if (!value.contains("@")) {
            throw new DomainValidationException("owner email must be a valid email address");
        }
        return value;
    }
}
