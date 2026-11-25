package com.rupeek.hotel_booking_system.repository;

import com.rupeek.hotel_booking_system.domain.model.RoomType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Transactional(readOnly = true)
public interface RoomTypeRepository extends JpaRepository<RoomType, Long>, JpaSpecificationExecutor<RoomType> {

    List<RoomType> findByPropertyIdOrderByBasePriceAsc(Long propertyId);

    Optional<RoomType> findByIdAndPropertyId(Long id, Long propertyId);

    boolean existsByPropertyIdAndNameIgnoreCase(Long propertyId, String name);

    @Query("select rt.id from RoomType rt order by rt.id")
    List<Long> findAllIds();

    /**
     * Loads room types together with their property in one query, avoiding N+1 selects in search.
     */
    @Query("""
            select rt from RoomType rt
            join fetch rt.property p
            where p.id in :propertyIds
            order by p.id, rt.basePrice
            """)
    List<RoomType> findWithPropertyByPropertyIdIn(@Param("propertyIds") Collection<Long> propertyIds);
}
