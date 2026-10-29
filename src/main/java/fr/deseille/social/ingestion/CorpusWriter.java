package fr.deseille.social.ingestion;

import fr.deseille.social.corpus.Article;
import fr.deseille.social.corpus.ArticleRepository;
import fr.deseille.social.corpus.EtatArticle;
import fr.deseille.social.corpus.JournalChangement;
import fr.deseille.social.corpus.JournalChangementRepository;
import fr.deseille.social.corpus.Section;
import fr.deseille.social.corpus.SectionRepository;
import fr.deseille.social.corpus.Texte;
import fr.deseille.social.corpus.TexteRepository;
import fr.deseille.social.corpus.TypeChangement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.listener.StepExecutionListener;
import org.springframework.batch.core.step.StepExecution;
import org.springframework.batch.infrastructure.item.Chunk;
import org.springframework.batch.infrastructure.item.ItemWriter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Applique les {@link CommandeEcriture} : upsert idempotent des textes, sections et articles
 * (clé = {@code sourceId}), classification et journal des changements d'article, puis
 * détection des disparitions en fin de step.
 * <p>
 * La classification est faite ici, dans l'ordre du document, à partir d'un état de la base
 * préchargé par chunk (deux requêtes) et de l'état accumulé pendant le step : le résultat ne
 * dépend donc pas du découpage en chunks. Porté par le step courant ({@code @StepScope}).
 */
@Component
@StepScope
public class CorpusWriter implements ItemWriter<CommandeEcriture>, StepExecutionListener {

    private static final Logger log = LoggerFactory.getLogger(CorpusWriter.class);

    private final TexteRepository texteRepository;
    private final SectionRepository sectionRepository;
    private final ArticleRepository articleRepository;
    private final JournalChangementRepository journalRepository;
    private final DetecteurDisparition detecteurDisparition;
    private final Long jobExecutionId;

    private Long texteId;
    private String ref;
    private final Map<String, Long> idSectionParSource = new HashMap<>();
    /** Articles rencontrés pendant le step (détection des disparitions et des doublons). */
    private final Set<String> articlesVus = new HashSet<>();
    /** cid ayant déjà une version, en base ou créée pendant le step. */
    private final Set<String> cidsConnus = new HashSet<>();

    /** Article à écrire, avec le changement détecté et son état précédent (null si création). */
    private record Classement(CommandeEcriture.Article commande, TypeChangement changement, EtatArticle avant) {
    }

    public CorpusWriter(TexteRepository texteRepository,
                        SectionRepository sectionRepository,
                        ArticleRepository articleRepository,
                        JournalChangementRepository journalRepository,
                        DetecteurDisparition detecteurDisparition,
                        @Value("#{stepExecution.jobExecutionId}") Long jobExecutionId) {
        this.texteRepository = texteRepository;
        this.sectionRepository = sectionRepository;
        this.articleRepository = articleRepository;
        this.journalRepository = journalRepository;
        this.detecteurDisparition = detecteurDisparition;
        this.jobExecutionId = jobExecutionId;
    }

    @Override
    public void write(Chunk<? extends CommandeEcriture> chunk) {
        Map<String, EtatArticle> existants = precharger(chunk);

        // Textes et sections d'abord, dans l'ordre : les articles du chunk peuvent y faire référence.
        List<Classement> aEcrire = new ArrayList<>();
        for (CommandeEcriture commande : chunk) {
            switch (commande) {
                case CommandeEcriture.Texte t -> appliquerTexte(t);
                case CommandeEcriture.Section s -> appliquerSection(s);
                case CommandeEcriture.Article a -> {
                    Classement classement = classer(a, existants.get(a.sourceId()));
                    if (classement != null) {
                        aEcrire.add(classement);
                    }
                }
            }
        }
        ecrireArticles(aEcrire);
    }

    /** Charge en deux requêtes l'état des articles du chunk et les cid déjà connus. */
    private Map<String, EtatArticle> precharger(Chunk<? extends CommandeEcriture> chunk) {
        List<CommandeEcriture.Article> articles = new ArrayList<>();
        for (CommandeEcriture commande : chunk) {
            if (commande instanceof CommandeEcriture.Article a) {
                articles.add(a);
            }
        }
        if (articles.isEmpty()) {
            return Map.of();
        }
        Set<String> sourceIds = articles.stream().map(CommandeEcriture.Article::sourceId).collect(Collectors.toSet());
        Set<String> cids = articles.stream().map(CommandeEcriture.Article::cid)
                .filter(Objects::nonNull).filter(cid -> !cidsConnus.contains(cid)).collect(Collectors.toSet());
        if (!cids.isEmpty()) {
            cidsConnus.addAll(articleRepository.findCidsConnus(cids));
        }
        return articleRepository.findEtatsBySourceIdIn(sourceIds).stream()
                .collect(Collectors.toMap(EtatArticle::sourceId, Function.identity()));
    }

    private void appliquerTexte(CommandeEcriture.Texte c) {
        Texte texte = texteRepository.findBySourceId(c.sourceId())
                .orElseGet(() -> new Texte(c.sourceId(), c.type(), c.titre()));
        texte.setIdcc(c.idcc());
        texte.setTitre(c.titre());
        texte.setEtat(c.etat());
        texte.setDateDebut(c.dateDebut());
        texte.setDateFin(c.dateFin());
        this.texteId = texteRepository.save(texte).getId();
    }

    private void appliquerSection(CommandeEcriture.Section c) {
        Section section = sectionRepository.findBySourceId(c.sourceId())
                .orElseGet(() -> new Section(c.sourceId(), referenceTexte()));
        section.setCid(c.cid());
        section.setTitre(c.titre());
        section.setEtat(c.etat());
        section.setIntOrdre(c.intOrdre());
        section.setDateDebut(c.dateDebut());
        section.setDateFin(c.dateFin());
        section.setParent(referenceSection(c.parentSourceId()));
        Long id = sectionRepository.save(section).getId();
        idSectionParSource.put(c.sourceId(), id);
    }

    /**
     * Décide du changement à appliquer pour un article, ou null s'il n'y a rien à écrire
     * (article inchangé, ou occurrence en double dans le dump).
     */
    private Classement classer(CommandeEcriture.Article c, EtatArticle avant) {
        if (!articlesVus.add(c.sourceId())) {
            log.warn("Article {} présent plusieurs fois dans le dump : occurrence ignorée", c.sourceId());
            return null;
        }
        this.ref = c.ref();
        if (avant == null) {
            // add() renvoie false si le cid avait déjà une version : c'en est une nouvelle.
            boolean nouvelleVersion = c.cid() != null && !cidsConnus.add(c.cid());
            return new Classement(c, nouvelleVersion ? TypeChangement.NOUVELLE_VERSION : TypeChangement.CREATION, null);
        }
        TypeChangement changement;
        if (avant.disparuLe() != null) {
            changement = TypeChangement.REAPPARITION;
        } else if (!Objects.equals(avant.contenuHash(), c.contenuHash())) {
            changement = TypeChangement.MODIFICATION;
        } else if (avant.etat() != c.etat()) {
            changement = TypeChangement.CHANGEMENT_ETAT;
        } else if (!Objects.equals(avant.metaHash(), c.metaHash())) {
            changement = TypeChangement.CHANGEMENT_METADONNEES;
        } else {
            return null; // inchangé : aucune écriture
        }
        return new Classement(c, changement, avant);
    }

    private void ecrireArticles(List<Classement> classements) {
        if (classements.isEmpty()) {
            return;
        }
        Set<String> aMettreAJour = classements.stream()
                .filter(cl -> cl.avant() != null)
                .map(cl -> cl.commande().sourceId())
                .collect(Collectors.toSet());
        Map<String, Article> entites = aMettreAJour.isEmpty() ? Map.of()
                : articleRepository.findBySourceIdIn(aMettreAJour).stream()
                        .collect(Collectors.toMap(Article::getSourceId, Function.identity()));

        List<Article> articles = new ArrayList<>(classements.size());
        List<JournalChangement> journal = new ArrayList<>(classements.size());
        for (Classement cl : classements) {
            CommandeEcriture.Article c = cl.commande();
            Article article = cl.avant() == null
                    ? new Article(c.sourceId(), referenceTexte())
                    : entites.get(c.sourceId());
            appliquer(article, c);
            articles.add(article);
            journal.add(entreeJournal(cl));
        }
        articleRepository.saveAll(articles);
        journalRepository.saveAll(journal);
    }

    private void appliquer(Article article, CommandeEcriture.Article c) {
        article.setCid(c.cid());
        article.setSection(referenceSection(c.sectionSourceId()));
        article.setNum(c.num());
        article.setEtat(c.etat());
        article.setDateDebut(c.dateDebut());
        article.setDateFin(c.dateFin());
        article.setIntOrdre(c.intOrdre());
        article.setContenuTexte(c.contenuTexte());
        article.setContenuHtml(c.contenuHtml());
        article.setNota(c.nota());
        article.setContenuHash(c.contenuHash());
        article.setMetaHash(c.metaHash());
        article.setDonneesBrutes(c.donneesBrutes());
        article.setDisparuLe(null);
    }

    private JournalChangement entreeJournal(Classement cl) {
        CommandeEcriture.Article c = cl.commande();
        JournalChangement entree = new JournalChangement(c.sourceId(), referenceTexte(), cl.changement());
        entree.setCid(c.cid());
        if (cl.avant() != null) {
            entree.setEtatAvant(cl.avant().etat());
            entree.setHashAvant(cl.avant().contenuHash());
        }
        entree.setEtatApres(c.etat());
        entree.setHashApres(c.contenuHash());
        entree.setExecutionId(jobExecutionId);
        entree.setRevisionSource(c.ref());
        return entree;
    }

    private Texte referenceTexte() {
        return texteRepository.getReferenceById(texteId);
    }

    private Section referenceSection(String sourceId) {
        if (sourceId == null) {
            return null;
        }
        Long id = idSectionParSource.get(sourceId);
        if (id == null) {
            id = sectionRepository.findBySourceId(sourceId).map(Section::getId).orElse(null);
        }
        return id == null ? null : sectionRepository.getReferenceById(id);
    }

    /**
     * Détecte les disparitions uniquement si le step a lu le dump en entier : sur un step en
     * échec, {@link #articlesVus} est partiel et produirait de fausses disparitions. Une erreur
     * de détection fait échouer le step (Spring Batch ne ferait que la journaliser).
     */
    @Override
    public ExitStatus afterStep(StepExecution stepExecution) {
        if (stepExecution.getStatus() != BatchStatus.COMPLETED) {
            log.warn("Step {} terminé en {} : détection des disparitions ignorée",
                    stepExecution.getStepName(), stepExecution.getStatus());
            return stepExecution.getExitStatus();
        }
        try {
            detecteurDisparition.marquerDisparitions(texteId, articlesVus, jobExecutionId, ref);
            return stepExecution.getExitStatus();
        } catch (RuntimeException e) {
            log.error("Échec de la détection des disparitions pour le step {}", stepExecution.getStepName(), e);
            stepExecution.setStatus(BatchStatus.FAILED);
            stepExecution.addFailureException(e);
            return ExitStatus.FAILED.addExitDescription(e);
        }
    }
}
