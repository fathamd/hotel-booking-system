package com.rupeek.hotel_booking_system.repository;

import com.rupeek.hotel_booking_system.domain.model.OwnerAccount;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Transactional(readOnly = true)
public interface OwnerAccountRepository extends JpaRepository<OwnerAccount, Long> {

    Optional<OwnerAccount> findByEmailIgnoreCase(String email);

    boolean existsByEmailIgnoreCase(String email);
}
