package fr.deseille.social.entreprise;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
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
    private final MutableClock horloge = new MutableClock(java.time.Instant.parse("2026-11-23T10:00:00Z"));
    private EntrepriseService service;

    /** Horloge réglable : le cache et la limite de débit dépendent du temps. */
    static final class MutableClock extends java.time.Clock {
        private java.time.Instant instant;

        MutableClock(java.time.Instant instant) {
            this.instant = instant;
        }

        void avancer(java.time.Duration duree) {
            instant = instant.plus(duree);
        }

        @Override
        public java.time.ZoneId getZone() {
            return java.time.ZoneOffset.UTC;
        }

        @Override
        public java.time.Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public java.time.Instant instant() {
            return instant;
        }
    }

    @BeforeEach
    void preparer() {
        Texte syntec = mock(Texte.class);
        when(syntec.getIdcc()).thenReturn(1486);
        when(syntec.getTitre()).thenReturn("Convention collective nationale Syntec");
        when(textes.findByIdccIn(anyCollection())).thenAnswer(i -> i.<java.util.Collection<Integer>>getArgument(0)
                .contains(1486) ? List.of(syntec) : List.of());
        service = new EntrepriseService(textes, builder, horloge);
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
    void siret_absent_de_la_reponse_conventions_de_l_entreprise_pas_du_siege() {
        annuaire.expect(requestTo("https://annuaire.test/search?q=47976684299999&per_page=5&page=1"))
                .andRespond(withSuccess(REPONSE, MediaType.APPLICATION_JSON));

        Entreprise e = service.chercher("47976684299999").getFirst();

        assertThat(e.siret()).as("l'établissement cherché n'est pas celui décrit").isNull();
        assertThat(e.commune()).isEqualTo("ISSY-LES-MOULINEAUX");
        assertThat(e.conventions()).extracting(Convention::idcc).containsExactly(1486);
    }

    @Test
    void par_siren_conventions_de_l_entreprise_titres_lus_en_une_requete() {
        annuaire.expect(requestTo("https://annuaire.test/search?q=479766842&per_page=5&page=1"))
                .andRespond(withSuccess(REPONSE.replace("\"results\": [{", "\"results\": [{\"x\": 1, ")
                        .replace("}], \"total_results\": 1", "}, {\"siren\": \"1\", \"nom_complet\": \"AUTRE\", "
                                + "\"complements\": {\"liste_idcc\": [\"1486\", \"2120\", \"\", \"abc\"]}, \"siege\": {}}], \"total_results\": 2"),
                        MediaType.APPLICATION_JSON));

        List<Entreprise> r = service.chercher("479 766 842");

        assertThat(r).extracting(Entreprise::nom).containsExactly("CAPGEMINI TECHNOLOGY SERVICES", "AUTRE");
        assertThat(r.get(1).conventions()).as("IDCC illisibles ignorés").extracting(Convention::idcc)
                .containsExactly(1486, 2120);
        assertThat(r.get(1).commune()).isNull();
        verify(textes, times(1)).findByIdccIn(anyCollection());  // 1486 et 2120 : une seule requête
    }

    @Test
    void aucun_resultat_liste_vide() {
        annuaire.expect(requestTo("https://annuaire.test/search?q=introuvable&per_page=5&page=1"))
                .andRespond(withSuccess("{\"results\": [], \"total_results\": 0}", MediaType.APPLICATION_JSON));

        assertThat(service.chercher("introuvable")).isEmpty();
    }

    @Test
    void reponse_illisible_503() {
        annuaire.expect(requestTo("https://annuaire.test/search?q=capgemini&per_page=5&page=1"))
                .andRespond(withSuccess("<html>maintenance</html>", MediaType.TEXT_HTML));

        assertThatThrownBy(() -> service.chercher("capgemini")).isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("503");
    }

    @Test
    void reponse_sans_liste_de_resultats_503_et_non_aucun_resultat() {
        annuaire.expect(requestTo("https://annuaire.test/search?q=capgemini&per_page=5&page=1"))
                .andRespond(withSuccess("{\"message\": \"erreur\"}", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> service.chercher("capgemini")).isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("503");
    }

    @Test
    void annuaire_indisponible_503() {
        annuaire.expect(requestTo("https://annuaire.test/search?q=capgemini&per_page=5&page=1"))
                .andRespond(withServerError());

        assertThatThrownBy(() -> service.chercher("capgemini")).isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("503").hasMessageContaining("annuaire des entreprises ne répond pas");
    }

    @Test
    void meme_recherche_servie_par_le_cache_pendant_une_heure() {
        annuaire.expect(org.springframework.test.web.client.ExpectedCount.twice(),
                        requestTo("https://annuaire.test/search?q=capgemini&per_page=5&page=1"))
                .andRespond(withSuccess(REPONSE, MediaType.APPLICATION_JSON));

        service.chercher("capgemini");
        horloge.avancer(java.time.Duration.ofMinutes(59));
        assertThat(service.chercher("  CapGemini ")).as("même recherche, casse et espaces près").hasSize(1);
        horloge.avancer(java.time.Duration.ofMinutes(2));
        service.chercher("capgemini");

        annuaire.verify();
    }

    @Test
    void au_dela_de_cinq_appels_par_seconde_429_puis_de_nouveau_possible() {
        annuaire.expect(org.springframework.test.web.client.ExpectedCount.times(6),
                        org.springframework.test.web.client.match.MockRestRequestMatchers.method(org.springframework.http.HttpMethod.GET))
                .andRespond(withSuccess(REPONSE, MediaType.APPLICATION_JSON));
        for (int i = 0; i < 5; i++) {
            service.chercher("entreprise " + i);
        }

        assertThatThrownBy(() -> service.chercher("entreprise 5")).isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("429");
        assertThat(service.chercher("entreprise 0")).as("le cache ne compte pas").hasSize(1);
        horloge.avancer(java.time.Duration.ofMillis(1001));
        assertThat(service.chercher("entreprise 5")).hasSize(1);
        annuaire.verify();
    }
}
