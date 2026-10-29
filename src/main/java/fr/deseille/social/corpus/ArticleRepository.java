package fr.deseille.social.corpus;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface ArticleRepository extends JpaRepository<Article, Long> {

    Optional<Article> findBySourceId(String sourceId);

    List<Article> findBySourceIdIn(Collection<String> sourceIds);

    /** États des articles déjà en base parmi les identifiants de version donnés (préchargement par chunk). */
    @Query("""
            select new fr.deseille.social.corpus.EtatArticle(
                a.sourceId, a.cid, a.etat, a.contenuHash, a.metaHash, a.disparuLe)
            from Article a where a.sourceId in :sourceIds""")
    List<EtatArticle> findEtatsBySourceIdIn(Collection<String> sourceIds);

    /** Parmi les cid donnés, ceux qui ont déjà au moins une version en base. */
    @Query("select distinct a.cid from Article a where a.cid in :cids")
    Set<String> findCidsConnus(Collection<String> cids);

    /** États des articles d'un texte encore présents dans le dump (détection des disparitions). */
    @Query("""
            select new fr.deseille.social.corpus.EtatArticle(
                a.sourceId, a.cid, a.etat, a.contenuHash, a.metaHash, a.disparuLe)
            from Article a where a.texte.id = :texteId and a.disparuLe is null""")
    List<EtatArticle> findEtatsPresentsByTexteId(Long texteId);

    @Modifying
    @Query("update Article a set a.disparuLe = :instant, a.majLe = :instant where a.sourceId in :sourceIds")
    int marquerDisparus(Collection<String> sourceIds, Instant instant);
}
