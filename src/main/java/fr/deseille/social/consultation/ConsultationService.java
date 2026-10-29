package fr.deseille.social.consultation;

import fr.deseille.social.consultation.VuesConsultation.ArticleDetail;
import fr.deseille.social.consultation.VuesConsultation.ArticleResume;
import fr.deseille.social.consultation.VuesConsultation.ElementSommaire;
import fr.deseille.social.consultation.VuesConsultation.EvenementJournal;
import fr.deseille.social.consultation.VuesConsultation.Historique;
import fr.deseille.social.consultation.VuesConsultation.SectionResume;
import fr.deseille.social.consultation.VuesConsultation.Sommaire;
import fr.deseille.social.consultation.VuesConsultation.TexteResume;
import fr.deseille.social.corpus.Article;
import fr.deseille.social.corpus.ArticleRepository;
import fr.deseille.social.corpus.JournalChangementRepository;
import fr.deseille.social.corpus.Section;
import fr.deseille.social.corpus.SectionRepository;
import fr.deseille.social.corpus.Texte;
import fr.deseille.social.corpus.TexteRepository;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Lecture du corpus pour l'API : textes, sommaire niveau par niveau, articles et historique.
 * Les transactions en lecture seule permettent de parcourir les relations paresseuses.
 */
@Service
@Transactional(readOnly = true)
public class ConsultationService {

    /** Ordre du sommaire : {@code intOrdre}, éléments sans ordre en fin. */
    private static final Comparator<ElementSommaire> ORDRE_SOMMAIRE =
            Comparator.comparing(ElementSommaire::intOrdre, Comparator.nullsLast(Comparator.naturalOrder()));

    /** Versions applicables d'abord, puis les plus récentes. */
    private static final Comparator<ArticleResume> ORDRE_VERSIONS =
            Comparator.comparing(ArticleResume::applicable).reversed()
                    .thenComparing(ArticleResume::dateDebut, Comparator.nullsLast(Comparator.<LocalDate>reverseOrder()));

    private final TexteRepository texteRepository;
    private final SectionRepository sectionRepository;
    private final ArticleRepository articleRepository;
    private final JournalChangementRepository journalRepository;

    public ConsultationService(TexteRepository texteRepository, SectionRepository sectionRepository,
                               ArticleRepository articleRepository, JournalChangementRepository journalRepository) {
        this.texteRepository = texteRepository;
        this.sectionRepository = sectionRepository;
        this.articleRepository = articleRepository;
        this.journalRepository = journalRepository;
    }

    public List<TexteResume> textes() {
        return texteRepository.findAll().stream()
                .map(TexteResume::de)
                .sorted(Comparator.comparing(TexteResume::type).thenComparing(TexteResume::titre))
                .toList();
    }

    /**
     * Un niveau du sommaire : la racine du texte si {@code sectionSourceId} est null, sinon le
     * contenu de cette section (qui doit appartenir au texte).
     */
    public Sommaire sommaire(String texteSourceId, String sectionSourceId) {
        Texte texte = texte(texteSourceId);
        if (sectionSourceId == null) {
            List<ElementSommaire> elements = Stream.concat(
                            sectionRepository.findByTexteIdAndParentIsNullOrderByIntOrdreAsc(texte.getId()).stream()
                                    .map(ElementSommaire::de),
                            articleRepository.findByTexteIdAndSectionIsNullOrderByIntOrdreAsc(texte.getId()).stream()
                                    .map(ElementSommaire::de))
                    .sorted(ORDRE_SOMMAIRE)
                    .toList();
            return new Sommaire(TexteResume.de(texte), null, List.of(), elements);
        }
        Section section = sectionRepository.findBySourceId(sectionSourceId)
                .filter(s -> s.getTexte().getId().equals(texte.getId()))
                .orElseThrow(() -> introuvable("Section " + sectionSourceId + " absente du texte " + texteSourceId));
        List<ElementSommaire> elements = Stream.concat(
                        sectionRepository.findByParentIdOrderByIntOrdreAsc(section.getId()).stream()
                                .map(ElementSommaire::de),
                        articleRepository.findBySectionIdOrderByIntOrdreAsc(section.getId()).stream()
                                .map(ElementSommaire::de))
                .sorted(ORDRE_SOMMAIRE)
                .toList();
        return new Sommaire(TexteResume.de(texte), SectionResume.de(section), filAriane(section), elements);
    }

    public ArticleDetail article(String sourceId) {
        Article a = articleRepository.findBySourceId(sourceId)
                .orElseThrow(() -> introuvable("Article inconnu : " + sourceId));
        return new ArticleDetail(a.getSourceId(), a.getCid(), a.getNum(), a.getEtat(), VuesConsultation.applicable(a),
                a.getDateDebut(), a.getDateFin(), a.getContenuTexte(), a.getContenuHtml(), a.getNota(),
                a.getDisparuLe(), TexteResume.de(a.getTexte()), filAriane(a.getSection()));
    }

    /** Versions d'un article d'un texte par numéro (ex. {@code L1221-1}), applicables d'abord. */
    public List<ArticleResume> articlesParNumero(String texteSourceId, String num) {
        Texte texte = texte(texteSourceId);
        return articleRepository.findByTexteIdAndNum(texte.getId(), num).stream()
                .map(ArticleResume::de)
                .sorted(ORDRE_VERSIONS)
                .toList();
    }

    public Historique historique(String sourceId) {
        Article article = articleRepository.findBySourceId(sourceId)
                .orElseThrow(() -> introuvable("Article inconnu : " + sourceId));
        List<Article> versions = article.getCid() == null ? List.of(article) : articleRepository.findByCid(article.getCid());
        List<String> ids = versions.stream().map(Article::getSourceId).toList();
        return new Historique(article.getCid(),
                versions.stream().map(ArticleResume::de).sorted(ORDRE_VERSIONS).toList(),
                journalRepository.findByArticleSourceIdInOrderByDetecteLeAscIdAsc(ids).stream()
                        .map(EvenementJournal::de).toList());
    }

    private Texte texte(String sourceId) {
        return texteRepository.findBySourceId(sourceId)
                .orElseThrow(() -> introuvable("Texte inconnu : " + sourceId));
    }

    /** Sections de la racine jusqu'à {@code section} incluse (vide si null). */
    private static List<SectionResume> filAriane(Section section) {
        List<SectionResume> fil = new ArrayList<>();
        for (Section s = section; s != null; s = s.getParent()) {
            fil.addFirst(SectionResume.de(s));
        }
        return fil;
    }

    private static ResponseStatusException introuvable(String message) {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, message);
    }
}
