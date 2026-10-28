package fr.deseille.social.ingestion;

import fr.deseille.social.corpus.Article;
import fr.deseille.social.corpus.ArticleRepository;
import fr.deseille.social.corpus.JournalChangement;
import fr.deseille.social.corpus.JournalChangementRepository;
import fr.deseille.social.corpus.TexteRepository;
import fr.deseille.social.corpus.TypeChangement;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Détecte les articles présents en base pour un texte mais absents du dump courant, et les
 * consigne au journal en {@link TypeChangement#DISPARITION}. Les articles ne sont jamais
 * supprimés (historisation) : seule une entrée de journal est créée.
 */
@Service
public class DetecteurDisparition {

    private static final Logger log = LoggerFactory.getLogger(DetecteurDisparition.class);

    private final ArticleRepository articleRepository;
    private final JournalChangementRepository journalRepository;
    private final TexteRepository texteRepository;

    public DetecteurDisparition(ArticleRepository articleRepository,
                                JournalChangementRepository journalRepository,
                                TexteRepository texteRepository) {
        this.articleRepository = articleRepository;
        this.journalRepository = journalRepository;
        this.texteRepository = texteRepository;
    }

    @Transactional
    public int marquerDisparitions(Long texteId, Collection<String> sourceIdsVus,
                                   Long executionId, String ref) {
        if (texteId == null) {
            return 0;
        }
        Set<String> vus = new HashSet<>(sourceIdsVus);
        List<String> enBase = articleRepository.findSourceIdsByTexteId(texteId);
        int disparus = 0;
        for (String sourceId : enBase) {
            if (vus.contains(sourceId)) {
                continue;
            }
            Article article = articleRepository.findBySourceId(sourceId).orElseThrow();
            JournalChangement entree = new JournalChangement(
                    sourceId, texteRepository.getReferenceById(texteId), TypeChangement.DISPARITION);
            entree.setCid(article.getCid());
            entree.setEtatAvant(article.getEtat());
            entree.setHashAvant(article.getContenuHash());
            entree.setExecutionId(executionId);
            entree.setRevisionSource(ref);
            journalRepository.save(entree);
            disparus++;
        }
        if (disparus > 0) {
            log.info("{} article(s) disparu(s) du dump pour le texte {}", disparus, texteId);
        }
        return disparus;
    }
}
