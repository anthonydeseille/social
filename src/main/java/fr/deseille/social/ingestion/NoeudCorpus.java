package fr.deseille.social.ingestion;

import tools.jackson.databind.JsonNode;

/**
 * Nœud brut émis par {@link UnistCorpusReader} lors du parcours de l'arbre unist, dans
 * l'ordre du document (racine d'abord, puis sections et articles en profondeur). Chaque
 * nœud porte le {@code data} JSON brut et la {@link SourceCorpus} d'origine ; la
 * normalisation est faite en aval par le processor.
 */
public sealed interface NoeudCorpus permits NoeudCorpus.TexteItem, NoeudCorpus.SectionItem, NoeudCorpus.ArticleItem {

    SourceCorpus source();

    JsonNode data();

    /** Racine de l'arbre : un Code ou une convention collective. */
    record TexteItem(SourceCorpus source, JsonNode data) implements NoeudCorpus {
    }

    /** Section de la hiérarchie, avec l'identifiant de sa section parente (null si racine). */
    record SectionItem(SourceCorpus source, JsonNode data, String parentSectionSourceId) implements NoeudCorpus {
    }

    /** Article, avec l'identifiant de la section qui le contient (null si directement sous la racine). */
    record ArticleItem(SourceCorpus source, JsonNode data, String sectionSourceId) implements NoeudCorpus {
    }
}
