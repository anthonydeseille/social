package fr.deseille.social.ingestion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import fr.deseille.social.corpus.EtatJuridique;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

class MappeurNoeudTest {

    private final ObjectMapper objectMapper = JsonMapper.builder().build();
    private final MappeurNoeud mappeur = new MappeurNoeud(objectMapper);

    private JsonNode json(String contenu) {
        return objectMapper.readTree(contenu);
    }

    @Test
    void etat_connu_ou_absent() {
        assertThat(mappeur.etat(json("{\"etat\":\"VIGUEUR_ETEN\"}"))).isEqualTo(EtatJuridique.VIGUEUR_ETEN);
        assertThat(mappeur.etat(json("{}"))).isNull();
        assertThat(mappeur.etat(json("{\"etat\":\"\"}"))).isNull();
    }

    @Test
    void etat_inconnu_echoue_explicitement() {
        assertThatThrownBy(() -> mappeur.etat(json("{\"etat\":\"NOUVEL_ETAT\"}")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("NOUVEL_ETAT");
    }

    @Test
    void dates_epoch_millis_et_iso() {
        JsonNode data = json("{\"debut\":1577836800000,\"fin\":\"2999-01-01\",\"horodate\":\"2020-06-15T00:00:00\",\"vide\":\"\"}");
        assertThat(mappeur.date(data, "debut")).isEqualTo(LocalDate.of(2020, 1, 1));
        assertThat(mappeur.date(data, "fin")).isEqualTo(LocalDate.of(2999, 1, 1));
        assertThat(mappeur.date(data, "horodate")).isEqualTo(LocalDate.of(2020, 6, 15));
        assertThat(mappeur.date(data, "vide")).isNull();
        assertThat(mappeur.date(data, "absent")).isNull();
    }

    @Test
    void date_mal_formee_echoue_explicitement() {
        assertThatThrownBy(() -> mappeur.date(json("{\"fin\":\"31/12/2020\"}"), "fin"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("fin")
                .hasMessageContaining("31/12/2020");
    }

    @Test
    void champ_obligatoire_absent_echoue_explicitement() {
        assertThatThrownBy(() -> mappeur.texteObligatoire(json("{\"title\":\" \"}"), "title", "texte X"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("title")
                .hasMessageContaining("texte X");
    }

    @Test
    void hash_contenu_sensible_au_contenu_et_sans_collision_par_concatenation() {
        String reference = mappeur.hashContenu("abc", "<p>abc</p>", "L1", null);
        assertThat(mappeur.hashContenu("abc", "<p>abc</p>", "L1", null)).isEqualTo(reference);
        assertThat(mappeur.hashContenu("abc", "<p>abc modifié</p>", "L1", null)).isNotEqualTo(reference);
        assertThat(mappeur.hashContenu("abc", "<p>abc</p>", "L1", "nota")).isNotEqualTo(reference);
        assertThat(mappeur.hashContenu("ab", "c", null, null)).isNotEqualTo(mappeur.hashContenu("a", "bc", null, null));
    }

    @Test
    void hash_metadonnees_sensible_a_l_ordre_la_section_et_les_dates() {
        LocalDate debut = LocalDate.of(2020, 1, 1);
        String reference = mappeur.hashMetadonnees("CID", "SCT1", 1, debut, null);
        assertThat(mappeur.hashMetadonnees("CID", "SCT1", 2, debut, null)).isNotEqualTo(reference);
        assertThat(mappeur.hashMetadonnees("CID", "SCT2", 1, debut, null)).isNotEqualTo(reference);
        assertThat(mappeur.hashMetadonnees("CID", "SCT1", 1, debut, LocalDate.of(2030, 1, 1))).isNotEqualTo(reference);
    }
}
