package fr.deseille.social.questions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import fr.deseille.social.calcul.CalculIndemniteService;
import fr.deseille.social.consultation.ConsultationService;
import fr.deseille.social.corpus.TexteRepository;
import fr.deseille.social.profil.ProfilSalarie;
import fr.deseille.social.profil.ProfilService;
import fr.deseille.social.profil.ProfilService.ContexteProfil;
import fr.deseille.social.questions.Assistant.VersionsConsignes;
import fr.deseille.social.recherche.RechercheService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.InputStream;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.mockito.ArgumentCaptor;
import org.springframework.web.server.ResponseStatusException;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * Jeu adversarial des garde-fous d'entrée ({@code src/test/resources/evaluation/adversarial.yaml}),
 * rejoué sur le vrai {@link QuestionService} et le vrai {@link ProfilService}, le modèle et la
 * recherche simulés : gratuit, dans chaque build.
 */
class GardeFousTest {

    private static final Clock HORLOGE = Clock.fixed(java.time.Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);

    @SuppressWarnings("unchecked")
    @TestFactory
    Stream<DynamicTest> jeu_adversarial() throws Exception {
        List<Map<String, Object>> cas;
        try (InputStream in = getClass().getResourceAsStream("/evaluation/adversarial.yaml")) {
            Map<String, Object> racine = new Yaml(new SafeConstructor(new LoaderOptions())).load(in);
            cas = (List<Map<String, Object>>) racine.get("cas");
        }
        assertThat(cas).extracting(c -> c.get("type")).contains("injection", "donnees_personnelles",
                "profil_incoherent", "legitime");
        return cas.stream().map(c -> DynamicTest.dynamicTest(c.get("type") + " : " + c.get("question"), () -> rejouer(c)));
    }

    private static void rejouer(Map<String, Object> cas) {
        TexteRepository textes = mock(TexteRepository.class);
        Assistant assistant = mock(Assistant.class);
        when(assistant.consignes()).thenReturn(VersionsConsignes.INCONNUES);
        when(assistant.modele()).thenReturn("simule");
        when(assistant.reformuler(anyString(), any())).thenReturn(List.of("requête"));
        RechercheService recherche = mock(RechercheService.class);
        SimpleMeterRegistry registre = new SimpleMeterRegistry();
        QuestionService service = new QuestionService(new ProfilService(textes, HORLOGE), recherche,
                mock(ConsultationService.class), assistant, new MetriquesQuestions(registre),
                new CalculsQuestions(new CalculIndemniteService(List.of())));

        String question = (String) cas.get("question");
        ProfilSalarie profil = new ProfilSalarie(null, date(cas.get("dateEntree")), (Integer) cas.get("age"), null,
                (String) cas.get("classification"), null, null, null, date(cas.get("dateReference")));

        switch ((String) cas.get("type")) {
            case "injection" -> {
                assertThatThrownBy(() -> service.repondre(question, profil)).isInstanceOf(ResponseStatusException.class)
                        .hasMessageContaining("instruction adressée à l'assistant");
                verify(assistant, never()).reformuler(anyString(), any());
                assertThat(registre.counter("droits.garde_fous", "type", "injection").count()).isEqualTo(1.0);
            }
            case "profil_incoherent" -> {
                assertThatThrownBy(() -> service.repondre(question, profil)).isInstanceOf(ResponseStatusException.class);
                verify(assistant, never()).reformuler(anyString(), any());
                assertThat(registre.counter("droits.garde_fous", "type", "profil_incoherent").count()).isEqualTo(1.0);
            }
            case "donnees_personnelles" -> {
                QuestionService.ReponseQuestion reponse = service.repondre(question, profil);
                ArgumentCaptor<String> envoyee = ArgumentCaptor.forClass(String.class);
                ArgumentCaptor<ContexteProfil> contexte = ArgumentCaptor.forClass(ContexteProfil.class);
                verify(assistant).reformuler(envoyee.capture(), contexte.capture());
                assertThat(envoyee.getValue()).isEqualTo(cas.getOrDefault("recu", question));
                if (cas.containsKey("classification_recue")) {
                    assertThat(contexte.getValue().profil().classification()).isEqualTo(cas.get("classification_recue"));
                }
                assertThat(reponse.avertissements()).anyMatch(a -> a.startsWith("Données personnelles retirées"));
                double comptees = registre.counter("droits.garde_fous", "type", "donnees_personnelles").count();
                assertThat(comptees).isPositive();
                if (cas.containsKey("retraits")) {
                    assertThat(comptees).as("une par donnée retirée").isEqualTo(((Number) cas.get("retraits")).doubleValue());
                }
            }
            case "legitime" -> {
                QuestionService.ReponseQuestion reponse = service.repondre(question, profil);
                ArgumentCaptor<String> envoyee = ArgumentCaptor.forClass(String.class);
                ArgumentCaptor<ContexteProfil> contexte = ArgumentCaptor.forClass(ContexteProfil.class);
                verify(assistant).reformuler(envoyee.capture(), contexte.capture());
                assertThat(envoyee.getValue()).as("question transmise intacte").isEqualTo(question);
                assertThat(contexte.getValue().profil().classification()).isEqualTo(cas.get("classification"));
                assertThat(reponse.avertissements()).noneMatch(a -> a.startsWith("Données personnelles retirées"));
            }
            default -> throw new IllegalArgumentException("Type de cas inconnu : " + cas.get("type"));
        }
    }

    private static LocalDate date(Object valeur) {
        return valeur == null ? null : LocalDate.parse(valeur.toString());
    }
}
