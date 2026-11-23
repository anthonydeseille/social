package fr.deseille.social.entreprise;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

@WebMvcTest(EntrepriseController.class)
class EntrepriseControllerTest {

    @Autowired
    private MockMvcTester mvc;
    @MockitoBean
    private EntrepriseService service;

    @Test
    void recherche_trop_courte_ou_absente_refusee() {
        assertThat(mvc.get().uri("/api/entreprises?q=ab")).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(mvc.get().uri("/api/entreprises?q=")).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(mvc.get().uri("/api/entreprises")).hasStatus(HttpStatus.BAD_REQUEST);
    }

    @Test
    void resultats_en_json() {
        when(service.chercher("acme")).thenReturn(List.of(new EntrepriseService.Entreprise("ACME", "123456789", null,
                "LYON", List.of(new EntrepriseService.Convention(1486, "Syntec", true)))));

        assertThat(mvc.get().uri("/api/entreprises?q=acme")).hasStatusOk().bodyJson()
                .extractingPath("$[0].conventions[0].idcc").isEqualTo(1486);
    }
}
