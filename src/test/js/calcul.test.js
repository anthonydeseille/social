// Tests de l'affichage du calcul (src/main/resources/static/calcul.js), sans navigateur :
// node src/test/js/calcul.test.js (lancé par le CI).
"use strict";

const assert = require("assert");
const path = require("path");
const Calcul = require(path.join(__dirname, "../../main/resources/static/calcul.js"));

// Faux document : juste ce qu'utilise calcul.js, rendu en HTML échappé pour comparer
function fauxDocument() {
  const echapper = t => t.replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;");
  return {
    createElement(balise) {
      return {
        balise: balise, enfants: [], textContent: "", className: "",
        append(...n) { this.enfants.push(...n); },
        html() {
          const interieur = this.textContent ? echapper(this.textContent) : this.enfants.map(e => e.html()).join("");
          return "<" + balise + ">" + interieur + "</" + balise + ">";
        }
      };
    }
  };
}

function rendu(calcul) {
  const document = fauxDocument();
  const conteneur = document.createElement("div");
  const affiche = Calcul.construire(document, calcul, conteneur);
  return { affiche: affiche, html: conteneur.enfants.map(e => e.html()).join("") };
}

const CADRE = {
  intitule: "indemnité de licenciement",
  impossible: null,
  resultat: {
    droit: true, absenceDroit: null, anneesAnciennete: 7, moisAnciennete: 1,
    legale: { regle: "Indemnité légale", mois: 1.77, detail: ["7,08 années × 1/4 de mois"], sources: [{ num: "R1234-2", texteSourceId: "LEGITEXT" }] },
    conventionnelle: { regle: "Convention Syntec (cadres)", mois: 2.36, detail: ["7,08 années × 1/3 de mois"], sources: [{ num: "4.5", texteSourceId: "KALI" }] },
    retenue: "CONVENTIONNELLE", moisRetenus: 2.36, montantBrut: 7083.33,
    hypotheses: ["Salaire mensuel brut de 3 000 € lu dans la question"],
    avertissements: ["Règles de calcul en cours de validation juridique."]
  }
};

const tests = {
  "nombres à la française"() {
    assert.strictEqual(Calcul.nombre(2.36), "2,36");
    assert.strictEqual(Calcul.nombre(1.5), "1,50");
    assert.strictEqual(Calcul.nombre(7083.33), "7 083,33");
    assert.strictEqual(Calcul.nombre(1234567), "1 234 567,00");
  },

  "résultat d'abord, puis détail, articles, hypothèses et avertissements"() {
    const forme = Calcul.mettreEnForme(CADRE);
    assert.strictEqual(forme.titre, "Calcul de l'indemnité de licenciement");
    assert.strictEqual(forme.principal, "2,36 mois de salaire brut (indemnité conventionnelle, plus favorable)");
    assert.strictEqual(forme.secondaire, "Soit 7 083,33 € brut");
    assert.deepStrictEqual(forme.sections.map(s => s.titre), ["Ancienneté retenue",
      "Indemnité légale : 1,77 mois de salaire", "Convention Syntec (cadres) : 2,36 mois de salaire",
      "Hypothèses", "Avertissements"]);
    assert.deepStrictEqual(forme.sections[2].lignes, ["7,08 années × 1/3 de mois", "Articles : 4.5"]);
  },

  "sans salaire, pas de montant en euros"() {
    const sansSalaire = JSON.parse(JSON.stringify(CADRE));
    sansSalaire.resultat.montantBrut = null;
    assert.strictEqual(Calcul.mettreEnForme(sansSalaire).secondaire, null);
  },

  "pas de droit : la raison, sans calcul légal ni conventionnel"() {
    const forme = Calcul.mettreEnForme({ intitule: "indemnité de licenciement", resultat: {
      droit: false, absenceDroit: "ancienneté inférieure à 8 mois", anneesAnciennete: 0, moisAnciennete: 6,
      legale: null, conventionnelle: null, retenue: null, moisRetenus: null, montantBrut: null,
      hypotheses: [], avertissements: [] } });
    assert.strictEqual(forme.principal, "Pas d'indemnité : ancienneté inférieure à 8 mois");
    assert.deepStrictEqual(forme.sections.map(s => s.titre), ["Ancienneté retenue"]);
  },

  "calcul impossible : la raison seule"() {
    const forme = Calcul.mettreEnForme({ intitule: "indemnité de licenciement", resultat: null,
      impossible: "la date d'entrée dans l'entreprise n'est pas renseignée dans le profil" });
    assert.strictEqual(forme.principal, "Calcul impossible : la date d'entrée dans l'entreprise n'est pas renseignée dans le profil.");
    assert.deepStrictEqual(forme.sections, []);
  },

  "pas de calcul : rien d'affiché"() {
    assert.strictEqual(rendu(null).affiche, false);
    assert.strictEqual(rendu(undefined).html, "");
  },

  "construit avec textContent : pas de HTML interprété"() {
    const piege = JSON.parse(JSON.stringify(CADRE));
    piege.resultat.hypotheses = ["<img src=x onerror=alert(1)>"];
    const { affiche, html } = rendu(piege);
    assert.strictEqual(affiche, true);
    assert.ok(html.startsWith("<h2>Calcul de l'indemnité de licenciement</h2><p>2,36 mois"), html);
    assert.ok(html.includes("<summary>Détail, articles et hypothèses</summary>"), html);
    assert.ok(html.includes("&lt;img src=x onerror=alert(1)&gt;"), html);
    assert.ok(!html.includes("<img"), html);
  }
};

let echecs = 0;
for (const [nom, test] of Object.entries(tests)) {
  try {
    test();
    console.log("ok - " + nom);
  } catch (e) {
    echecs++;
    console.log("ÉCHEC - " + nom + "\n" + e.message);
  }
}
if (echecs) {
  console.log(echecs + " échec(s)");
  process.exit(1);
}
