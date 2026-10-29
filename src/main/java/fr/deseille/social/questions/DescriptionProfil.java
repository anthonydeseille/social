package fr.deseille.social.questions;

import fr.deseille.social.consultation.VuesConsultation.TexteResume;
import fr.deseille.social.profil.ProfilSalarie;
import fr.deseille.social.profil.ProfilService.ContexteProfil;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Décrit en français le contexte d'un profil pour le modèle : uniquement les éléments utiles au
 * raisonnement juridique, aucune donnée d'identité.
 */
final class DescriptionProfil {

    private DescriptionProfil() {
    }

    static String decrire(ContexteProfil contexte) {
        ProfilSalarie p = contexte.profil();
        List<String> lignes = new ArrayList<>();
        lignes.add("Profil du salarié, à la date du " + p.dateReference() + " :");
        lignes.add("- Convention collective : " + convention(contexte));
        if (p.statut() != null) {
            lignes.add("- Statut : " + (p.statut() == ProfilSalarie.Statut.CADRE ? "cadre" : "non cadre"));
        }
        if (p.classification() != null && !p.classification().isBlank()) {
            lignes.add("- Classification : " + p.classification().strip());
        }
        if (p.typeContrat() != null) {
            lignes.add("- Contrat : " + p.typeContrat() + (Boolean.TRUE.equals(p.tempsPartiel()) ? ", à temps partiel" : ""));
        } else if (Boolean.TRUE.equals(p.tempsPartiel())) {
            lignes.add("- Contrat à temps partiel");
        }
        if (contexte.anciennete() != null) {
            lignes.add("- Ancienneté : " + contexte.anciennete().annees() + " an(s) et " + contexte.anciennete().mois()
                    + " mois (entrée le " + p.dateEntree() + ")");
        }
        if (p.age() != null) {
            lignes.add("- Âge : " + p.age() + " ans");
        }
        if (!p.situations().isEmpty()) {
            lignes.add("- Situations : " + String.join(", ", p.situations().stream()
                    .map(s -> s.name().toLowerCase(Locale.ROOT).replace('_', ' ')).sorted().toList()));
        }
        lignes.add("Les informations absentes ne sont pas connues.");
        contexte.avertissements().forEach(a -> lignes.add("Attention : " + a));
        return String.join("\n", lignes);
    }

    private static String convention(ContexteProfil contexte) {
        if (contexte.profil().idcc() == null) {
            return "non renseignée";
        }
        return contexte.textesApplicables().stream()
                .filter(t -> t.idcc() != null)
                .findFirst()
                .map(TexteResume::titre)
                .map(titre -> titre.strip() + " (IDCC " + contexte.profil().idcc() + ")")
                .orElse("IDCC " + contexte.profil().idcc() + ", non intégrée");
    }
}
