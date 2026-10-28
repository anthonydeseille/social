package fr.deseille.social.ingestion;

import fr.deseille.social.corpus.Article;
import fr.deseille.social.corpus.ArticleRepository;
import fr.deseille.social.corpus.JournalChangement;
import fr.deseille.social.corpus.JournalChangementRepository;
import fr.deseille.social.corpus.Section;
import fr.deseille.social.corpus.SectionRepository;
import fr.deseille.social.corpus.Texte;
import fr.deseille.social.corpus.TexteRepository;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
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
 * (clé = {@code sourceId}), écriture du journal des changements pour les articles, et
 * détection des disparitions en fin de step. Porté par le step courant ({@code @StepScope}) :
 * conserve l'identité du texte, le cache {@code sourceId → id} des sections et l'ensemble des
 * articles vus durant le run.
 */
@Component
@StepScope
public class CorpusWriter implements ItemWriter<CommandeEcriture>, StepExecutionListener {

    private final TexteRepository texteRepository;
    private final SectionRepository sectionRepository;
    private final ArticleRepository articleRepository;
    private final JournalChangementRepository journalRepository;
    private final DetecteurDisparition detecteurDisparition;
    private final Long jobExecutionId;

    private Long texteId;
    private String ref;
    private final Map<String, Long> idSectionParSource = new HashMap<>();
    private final Set<String> articlesVus = new HashSet<>();

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
        for (CommandeEcriture commande : chunk) {
            switch (commande) {
                case CommandeEcriture.Texte t -> appliquerTexte(t);
                case CommandeEcriture.Section s -> appliquerSection(s);
                case CommandeEcriture.Article a -> appliquerArticle(a);
            }
        }
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

    private void appliquerArticle(CommandeEcriture.Article c) {
        articlesVus.add(c.sourceId());
        this.ref = c.ref();
        if (c.changement() == null) {
            return; // inchangé : upsert sans écriture
        }
        Article article = switch (c.changement()) {
            case CREATION, NOUVELLE_VERSION -> new Article(c.sourceId(), referenceTexte());
            default -> articleRepository.findBySourceId(c.sourceId()).orElseThrow();
        };
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
        article.setDonneesBrutes(c.donneesBrutes());
        articleRepository.save(article);
        journaliser(c);
    }

    private void journaliser(CommandeEcriture.Article c) {
        JournalChangement entree = new JournalChangement(c.sourceId(), referenceTexte(), c.changement());
        entree.setCid(c.cid());
        entree.setEtatAvant(c.etatAvant());
        entree.setEtatApres(c.etat());
        entree.setHashAvant(c.hashAvant());
        entree.setHashApres(c.contenuHash());
        entree.setExecutionId(jobExecutionId);
        entree.setRevisionSource(c.ref());
        journalRepository.save(entree);
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

    @Override
    public ExitStatus afterStep(StepExecution stepExecution) {
        detecteurDisparition.marquerDisparitions(texteId, articlesVus, jobExecutionId, ref);
        return stepExecution.getExitStatus();
    }
}
