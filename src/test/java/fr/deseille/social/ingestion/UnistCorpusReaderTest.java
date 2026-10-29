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
    void titres_longs_de_meme_debut_donnent_des_ids_distincts_et_bornes() throws Exception {
        String debut = "Accord du 1er janvier 2020 relatif aux salaires minima conventionnels";
        List<NoeudCorpus> noeuds = lire("""
                {"type": "convention collective", "data": {"id": "KALICONT000000000001", "title": "Convention"},
                 "children": [
                   {"type": "section", "data": {"title": "%s des ingénieurs"}},
                   {"type": "section", "data": {"title": "%s des techniciens"}}
                 ]}
                """.formatted(debut, debut));

        String id1 = noeuds.get(1).data().get("id").asText();
        String id2 = noeuds.get(2).data().get("id").asText();
        assertThat(id1).hasSize(64).startsWith("KALICONT000000000001:accord-du-1er-janvier");
        assertThat(id2).hasSize(64).isNotEqualTo(id1);
        assertThat(id1).matches(".*-[0-9a-f]{8}");
    }

    @Test
    void section_sans_id_ni_titre_echoue_explicitement() {
        assertThatThrownBy(() -> lire("""
                {"type": "convention collective", "data": {"id": "KALICONT000000000001", "title": "Convention"},
                 "children": [ {"type": "section", "data": {"etat": "VIGUEUR"}} ]}
                """))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ni titre")
                .hasMessageContaining("KALICONT000000000001");
    }

    @Test
    void section_sans_id_hors_d_un_texte_echoue_explicitement() {
        assertThatThrownBy(() -> lire("""
                {"type": "section", "data": {"title": "Racine inattendue"}, "children": []}
                """))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("hors d'un texte");
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
