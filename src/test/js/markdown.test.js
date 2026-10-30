// Tests de l'affichage Markdown des réponses (src/main/resources/static/markdown.js), sans
// navigateur : node src/test/js/markdown.test.js (lancé par le CI).
"use strict";

const assert = require("assert");
const path = require("path");
const Markdown = require(path.join(__dirname, "../../main/resources/static/markdown.js"));

// Faux document : juste ce qu'utilise markdown.js, et un rendu en HTML échappé pour comparer
function fauxDocument() {
  const echapper = t => t.replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;");
  function noeud(balise) {
    return {
      balise: balise, enfants: [], textContent: "", className: "",
      append(...n) { this.enfants.push(...n); },
      html() {
        const interieur = this.textContent ? echapper(this.textContent) : this.enfants.map(e => e.html()).join("");
        return balise === "br" || balise === "hr" ? "<" + balise + ">" : "<" + balise + ">" + interieur + "</" + balise + ">";
      }
    };
  }
  return {
    createElement: noeud,
    createTextNode: t => ({ html: () => echapper(t) })
  };
}

function rendu(texte, citation) {
  const document = fauxDocument();
  const racine = document.createElement("div");
  Markdown.construire(document, Markdown.analyser(texte), racine, citation || (i => {
    const lien = document.createElement("a");
    lien.textContent = "c" + i;
    return lien;
  }));
  return racine.enfants.map(e => e.html()).join("");
}

const tests = {
  gras_italique_et_code() {
    assert.strictEqual(rendu("La période est de **4 mois** pour les *cadres* (`L1221-19`)."),
      "<p>La période est de <strong>4 mois</strong> pour les <em>cadres</em> (<code>L1221-19</code>).</p>");
  },

  titres_ramenes_aux_niveaux_3_et_4() {
    assert.strictEqual(rendu("## En bref\n\nOui.\n\n### Détails"), "<h3>En bref</h3><p>Oui.</p><h4>Détails</h4>");
  },

  listes_a_puces_numerotees_et_imbriquees() {
    assert.strictEqual(rendu("Conditions :\n- être enceinte\n- l'avoir signalé\n  1. par lettre\n  2. ou certificat\n- suite\n  sur deux lignes"),
      "<p>Conditions :</p><ul><li>être enceinte</li><li>l'avoir signalé<ol><li>par lettre</li><li>ou certificat</li></ol></li>"
      + "<li>suite<br>sur deux lignes</li></ul>");
  },

  tableau() {
    assert.strictEqual(rendu("| Texte | Durée |\n|---|:---:|\n| Code | **4 mois** |\n| Syntec | 3 mois |"),
      "<div><table><thead><tr><th>Texte</th><th>Durée</th></tr></thead><tbody><tr><td>Code</td><td><strong>4 mois</strong></td></tr>"
      + "<tr><td>Syntec</td><td>3 mois</td></tr></tbody></table></div>");
  },

  retrait_et_separateur() {
    assert.strictEqual(rendu("> Information générale.\n\n---\n\nFin"),
      "<blockquote><p>Information générale.</p></blockquote><hr><p>Fin</p>");
  },

  citations_a_leur_place_meme_dans_le_gras() {
    const texte = "Vous êtes **protégée" + Markdown.marqueur(0) + " pendant la grossesse**" + Markdown.marqueur(1) + ".";
    assert.strictEqual(rendu(texte), "<p>Vous êtes <strong>protégée<a>c0</a> pendant la grossesse</strong><a>c1</a>.</p>");
  },

  marque_de_gras_orpheline_retiree_asterisque_isole_garde() {
    assert.strictEqual(rendu("Début **sans fin\n\nSuite"), "<p>Début sans fin</p><p>Suite</p>");
    assert.strictEqual(rendu("Soit 3 * 2 jours"), "<p>Soit 3 * 2 jours</p>");
  },

  html_du_modele_jamais_interprete() {
    assert.strictEqual(rendu("<script>alert(1)</script> et **<img src=x onerror=alert(1)>**"),
      "<p>&lt;script&gt;alert(1)&lt;/script&gt; et <strong>&lt;img src=x onerror=alert(1)&gt;</strong></p>");
  },

  citation_inconnue_ignoree() {
    assert.strictEqual(rendu("Texte" + Markdown.marqueur(5), () => null), "<p>Texte</p>");
  },

  retours_a_la_ligne_conserves_dans_un_paragraphe() {
    assert.strictEqual(rendu("Ligne 1\nLigne 2"), "<p>Ligne 1<br>Ligne 2</p>");
  }
};

let echecs = 0;
for (const nom of Object.keys(tests)) {
  try {
    tests[nom]();
    console.log("ok      " + nom);
  } catch (e) {
    echecs++;
    console.log("ÉCHEC   " + nom + "\n" + e.message);
  }
}
console.log(Object.keys(tests).length + " tests, " + echecs + " échec(s)");
process.exit(echecs ? 1 : 0);
