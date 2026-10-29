package fr.deseille.social.ingestion;

import fr.deseille.social.corpus.TypeTexte;
import java.time.LocalDate;
import org.springframework.batch.infrastructure.item.ItemProcessor;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * Transforme un nœud brut en {@link CommandeEcriture} normalisée, sans accès à la base : la
 * comparaison avec l'existant est faite par {@link CorpusWriter}, par chunk.
 */
@Component
public class NoeudCorpusProcessor implements ItemProcessor<NoeudCorpus, CommandeEcriture> {

    private final MappeurNoeud mappeur;

    public NoeudCorpusProcessor(MappeurNoeud mappeur) {
        this.mappeur = mappeur;
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
        String sourceId = mappeur.texteObligatoire(data, "id", "texte de la source " + item.source().cle());
        Integer idcc = type == TypeTexte.CONVENTION_COLLECTIVE ? mappeur.entier(data, "num") : null;
        return new CommandeEcriture.Texte(
                sourceId,
                type,
                idcc,
                mappeur.texteObligatoire(data, "title", "texte " + sourceId).strip(),
                mappeur.etat(data),
                mappeur.date(data, "dateDebutVersion"),
                mappeur.date(data, "dateFinVersion"));
    }

    private CommandeEcriture versSection(NoeudCorpus.SectionItem item) {
        JsonNode data = item.data();
        return new CommandeEcriture.Section(
                mappeur.texteObligatoire(data, "id", "section de la source " + item.source().cle()),
                mappeur.texte(data, "cid"),
                item.parentSectionSourceId(),
                mappeur.titre(data),
                mappeur.etat(data),
                mappeur.entier(data, "intOrdre"),
                mappeur.date(data, "dateDebut"),
                mappeur.date(data, "dateFin"));
    }

    private CommandeEcriture versArticle(NoeudCorpus.ArticleItem item) {
        JsonNode data = item.data();
        String sourceId = mappeur.texteObligatoire(data, "id", "article de la source " + item.source().cle());
        String cid = mappeur.texte(data, "cid");
        String num = mappeur.texte(data, "num");
        String contenuTexte = mappeur.contenuTexte(data);
        String contenuHtml = mappeur.contenuHtml(data);
        String nota = mappeur.texte(data, "nota");
        Integer intOrdre = mappeur.entier(data, "intOrdre");
        LocalDate dateDebut = mappeur.date(data, "dateDebut");
        LocalDate dateFin = mappeur.date(data, "dateFin");

        return new CommandeEcriture.Article(
                sourceId,
                cid,
                item.sectionSourceId(),
                num,
                mappeur.etat(data),
                dateDebut,
                dateFin,
                intOrdre,
                contenuTexte,
                contenuHtml,
                nota,
                mappeur.hashContenu(contenuTexte, contenuHtml, num, nota),
                mappeur.hashMetadonnees(cid, item.sectionSourceId(), intOrdre, dateDebut, dateFin),
                mappeur.brut(data),
                item.source().ref());
    }
}
