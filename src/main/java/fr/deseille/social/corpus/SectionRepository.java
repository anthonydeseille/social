package fr.deseille.social.corpus;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface SectionRepository extends JpaRepository<Section, Long> {

    Optional<Section> findBySourceId(String sourceId);

    /** Sections de premier niveau d'un texte, dans l'ordre du sommaire. */
    List<Section> findByTexteIdAndParentIsNullOrderByIntOrdreAsc(Long texteId);

    List<Section> findByParentIdOrderByIntOrdreAsc(Long parentId);

    /** Section et ses ancêtres, de la racine vers la section, en une requête récursive. */
    @Query(value = """
            with recursive chemin(id, parent_id, source_id, titre, etat, profondeur) as (
                select id, parent_id, source_id, titre, etat, 0 from section where id = :sectionId
                union all
                select s.id, s.parent_id, s.source_id, s.titre, s.etat, c.profondeur + 1
                from section s join chemin c on s.id = c.parent_id
            )
            select source_id as sourceId, titre, etat from chemin order by profondeur desc""",
            nativeQuery = true)
    List<ElementChemin> findChemin(Long sectionId);

    /** Projection d'une section du fil d'Ariane ({@code etat} brut, issu d'une requête native). */
    interface ElementChemin {
        String getSourceId();

        String getTitre();

        String getEtat();
    }
}
