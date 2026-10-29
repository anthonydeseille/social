package fr.deseille.social.corpus;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SectionRepository extends JpaRepository<Section, Long> {

    Optional<Section> findBySourceId(String sourceId);

    /** Sections de premier niveau d'un texte, dans l'ordre du sommaire. */
    List<Section> findByTexteIdAndParentIsNullOrderByIntOrdreAsc(Long texteId);

    List<Section> findByParentIdOrderByIntOrdreAsc(Long parentId);
}
