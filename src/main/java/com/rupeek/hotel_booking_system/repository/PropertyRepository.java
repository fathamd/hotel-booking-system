package com.rupeek.hotel_booking_system.repository;

import com.rupeek.hotel_booking_system.domain.model.Property;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Transactional(readOnly = true)
public interface PropertyRepository extends JpaRepository<Property, Long> {

    List<Property> findByOwnerIdOrderByNameAsc(Long ownerId);

    Optional<Property> findByIdAndOwnerId(Long id, Long ownerId);

    /**
     * Case- and whitespace-insensitive city lookup that uses the search-key index.
     */
    default List<Property> findByCity(String city) {
        return findBySearchCity(Property.searchKey(city));
    }

    default List<Property> findByCityAndLocality(String city, String locality) {
        return findBySearchCityAndSearchLocality(Property.searchKey(city), Property.searchKey(locality));
    }

    @Query("""
            select p from Property p
            where p.searchCity = :searchCity
            order by p.starRating desc, p.name asc
            """)
    List<Property> findBySearchCity(@Param("searchCity") String searchCity);

    @Query("""
            select p from Property p
            where p.searchCity = :searchCity and p.searchLocality = :searchLocality
            order by p.starRating desc, p.name asc
            """)
    List<Property> findBySearchCityAndSearchLocality(@Param("searchCity") String searchCity,
                                                     @Param("searchLocality") String searchLocality);
}
