// Affichage du Markdown des réponses, sans bibliothèque et sans innerHTML : le texte est d'abord
// analysé en un arbre (fonction pure, testée par src/test/js/markdown.test.js), puis l'arbre est
// construit avec createElement et textContent. Aucun HTML venu du modèle n'est donc interprété.
// Les citations sont des marqueurs (n) placés dans le texte avant l'analyse : la mise
// en forme peut ainsi chevaucher plusieurs passages sans perdre les citations.
"use strict";

(function (racine) {
  const DEBUT_CITATION = "";
  const FIN_CITATION = "";

  // --- Éléments en ligne : gras, italique, code, citations ---

  function enLigne(texte) {
    const noeuds = [];
    let tampon = "";
    let i = 0;
    const vider = () => {
      if (tampon) noeuds.push({ type: "texte", valeur: tampon });
      tampon = "";
    };
    while (i < texte.length) {
      const c = texte[i];
      if (c === DEBUT_CITATION) {
        const fin = texte.indexOf(FIN_CITATION, i + 1);
        if (fin > i) {
          vider();
          noeuds.push({ type: "citation", index: Number(texte.slice(i + 1, fin)) });
          i = fin + 1;
          continue;
        }
      }
      if (c === "`") {
        const fin = texte.indexOf("`", i + 1);
        if (fin > i + 1) {
          vider();
          noeuds.push({ type: "code", valeur: texte.slice(i + 1, fin) });
          i = fin + 1;
          continue;
        }
      }
      // Gras et italique avec « * » seulement : « _ » apparaît dans des identifiants
      if (c === "*" && texte[i + 1] === "*") {
        const fin = texte.indexOf("**", i + 2);
        if (fin > i + 2) {
          vider();
          noeuds.push({ type: "gras", enfants: enLigne(texte.slice(i + 2, fin)) });
          i = fin + 2;
          continue;
        }
        // Marque orpheline collée à un mot (gras ouvert dans un autre bloc) : retirée plutôt
        // qu'affichée ; isolée entre deux blancs, c'est du texte
        const avant = i === 0 ? " " : texte[i - 1];
        const apres = i + 2 >= texte.length ? " " : texte[i + 2];
        if (!(/\s/.test(avant) && /\s/.test(apres))) {
          i += 2;
          continue;
        }
        tampon += "**";
        i += 2;
        continue;
      }
      // « * » isolé et suivi d'une espace reste du texte (multiplication)
      if (c === "*" && texte[i + 1] && texte[i + 1] !== " ") {
        const fin = texte.indexOf("*", i + 1);
        if (fin > i + 1 && texte[fin - 1] !== " ") {
          vider();
          noeuds.push({ type: "italique", enfants: enLigne(texte.slice(i + 1, fin)) });
          i = fin + 1;
          continue;
        }
      }
      tampon += c;
      i++;
    }
    vider();
    return noeuds;
  }

  // --- Blocs : titres, paragraphes, listes, tableaux, citations en retrait, séparateurs ---

  const TITRE = /^(#{1,6})\s+(.*?)\s*#*\s*$/;
  const SEPARATEUR = /^\s{0,3}([-*_])(\s*\1){2,}\s*$/;
  const PUCE = /^(\s*)([-*+]|\d{1,3}[.)])\s+(.*)$/;
  const LIGNE_TABLEAU = /^\s*\|.*\|\s*$/;
  const ALIGNEMENT_TABLEAU = /^\s*\|?(\s*:?-{2,}:?\s*\|)+\s*(:?-{2,}:?\s*)?\|?\s*$/;

  // Citations en fin de ligne, mises de côté pour reconnaître la structure (rangée de tableau,
  // séparateur) puis replacées dans le dernier élément du bloc
  const MARQUEURS_FIN = /((?:\uE000\d+\uE001)+)\s*$/;

  function sansMarqueursFin(ligne) {
    const m = MARQUEURS_FIN.exec(ligne);
    return m ? { ligne: ligne.slice(0, m.index), marqueurs: m[1] } : { ligne: ligne, marqueurs: "" };
  }

  function cellules(ligneBrute) {
    const l = sansMarqueursFin(ligneBrute);
    const textes = l.ligne.trim().replace(/^\|/, "").replace(/\|$/, "").split("|").map(c => c.trim());
    textes[textes.length - 1] += l.marqueurs;
    return textes.map(enLigne);
  }

  function estRangee(ligne) {
    return LIGNE_TABLEAU.test(sansMarqueursFin(ligne).ligne);
  }

  function analyser(texte) {
    const lignes = texte.replace(/\r\n?/g, "\n").split("\n");
    const blocs = [];
    let paragraphe = [];
    const finirParagraphe = () => {
      if (paragraphe.length) blocs.push({ type: "paragraphe", enfants: enLigne(paragraphe.join("\n")) });
      paragraphe = [];
    };

    for (let i = 0; i < lignes.length; i++) {
      const ligne = lignes[i];
      if (!ligne.trim()) {
        finirParagraphe();
        continue;
      }
      let m = TITRE.exec(ligne);
      if (m) {
        finirParagraphe();
        blocs.push({ type: "titre", niveau: m[1].length, enfants: enLigne(m[2]) });
        continue;
      }
      const separateur = sansMarqueursFin(ligne);
      if (SEPARATEUR.test(separateur.ligne)) {
        finirParagraphe();
        // Citation d'un séparateur : gardée, dans un paragraphe à part
        if (separateur.marqueurs) blocs.push({ type: "paragraphe", enfants: enLigne(separateur.marqueurs) });
        blocs.push({ type: "separateur" });
        continue;
      }
      if (estRangee(ligne) && i + 1 < lignes.length && ALIGNEMENT_TABLEAU.test(lignes[i + 1])) {
        finirParagraphe();
        const entete = cellules(ligne);
        const rangees = [];
        i += 2;
        while (i < lignes.length && estRangee(lignes[i])) {
          rangees.push(cellules(lignes[i]));
          i++;
        }
        i--;
        blocs.push({ type: "tableau", entete: entete, rangees: rangees });
        continue;
      }
      if (/^\s*>/.test(ligne)) {
        finirParagraphe();
        const contenu = [];
        while (i < lignes.length && /^\s*>/.test(lignes[i])) {
          contenu.push(lignes[i].replace(/^\s*>\s?/, ""));
          i++;
        }
        i--;
        blocs.push({ type: "retrait", enfants: analyser(contenu.join("\n")) });
        continue;
      }
      if (PUCE.test(ligne)) {
        finirParagraphe();
        const elements = [];
        while (i < lignes.length && (PUCE.test(lignes[i]) || (/^\s+\S/.test(lignes[i]) && elements.length))) {
          const p = PUCE.exec(lignes[i]);
          if (p) {
            elements.push({ retrait: p[1].replace(/\t/g, "    ").length, ordonnee: /\d/.test(p[2]), texte: p[3] });
          } else {
            // Ligne de continuation d'un élément
            elements[elements.length - 1].texte += "\n" + lignes[i].trim();
          }
          i++;
        }
        i--;
        blocs.push(liste(elements, 0, elements.length));
        continue;
      }
      paragraphe.push(ligne.trim());
    }
    finirParagraphe();
    return blocs;
  }

  // Liste et sous-listes, d'après le retrait de chaque élément par rapport au premier
  function liste(elements, debut, fin) {
    const base = elements[debut].retrait;
    const bloc = { type: "liste", ordonnee: elements[debut].ordonnee, elements: [] };
    let i = debut;
    while (i < fin) {
      const element = { enfants: enLigne(elements[i].texte), sousListe: null };
      let j = i + 1;
      while (j < fin && elements[j].retrait > base) j++;
      if (j > i + 1) element.sousListe = liste(elements, i + 1, j);
      bloc.elements.push(element);
      i = j;
    }
    return bloc;
  }

  // --- Construction du DOM ---

  function construireEnLigne(document, noeuds, parent, citation) {
    for (const n of noeuds) {
      if (n.type === "texte") {
        // Retour à la ligne simple dans un paragraphe : conservé
        n.valeur.split("\n").forEach((morceau, k) => {
          if (k > 0) parent.append(document.createElement("br"));
          if (morceau) parent.append(document.createTextNode(morceau));
        });
      } else if (n.type === "citation") {
        const lien = citation(n.index);
        if (lien) parent.append(lien);
      } else if (n.type === "code") {
        const code = document.createElement("code");
        code.textContent = n.valeur;
        parent.append(code);
      } else {
        const balise = document.createElement(n.type === "gras" ? "strong" : "em");
        construireEnLigne(document, n.enfants, balise, citation);
        parent.append(balise);
      }
    }
  }

  function construireListe(document, bloc, citation) {
    const liste = document.createElement(bloc.ordonnee ? "ol" : "ul");
    for (const e of bloc.elements) {
      const li = document.createElement("li");
      construireEnLigne(document, e.enfants, li, citation);
      if (e.sousListe) li.append(construireListe(document, e.sousListe, citation));
      liste.append(li);
    }
    return liste;
  }

  /**
   * @param citation fonction (index) → élément à insérer pour la citation n° index
   */
  function construire(document, blocs, parent, citation) {
    for (const b of blocs) {
      if (b.type === "paragraphe") {
        const p = document.createElement("p");
        construireEnLigne(document, b.enfants, p, citation);
        parent.append(p);
      } else if (b.type === "titre") {
        // La réponse est sous le titre de la page : titres de niveau 3 et 4 seulement
        const h = document.createElement(b.niveau <= 2 ? "h3" : "h4");
        construireEnLigne(document, b.enfants, h, citation);
        parent.append(h);
      } else if (b.type === "separateur") {
        parent.append(document.createElement("hr"));
      } else if (b.type === "liste") {
        parent.append(construireListe(document, b, citation));
      } else if (b.type === "retrait") {
        const q = document.createElement("blockquote");
        construire(document, b.enfants, q, citation);
        parent.append(q);
      } else if (b.type === "tableau") {
        const cadre = document.createElement("div");
        cadre.className = "tableau";
        const table = document.createElement("table");
        const thead = document.createElement("thead");
        const entete = document.createElement("tr");
        b.entete.forEach(c => {
          const th = document.createElement("th");
          construireEnLigne(document, c, th, citation);
          entete.append(th);
        });
        thead.append(entete);
        const tbody = document.createElement("tbody");
        b.rangees.forEach(r => {
          const tr = document.createElement("tr");
          r.forEach(c => {
            const td = document.createElement("td");
            construireEnLigne(document, c, td, citation);
            tr.append(td);
          });
          tbody.append(tr);
        });
        table.append(thead, tbody);
        cadre.append(table);
        parent.append(cadre);
      }
    }
  }

  /** Marqueur d'une citation, à insérer dans le texte avant l'analyse. */
  function marqueur(index) {
    return DEBUT_CITATION + index + FIN_CITATION;
  }

  const Markdown = { analyser: analyser, construire: construire, marqueur: marqueur };
  if (typeof module !== "undefined" && module.exports) {
    module.exports = Markdown;
  } else {
    racine.Markdown = Markdown;
  }
})(this);
