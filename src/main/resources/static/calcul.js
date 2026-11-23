// Affichage du calcul fait par l'application (champ « calcul » de la réponse) : le résultat d'abord,
// puis le détail, les articles, les hypothèses et les avertissements dans un bloc dépliable. Le
// contenu est d'abord mis en forme (fonction pure, testée par src/test/js/calcul.test.js), puis
// construit avec createElement et textContent, sans innerHTML.
"use strict";

(function (racine) {
  /** Écriture française d'un nombre venu de l'API : 2.36 → « 2,36 », 7083.33 → « 7 083,33 ». */
  function nombre(n, decimales) {
    const valeur = Number(n);
    if (!Number.isFinite(valeur)) return String(n);
    const fixe = valeur.toFixed(decimales === undefined ? 2 : decimales);
    const [entier, fraction] = fixe.split(".");
    const groupes = entier.replace(/\B(?=(\d{3})+(?!\d))/g, " ");
    return fraction ? groupes + "," + fraction : groupes;
  }

  const RETENUE = {
    LEGALE: "indemnité légale",
    CONVENTIONNELLE: "indemnité conventionnelle, plus favorable",
    IDENTIQUES: "indemnités légale et conventionnelle identiques"
  };

  function regle(calcul) {
    const lignes = (calcul.detail || []).slice();
    const articles = (calcul.sources || []).map(s => s.num).filter(Boolean);
    if (articles.length) lignes.push("Articles : " + articles.join(", "));
    return { titre: calcul.regle + " : " + nombre(calcul.mois) + " mois de salaire", lignes: lignes };
  }

  /**
   * Mise en forme du calcul : { titre, principal, secondaire, sections: [{ titre, lignes }] } ;
   * null s'il n'y a pas de calcul.
   */
  function mettreEnForme(calcul) {
    if (!calcul) return null;
    const titre = "Calcul de l'" + calcul.intitule;
    const r = calcul.resultat;
    if (!r) {
      return { titre: titre, principal: "Calcul impossible : " + calcul.impossible + ".", secondaire: null, sections: [] };
    }
    const sections = [{
      titre: "Ancienneté retenue",
      lignes: [r.anneesAnciennete + " an(s) et " + r.moisAnciennete + " mois"]
    }];
    let principal;
    let secondaire = null;
    if (!r.droit) {
      principal = "Pas d'indemnité : " + r.absenceDroit;
    } else {
      principal = nombre(r.moisRetenus) + " mois de salaire brut (" + RETENUE[r.retenue] + ")";
      if (r.montantBrut !== null && r.montantBrut !== undefined) secondaire = "Soit " + nombre(r.montantBrut) + " € brut";
      if (r.legale) sections.push(regle(r.legale));
      if (r.conventionnelle) sections.push(regle(r.conventionnelle));
    }
    if (r.hypotheses && r.hypotheses.length) sections.push({ titre: "Hypothèses", lignes: r.hypotheses });
    if (r.avertissements && r.avertissements.length) sections.push({ titre: "Avertissements", lignes: r.avertissements });
    return { titre: titre, principal: principal, secondaire: secondaire, sections: sections };
  }

  /** Construit le bloc dans conteneur (vidé au préalable) ; renvoie false s'il n'y a rien à afficher. */
  function construire(document, calcul, conteneur) {
    const forme = mettreEnForme(calcul);
    if (!forme) return false;
    const el = (balise, texte, classe) => {
      const n = document.createElement(balise);
      if (texte !== undefined) n.textContent = texte;
      if (classe) n.className = classe;
      return n;
    };
    conteneur.append(el("h2", forme.titre));
    conteneur.append(el("p", forme.principal, "principal"));
    if (forme.secondaire) conteneur.append(el("p", forme.secondaire, "secondaire"));
    if (forme.sections.length) {
      const details = el("details");
      details.append(el("summary", "Détail, articles et hypothèses"));
      for (const section of forme.sections) {
        details.append(el("h3", section.titre));
        const liste = el("ul");
        section.lignes.forEach(l => liste.append(el("li", l)));
        details.append(liste);
      }
      conteneur.append(details);
    }
    return true;
  }

  const Calcul = { mettreEnForme: mettreEnForme, construire: construire, nombre: nombre };
  if (typeof module !== "undefined" && module.exports) {
    module.exports = Calcul;
  } else {
    racine.Calcul = Calcul;
  }
})(this);
