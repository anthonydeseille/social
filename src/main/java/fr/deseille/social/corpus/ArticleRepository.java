package fr.deseille.social.corpus;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface ArticleRepository extends JpaRepository<Article, Long> {

    Optional<Article> findBySourceId(String sourceId);

    boolean existsByCid(String cid);

    List<Article> findByTexteId(Long texteId);

    /** Identifiants de version présents en base pour un texte (détection des disparitions). */
    @Query("select a.sourceId from Article a where a.texte.id = :texteId")
    List<String> findSourceIdsByTexteId(Long texteId);
}
