package likelion.repository;

import likelion.domain.entity.RealEstate;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RealEstateRepository extends JpaRepository<RealEstate, Long> {
}