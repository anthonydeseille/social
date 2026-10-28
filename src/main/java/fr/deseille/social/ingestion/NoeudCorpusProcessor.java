package fr.deseille.social.ingestion;

import tools.jackson.databind.JsonNode;
import fr.deseille.social.corpus.Article;
import fr.deseille.social.corpus.ArticleRepository;
import fr.deseille.social.corpus.EtatJuridique;
import fr.deseille.social.corpus.TypeChangement;
import fr.deseille.social.corpus.TypeTexte;
import java.util.Objects;
import java.util.Optional;
import org.springframework.batch.infrastructure.item.ItemProcessor;
import org.springframework.stereotype.Component;

/**
 * Transforme un nœud brut en {@link CommandeEcriture} normalisée. Pour les articles, compare
 * à l'état en base pour classer le changement (création, nouvelle version, modification,
 * changement d'état, ou aucun).
 */
@Component
public class NoeudCorpusProcessor implements ItemProcessor<NoeudCorpus, CommandeEcriture> {

    private final MappeurNoeud mappeur;
    private final ArticleRepository articleRepository;

    public NoeudCorpusProcessor(MappeurNoeud mappeur, ArticleRepository articleRepository) {
        this.mappeur = mappeur;
        this.articleRepository = articleRepository;
    }

    @Override
    public CommandeEcriture process(NoeudCorpus item) {
        return switch (item) {
            case NoeudCorpus.TexteItem t -> versTexte(t);
            case NoeudCorpus.SectionItem s -> versSection(s);
            case NoeudCorpus.ArticleItem a -> versArticle(a);
        };
    }

    private CommandeEcriture versTexte(NoeudCorpus.TexteItem item) {
        JsonNode data = item.data();
        TypeTexte type = item.source().type();
        Integer idcc = type == TypeTexte.CONVENTION_COLLECTIVE ? mappeur.entier(data, "num") : null;
        return new CommandeEcriture.Texte(
                mappeur.texte(data, "id"),
                type,
                idcc,
                mappeur.texte(data, "title"),
                mappeur.etat(data),
                mappeur.date(data, "dateDebutVersion"),
                mappeur.date(data, "dateFinVersion"));
    }

    private CommandeEcriture versSection(NoeudCorpus.SectionItem item) {
        JsonNode data = item.data();
        return new CommandeEcriture.Section(
                mappeur.texte(data, "id"),
                mappeur.texte(data, "cid"),
                item.parentSectionSourceId(),
                mappeur.texte(data, "title"),
                mappeur.etat(data),
                mappeur.entier(data, "intOrdre"),
                mappeur.date(data, "dateDebut"),
                mappeur.date(data, "dateFin"));
    }

    private CommandeEcriture versArticle(NoeudCorpus.ArticleItem item) {
        JsonNode data = item.data();
        String sourceId = mappeur.texte(data, "id");
        String cid = mappeur.texte(data, "cid");
        EtatJuridique etat = mappeur.etat(data);
        String hash = mappeur.hashContenu(data);

        Optional<Article> existant = articleRepository.findBySourceId(sourceId);
        TypeChangement changement;
        EtatJuridique etatAvant = null;
        String hashAvant = null;
        if (existant.isEmpty()) {
            boolean autreVersion = cid != null && articleRepository.existsByCid(cid);
            changement = autreVersion ? TypeChangement.NOUVELLE_VERSION : TypeChangement.CREATION;
        } else {
            Article a = existant.get();
            etatAvant = a.getEtat();
            hashAvant = a.getContenuHash();
            boolean memeContenu = Objects.equals(a.getContenuHash(), hash);
            boolean memeEtat = a.getEtat() == etat;
            if (memeContenu && memeEtat) {
                changement = null; // inchangé → aucune écriture
            } else if (memeContenu) {
                changement = TypeChangement.CHANGEMENT_ETAT;
            } else {
                changement = TypeChangement.MODIFICATION;
            }
        }

        return new CommandeEcriture.Article(
                sourceId,
                cid,
                item.sectionSourceId(),
                mappeur.texte(data, "num"),
                etat,
                mappeur.date(data, "dateDebut"),
                mappeur.date(data, "dateFin"),
                mappeur.entier(data, "intOrdre"),
                mappeur.contenuTexte(data),
                mappeur.contenuHtml(data),
                mappeur.texte(data, "nota"),
                hash,
                mappeur.brut(data),
                changement,
                etatAvant,
                hashAvant,
                item.source().ref());
    }
}
