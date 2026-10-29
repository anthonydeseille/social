package fr.deseille.social.ingestion;

import fr.deseille.social.corpus.ArticleRepository;
import fr.deseille.social.corpus.EtatArticle;
import fr.deseille.social.corpus.JournalChangement;
import fr.deseille.social.corpus.JournalChangementRepository;
import fr.deseille.social.corpus.Texte;
import fr.deseille.social.corpus.TexteRepository;
import fr.deseille.social.corpus.TypeChangement;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Détecte les articles d'un texte encore présents en base mais absents du dump courant : ils
 * sont marqués disparus ({@code disparuLe}) et consignés au journal en
 * {@link TypeChangement#DISPARITION}. Un article déjà disparu n'est pas re-journalisé ; s'il
 * revient, le writer le consigne en {@link TypeChangement#REAPPARITION}. Les articles ne sont
 * jamais supprimés (historisation).
 */
@Service
public class DetecteurDisparition {

    private static final Logger log = LoggerFactory.getLogger(DetecteurDisparition.class);

    /** Taille des lots de mise à jour (reste loin de la limite de paramètres de PostgreSQL). */
    private static final int TAILLE_LOT = 1000;

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
        List<EtatArticle> disparus = articleRepository.findEtatsPresentsByTexteId(texteId).stream()
                .filter(a -> !sourceIdsVus.contains(a.sourceId()))
                .toList();
        if (disparus.isEmpty()) {
            return 0;
        }

        Texte texte = texteRepository.getReferenceById(texteId);
        journalRepository.saveAll(disparus.stream().map(a -> {
            JournalChangement entree = new JournalChangement(a.sourceId(), texte, TypeChangement.DISPARITION);
            entree.setCid(a.cid());
            entree.setEtatAvant(a.etat());
            entree.setHashAvant(a.contenuHash());
            entree.setExecutionId(executionId);
            entree.setRevisionSource(ref);
            return entree;
        }).toList());

        Instant maintenant = Instant.now();
        List<String> ids = disparus.stream().map(EtatArticle::sourceId).toList();
        for (int debut = 0; debut < ids.size(); debut += TAILLE_LOT) {
            articleRepository.marquerDisparus(ids.subList(debut, Math.min(debut + TAILLE_LOT, ids.size())), maintenant);
        }
        log.info("{} article(s) disparu(s) du dump pour le texte {}", disparus.size(), texteId);
        return disparus.size();
    }
}
