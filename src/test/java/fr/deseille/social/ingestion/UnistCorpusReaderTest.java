package fr.deseille.social.ingestion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import fr.deseille.social.corpus.TypeTexte;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.batch.infrastructure.item.ExecutionContext;
import tools.jackson.databind.json.JsonMapper;

class UnistCorpusReaderTest {

    @TempDir
    Path repertoire;

    private List<NoeudCorpus> lire(String json) throws Exception {
        Files.writeString(repertoire.resolve("kali.json"), json);
        IngestionProperties properties = new IngestionProperties(
                repertoire.toUri().toString(), repertoire.resolve("cache").toString(), List.of(), 500);
        SourceCorpus source = new SourceCorpus("syntec", TypeTexte.CONVENTION_COLLECTIVE, "", "", "kali.json");
        UnistCorpusReader reader = new UnistCorpusReader(
                new CorpusDownloader(properties), JsonMapper.builder().build(), source);
        reader.open(new ExecutionContext());
        try {
            List<NoeudCorpus> noeuds = new ArrayList<>();
            for (NoeudCorpus n = reader.read(); n != null; n = reader.read()) {
                noeuds.add(n);
            }
            return noeuds;
        } finally {
            reader.close();
        }
    }

    @Test
    void section_sans_id_recoit_un_id_synthetique_stable_herite_par_ses_enfants() throws Exception {
        List<NoeudCorpus> noeuds = lire("""
                {"type": "convention collective", "data": {"id": "KALICONT000000000001", "title": "Convention"},
                 "children": [
                   {"type": "section", "data": {"title": "Textes Attachés", "etat": "VIGUEUR"},
                    "children": [
                      {"type": "section", "data": {"id": "KALISCTA000000000001", "title": "Avenant 1"},
                       "children": [
                         {"type": "article", "data": {"id": "KALIARTI000000000001"}}
                       ]},
                      {"type": "article", "data": {"id": "KALIARTI000000000002"}}
                    ]}
                 ]}
                """);

        assertThat(noeuds).hasSize(5);
        var regroupement = (NoeudCorpus.SectionItem) noeuds.get(1);
        assertThat(regroupement.data().get("id").asText()).isEqualTo("KALICONT000000000001:textes-attaches");
        assertThat(regroupement.parentSectionSourceId()).isNull();
        assertThat(((NoeudCorpus.SectionItem) noeuds.get(2)).parentSectionSourceId())
                .isEqualTo("KALICONT000000000001:textes-attaches");
        assertThat(((NoeudCorpus.ArticleItem) noeuds.get(3)).sectionSourceId()).isEqualTo("KALISCTA000000000001");
        assertThat(((NoeudCorpus.ArticleItem) noeuds.get(4)).sectionSourceId())
                .isEqualTo("KALICONT000000000001:textes-attaches");
    }

    @Test
    void deux_sections_sans_id_de_meme_titre_echouent_explicitement() {
        assertThatThrownBy(() -> lire("""
                {"type": "convention collective", "data": {"id": "KALICONT000000000001", "title": "Convention"},
                 "children": [
                   {"type": "section", "data": {"title": "Textes Salaires"}},
                   {"type": "section", "data": {"title": "Textes salaires"}}
                 ]}
                """))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("KALICONT000000000001:textes-salaires");
    }

    @Test
    void fichier_tronque_echoue_explicitement() {
        assertThatThrownBy(() -> lire("""
                {"type": "convention collective", "data": {"id": "KALICONT000000000001", "title": "Convention"},
                 "children": [ {"type": "article", "data": {"id": "KALIARTI000000000001"}},
                """))
                .isInstanceOf(Exception.class);
    }
}
