// Recherche de l'entreprise, pour le salarié qui ne connaît pas sa convention collective : mise en
// forme des résultats de /api/entreprises (fonction pure, testée par src/test/js/entreprises.test.js).
"use strict";

(function (racine) {
  const PREFIXE = /^Convention collective nationale (de la |des |du |de l'|de )?/i;

  function libelle(convention) {
    const numero = "IDCC " + String(convention.idcc).padStart(4, "0");
    if (!convention.integree || !convention.titre) return numero + " (pas encore intégrée : réponses sur le seul Code du travail)";
    return numero + " - " + convention.titre.replace(PREFIXE, "");
  }

  /** [{ titre, detail, conventions: [{ idcc, libelle, integree }] }] ; message si rien d'utilisable. */
  function mettreEnForme(entreprises) {
    if (!entreprises || !entreprises.length) {
      return { message: "Aucune entreprise trouvée. Essayez le nom exact ou le SIRET (14 chiffres, sur votre fiche de paie).", resultats: [] };
    }
    const resultats = entreprises.map(e => ({
      titre: e.nom,
      detail: [e.commune, e.siret ? "SIRET " + e.siret : "SIREN " + e.siren].filter(Boolean).join(" · "),
      conventions: (e.conventions || []).map(c => ({ idcc: c.idcc, libelle: libelle(c), integree: c.integree }))
    }));
    const sansConvention = resultats.every(r => !r.conventions.length);
    return {
      message: sansConvention ? "L'annuaire n'indique pas de convention collective pour ces entreprises : vérifiez votre fiche de paie." : null,
      resultats: resultats
    };
  }

  const Entreprises = { mettreEnForme: mettreEnForme, libelle: libelle };
  if (typeof module !== "undefined" && module.exports) {
    module.exports = Entreprises;
  } else {
    racine.Entreprises = Entreprises;
  }
})(this);
