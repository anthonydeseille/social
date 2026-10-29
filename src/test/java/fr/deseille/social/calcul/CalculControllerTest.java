package fr.deseille.social.calcul;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

/** API du calculateur : sans base de données ni modèle, une tranche web suffit. */
@WebMvcTest(CalculController.class)
@Import({CalculIndemniteService.class, BaremeSyntec.class})
class CalculControllerTest {

    @Autowired
    private MockMvcTester mvc;

    @Test
    void indemnite_avec_detail_sources_et_montant() {
        var reponse = mvc.post().uri("/api/calculs/indemnite-licenciement").contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"profil": {"idcc": 1486, "statut": "CADRE", "typeContrat": "CDI", "dateEntree": "2019-10-01"},
                         "dateNotification": "2026-11-02", "motif": "LICENCIEMENT_ECONOMIQUE", "salaireMensuelBrut": 4500}
                        """);

        assertThat(reponse).hasStatusOk().bodyJson().extractingPath("$.retenue").isEqualTo("CONVENTIONNELLE");
        assertThat(reponse).bodyJson().extractingPath("$.moisRetenus").isEqualTo(2.36);
        assertThat(reponse).bodyJson().extractingPath("$.montantBrut").isEqualTo(10620.00);
        assertThat(reponse).bodyJson().extractingPath("$.conventionnelle.sources[0].num").isEqualTo("4.5");
        assertThat(reponse).bodyJson().extractingPath("$.legale.detail").asArray().isNotEmpty();
        assertThat(reponse).bodyJson().extractingPath("$.hypotheses").asArray().isNotEmpty();
    }

    @Test
    void demande_invalide_refusee() {
        assertThat(mvc.post().uri("/api/calculs/indemnite-licenciement").contentType(MediaType.APPLICATION_JSON)
                .content("{\"profil\": {\"dateEntree\": \"2020-01-06\"}, \"motif\": \"FAUTE_GRAVE\"}"))
                .as("date de notification manquante").hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(mvc.post().uri("/api/calculs/indemnite-licenciement").contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"profil": {"dateEntree": "2020-01-06"}, "dateNotification": "2026-11-02",
                         "motif": "LICENCIEMENT_PERSONNEL", "salaireMensuelBrut": -1}
                        """)).as("salaire négatif").hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(mvc.post().uri("/api/calculs/indemnite-licenciement").contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"profil": {}, "dateNotification": "2026-11-02", "motif": "LICENCIEMENT_PERSONNEL"}
                        """)).as("date d'entrée manquante").hasStatus(HttpStatus.BAD_REQUEST)
                .bodyJson().extractingPath("$.detail").asString().contains("date d'entrée");
    }
}
