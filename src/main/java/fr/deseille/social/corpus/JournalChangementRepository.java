package fr.deseille.social.corpus;

import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface JournalChangementRepository extends JpaRepository<JournalChangement, Long> {

    /** Événements de toutes les versions d'un article, du plus ancien au plus récent. */
    List<JournalChangement> findByArticleSourceIdInOrderByDetecteLeAscIdAsc(Collection<String> articleSourceIds);
}
