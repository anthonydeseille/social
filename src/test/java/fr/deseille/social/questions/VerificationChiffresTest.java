package fr.deseille.social.questions;

import static org.assertj.core.api.Assertions.assertThat;

import fr.deseille.social.consultation.VuesConsultation.ArticleDetail;
import fr.deseille.social.consultation.VuesConsultation.TexteResume;
import fr.deseille.social.corpus.EtatJuridique;
import fr.deseille.social.corpus.TypeTexte;
import fr.deseille.social.profil.ProfilSalarie;
import fr.deseille.social.profil.ProfilService.Anciennete;
import fr.deseille.social.profil.ProfilService.ContexteProfil;
import fr.deseille.social.questions.Assistant.Passage;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class VerificationChiffresTest {

    private static final TexteResume CODE = new TexteResume("LEGITEXT", TypeTexte.CODE, null, "Code du travail",
            EtatJuridique.VIGUEUR);
    private static final ArticleDetail L1234_9 = new ArticleDetail("LEGIARTI1", "C1", "L1234-9", EtatJuridique.VIGUEUR,
            true, null, null, "Le salarié licencié comptant huit mois d'ancienneté ininterrompus a droit à une indemnité. "
            + "Elle ne peut être inférieure à un quart de mois de salaire par année d'ancienneté jusqu'à dix ans, "
            + "puis un tiers de mois au-delà.", null, null, null, CODE, List.of());
    private static final ArticleDetail CONVENTION = new ArticleDetail("KALIARTI1", "C2", "4.2", EtatJuridique.VIGUEUR,
            true, null, null, null, "<p>Préavis : <strong>quatre-vingt-dix jours</strong> ; plafond de 1 500,50 € "
            + "et 25 % du salaire.</p>", null, null, CODE, List.of());
    private static final ContexteProfil CONTEXTE = new ContexteProfil(
            new ProfilSalarie(1486, LocalDate.of(2019, 3, 15), 41, null, "ETAM coefficient 250", null, null, null,
                    LocalDate.of(2026, 9, 1)),
            new Anciennete(7, 5, 89), List.of(CODE), true, List.of());

    private static List<String> verifier(String... passages) {
        return VerificationChiffres.nonRetrouves(
                java.util.Arrays.stream(passages).map(t -> new Passage(t, List.of())).toList(),
                List.of(L1234_9, CONVENTION), CONTEXTE, "Quelle indemnité après mon licenciement ?");
    }

    @Test
    void chiffres_des_articles_en_lettres_ou_en_chiffres_retrouves() {
        assertThat(verifier("Après 8 mois d'ancienneté, l'indemnité vaut 1/4 de mois par année jusqu'à 10 ans, "
                + "puis 1/3 de mois.", "Le préavis est de 90 jours, dans la limite de 1 500,50 € et de 25 % du salaire."))
                .isEmpty();
    }

    @Test
    void chiffres_du_profil_retrouves() {
        assertThat(verifier("Avec 7 ans et 5 mois d'ancienneté (89 mois), à 41 ans, entré le 15 mars 2019, "
                + "coefficient 250, IDCC 1486, au 1er septembre 2026.")).isEmpty();
    }

    @Test
    void chiffre_sans_source_signale_une_fois() {
        assertThat(verifier("Votre indemnité est de 1,75 mois de salaire, soit 4 200 €.", "Comptez 1,75 mois."))
                .containsExactly("1,75", "4 200");
    }

    @Test
    void numeros_d_articles_et_unite_non_controles() {
        assertThat(verifier("Selon l'article L1234-9 et l'article 4.2, 1 seul préavis s'applique.")).isEmpty();
    }

    @Test
    void nombres_en_lettres() {
        assertThat(VerificationChiffres.nombresEnLettres("quatre-vingt-dix jours, vingt-cinq ans, trois cents heures, "
                + "soixante et onze, deux mille"))
                .contains("90", "25", "300", "71", "2000").doesNotContain("73000");
        assertThat(VerificationChiffres.nombresEnLettres("trois et quatre jours, vingt et un ans"))
                .contains("3", "4", "21").doesNotContain("7");
    }

    @Test
    void valeurs_normalisees() {
        assertThat(VerificationChiffres.cle(VerificationChiffres.valeur("1 500,50"))).isEqualTo("1500.5");
        assertThat(VerificationChiffres.cle(VerificationChiffres.valeur("90,0"))).as("pas 9E+1").isEqualTo("90");
        assertThat(VerificationChiffres.nombres("4,0 mois")).isEqualTo(VerificationChiffres.nombres("4 mois"));
    }
}
