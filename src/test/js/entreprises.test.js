// Tests de la mise en forme de la recherche d'entreprise (src/main/resources/static/entreprises.js),
// sans navigateur : node src/test/js/entreprises.test.js (lancé par le CI).
"use strict";

const assert = require("assert");
const path = require("path");
const Entreprises = require(path.join(__dirname, "../../main/resources/static/entreprises.js"));

const tests = {
  "convention intégrée : numéro et titre court"() {
    assert.strictEqual(Entreprises.libelle({ idcc: 1486, titre: "Convention collective nationale des bureaux d'études techniques (Syntec)", integree: true }),
      "IDCC 1486 - bureaux d'études techniques (Syntec)");
  },

  "convention non intégrée : dit que la réponse portera sur le Code"() {
    assert.strictEqual(Entreprises.libelle({ idcc: 44, titre: null, integree: false }),
      "IDCC 0044 (pas encore intégrée : réponses sur le seul Code du travail)");
  },

  "résultats : nom, commune et SIRET ou SIREN"() {
    const f = Entreprises.mettreEnForme([
      { nom: "ACME", siren: "123456789", siret: null, commune: "LYON", conventions: [{ idcc: 1486, titre: "Syntec", integree: true }] },
      { nom: "ACME SUD", siren: "987654321", siret: "98765432100012", commune: null, conventions: [] }
    ]);
    assert.strictEqual(f.message, null);
    assert.deepStrictEqual(f.resultats.map(r => [r.titre, r.detail]),
      [["ACME", "LYON · SIREN 123456789"], ["ACME SUD", "SIRET 98765432100012"]]);
    assert.deepStrictEqual(f.resultats[0].conventions, [{ idcc: 1486, libelle: "IDCC 1486 - Syntec", integree: true }]);
  },

  "rien trouvé, ou aucune convention connue : un message"() {
    assert.match(Entreprises.mettreEnForme([]).message, /Aucune entreprise trouvée/);
    assert.match(Entreprises.mettreEnForme([{ nom: "X", siren: "1", conventions: [] }]).message, /n'indique pas de convention/);
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
