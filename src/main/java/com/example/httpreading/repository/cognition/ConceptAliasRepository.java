package com.example.httpreading.repository.cognition;

import java.util.List;
import java.util.Optional;

import com.example.httpreading.domain.cognition.ConceptAlias;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ConceptAliasRepository extends JpaRepository<ConceptAlias, Long> {
    Optional<ConceptAlias> findFirstByAliasNameIgnoreCase(String aliasName);

    List<ConceptAlias> findByNormalizedAliasName(String normalizedAliasName);

    boolean existsByConceptIdAndNormalizedAliasName(Long conceptId, String normalizedAliasName);
}
