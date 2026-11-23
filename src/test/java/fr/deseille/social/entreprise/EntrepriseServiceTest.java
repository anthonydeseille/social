package fr.deseille.social.entreprise;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import fr.deseille.social.corpus.Texte;
import fr.deseille.social.corpus.TexteRepository;
import fr.deseille.social.entreprise.EntrepriseService.Convention;
import fr.deseille.social.entreprise.EntrepriseService.Entreprise;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;

class EntrepriseServiceTest {

    /** Réponse de l'annuaire, réduite aux champs lus (entreprise réelle, données publiques). */
    private static final String REPONSE = """
            {"results": [{
              "siren": "479766842", "nom_complet": "CAPGEMINI TECHNOLOGY SERVICES",
              "complements": {"liste_idcc": ["1486", "9999"]},
              "siege": {"siret": "47976684200724", "liste_idcc": ["9999", "1486"], "libelle_commune": "ISSY-LES-MOULINEAUX"},
              "matching_etablissements": [
                {"siret": "47976684201011", "liste_idcc": ["1486", "2120"], "libelle_commune": "LE BOURGET-DU-LAC"}]
            }], "total_results": 1}
            """;

    private final TexteRepository textes = mock(TexteRepository.class);
    private final RestClient.Builder builder = RestClient.builder().baseUrl("https://annuaire.test");
    private final MockRestServiceServer annuaire = MockRestServiceServer.bindTo(builder).build();
    private EntrepriseService service;

    @BeforeEach
    void preparer() {
        Texte syntec = mock(Texte.class);
        when(syntec.getTitre()).thenReturn("Convention collective nationale Syntec");
        when(textes.findByIdcc(1486)).thenReturn(List.of(syntec));
        service = new EntrepriseService(textes, builder);
    }

    @Test
    void par_nom_conventions_de_l_entreprise_sans_l_idcc_9999() {
        annuaire.expect(requestTo("https://annuaire.test/search?q=capgemini%20technology&per_page=5&page=1"))
                .andRespond(withSuccess(REPONSE, MediaType.APPLICATION_JSON));

        List<Entreprise> r = service.chercher("capgemini technology");

        assertThat(r).singleElement().satisfies(e -> {
            assertThat(e.nom()).isEqualTo("CAPGEMINI TECHNOLOGY SERVICES");
            assertThat(e.siret()).isNull();
            assertThat(e.commune()).isEqualTo("ISSY-LES-MOULINEAUX");
            assertThat(e.conventions()).containsExactly(
                    new Convention(1486, "Convention collective nationale Syntec", true));
        });
    }

    @Test
    void par_siret_conventions_de_l_etablissement_espaces_retires() {
        annuaire.expect(requestTo("https://annuaire.test/search?q=47976684201011&per_page=5&page=1"))
                .andRespond(withSuccess(REPONSE, MediaType.APPLICATION_JSON));

        Entreprise e = service.chercher("479 766 842 01011").getFirst();

        assertThat(e.siret()).isEqualTo("47976684201011");
        assertThat(e.commune()).isEqualTo("LE BOURGET-DU-LAC");
        assertThat(e.conventions()).as("2120 : connue de l'annuaire, pas intégrée")
                .containsExactly(new Convention(1486, "Convention collective nationale Syntec", true),
                        new Convention(2120, null, false));
    }

    @Test
    void annuaire_indisponible_503() {
        annuaire.expect(requestTo("https://annuaire.test/search?q=capgemini&per_page=5&page=1"))
                .andRespond(withServerError());

        assertThatThrownBy(() -> service.chercher("capgemini")).isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("503").hasMessageContaining("annuaire des entreprises ne répond pas");
    }
}
