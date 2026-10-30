// Page minimale : profil gardé dans le navigateur, question, réponse avec citations cliquables.
// Tout texte venant de l'API est inséré avec textContent (jamais innerHTML) ; la réponse, en
// Markdown, est mise en forme par markdown.js selon le même principe.
"use strict";

const CLE_PROFIL = "droits.profil";
const formulaire = document.getElementById("formulaire");
const champs = ["idcc", "statut", "typeContrat", "dateEntree", "classification"];

// Le profil peut contenir des données sensibles (grossesse, santé) : il n'est gardé sur l'appareil
// que si la personne le demande, et peut être effacé à tout moment.
function lireProfilSauvegarde() {
  try {
    return JSON.parse(localStorage.getItem(CLE_PROFIL) || "{}");
  } catch {
    return {};
  }
}

function sauvegarderProfil(profil) {
  try {
    if (document.getElementById("memoriser").checked) localStorage.setItem(CLE_PROFIL, JSON.stringify(profil));
    else localStorage.removeItem(CLE_PROFIL);
  } catch {
    // stockage indisponible (navigation privée…) : le profil n'est simplement pas retenu
  }
}

function effacerProfil() {
  try { localStorage.removeItem(CLE_PROFIL); } catch { /* rien à effacer */ }
  for (const champ of champs) document.getElementById(champ).value = "";
  formulaire.querySelectorAll("input[type=checkbox]").forEach(c => { c.checked = false; });
}

/** Garde dans la liste une convention mémorisée qui n'y figure pas (non intégrée ou liste indisponible). */
function conserverConvention(idcc) {
  const liste = document.getElementById("idcc");
  if (!idcc || [...liste.options].some(o => Number(o.value) === Number(idcc))) return;
  const option = document.createElement("option");
  option.value = idcc;
  option.textContent = String(idcc).padStart(4, "0") + " - convention enregistrée sur cet appareil";
  liste.append(option);
}

/** Texte lisible d'un HTML d'article : sauts de ligne et paragraphes conservés, rien n'est exécuté. */
function texteDepuisHtml(html) {
  if (!html) return "";
  const doc = new DOMParser().parseFromString(html, "text/html");
  doc.querySelectorAll("br").forEach(br => br.replaceWith("\n"));
  doc.querySelectorAll("td, th").forEach(cellule => cellule.after("\t"));
  doc.querySelectorAll("p, div, li, tr, h1, h2, h3, h4, h5, h6, table, ul, ol").forEach(bloc => bloc.after("\n\n"));
  return doc.body.textContent.replace(/[ \t]+\n/g, "\n").replace(/\n{3,}/g, "\n\n").trim();
}

function profilDuFormulaire() {
  const profil = {};
  for (const champ of champs) {
    const valeur = document.getElementById(champ).value.trim();
    if (valeur) profil[champ] = champ === "idcc" ? Number(valeur) : valeur;
  }
  profil.tempsPartiel = document.getElementById("tempsPartiel").checked;
  profil.situations = [...formulaire.querySelectorAll("input[name=situations]:checked")].map(c => c.value);
  return profil;
}

function appliquerProfil(profil) {
  for (const champ of champs) {
    if (profil[champ] !== undefined) document.getElementById(champ).value = profil[champ];
  }
  document.getElementById("tempsPartiel").checked = Boolean(profil.tempsPartiel);
  for (const caseSituation of formulaire.querySelectorAll("input[name=situations]")) {
    caseSituation.checked = (profil.situations || []).includes(caseSituation.value);
  }
}

async function chargerConventions() {
  try {
    const reponse = await fetch("/api/textes");
    if (!reponse.ok) return;
    const textes = await reponse.json();
    const liste = document.getElementById("idcc");
    textes.filter(t => t.idcc)
      .sort((a, b) => a.idcc - b.idcc)
      .forEach(t => {
        const option = document.createElement("option");
        option.value = t.idcc;
        option.textContent = String(t.idcc).padStart(4, "0") + " - " + t.titre.replace(/^Convention collective nationale (de la |des |du |de l'|de )?/i, "");
        liste.append(option);
      });
  } catch {
    // liste indisponible : le salarié peut toujours répondre « Je ne sais pas »
  }
}

function element(tag, texte, classe) {
  const n = document.createElement(tag);
  if (texte !== undefined) n.textContent = texte;
  if (classe) n.className = classe;
  return n;
}

function afficherErreur(message) {
  const bloc = document.getElementById("erreur");
  bloc.textContent = message;
  bloc.hidden = false;
}

function afficherReponse(donnees) {
  const avertissements = document.getElementById("avertissements");
  avertissements.replaceChildren();
  if (donnees.avertissements && donnees.avertissements.length) {
    const liste = element("ul");
    donnees.avertissements.forEach(a => liste.append(element("li", a)));
    avertissements.append(liste);
    avertissements.hidden = false;
  }

  // Réponse en Markdown : passages mis bout à bout, chaque citation remplacée par un marqueur placé
  // avant les blancs de fin du passage (pour rester dans son paragraphe), puis analyse de l'ensemble
  const reponse = document.getElementById("reponse");
  reponse.replaceChildren();
  const citations = [];
  let texte = "";
  for (const passage of donnees.passages || []) {
    const vus = new Set();
    let marqueurs = "";
    for (const citation of passage.citations || []) {
      if (vus.has(citation.articleSourceId)) continue;
      vus.add(citation.articleSourceId);
      marqueurs += Markdown.marqueur(citations.length);
      citations.push(citation);
    }
    const contenu = passage.texte || "";
    const blancs = /\s*$/.exec(contenu)[0];
    texte += contenu.slice(0, contenu.length - blancs.length) + marqueurs + blancs;
  }
  Markdown.construire(document, Markdown.analyser(texte), reponse, index => {
    const citation = citations[index];
    if (!citation) return null;
    const lien = element("a", citation.num || "article", "citation");
    lien.href = "#";
    lien.title = citation.texteCite || "";
    lien.addEventListener("click", e => { e.preventDefault(); ouvrirArticle(citation.articleSourceId); });
    return lien;
  });

  const sources = document.getElementById("sources");
  sources.replaceChildren();
  if (donnees.sources && donnees.sources.length) {
    sources.append(element("h2", "Articles consultés"));
    const liste = element("ul");
    for (const source of donnees.sources) {
      const item = element("li");
      const lien = element("a", source.num || source.articleSourceId);
      lien.href = "#";
      lien.addEventListener("click", e => { e.preventDefault(); ouvrirArticle(source.articleSourceId); });
      item.append(lien, document.createTextNode(" (" + source.texteTitre + ")"));
      liste.append(item);
    }
    sources.append(liste);
    sources.hidden = false;
  }
}

let demandeArticle = 0;

async function ouvrirArticle(sourceId) {
  const numero = ++demandeArticle;
  const dialogue = document.getElementById("article");
  document.getElementById("article-titre").textContent = "Chargement…";
  document.getElementById("article-fil").textContent = "";
  document.getElementById("article-etat").textContent = "";
  document.getElementById("article-texte").textContent = "";
  dialogue.showModal();
  try {
    const reponse = await fetch("/api/articles/" + encodeURIComponent(sourceId));
    if (!reponse.ok) throw new Error();
    const a = await reponse.json();
    if (numero !== demandeArticle) return; // un autre article a été demandé entre-temps
    document.getElementById("article-fil").textContent =
      [a.texte.titre].concat((a.filAriane || []).map(s => s.titre)).join(" › ");
    document.getElementById("article-titre").textContent = "Article " + (a.num || "");
    document.getElementById("article-etat").textContent = a.applicable ? "En vigueur" : "Non applicable aujourd'hui (" + a.etat + ")";
    const texte = a.contenuTexte || texteDepuisHtml(a.contenuHtml);
    document.getElementById("article-texte").textContent = (texte || "").trim()
      + (a.nota ? "\n\nNota : " + texteDepuisHtml(a.nota) : "");
  } catch {
    if (numero === demandeArticle) document.getElementById("article-titre").textContent = "Article indisponible pour le moment.";
  }
}

const MESSAGES = {
  400: "La demande est incomplète ou incohérente : vérifiez votre question et vos dates.",
  429: "Le nombre maximal de questions pour aujourd'hui est atteint. Réessayez demain.",
  503: "Le service de réponse est momentanément indisponible. Réessayez dans quelques minutes."
};

formulaire.addEventListener("submit", async e => {
  e.preventDefault();
  const question = document.getElementById("question").value.trim();
  if (!question) {
    document.getElementById("question").focus();
    return;
  }
  const profil = profilDuFormulaire();
  sauvegarderProfil(profil);

  const bouton = document.getElementById("envoyer");
  const attente = document.getElementById("attente");
  document.getElementById("resultat").hidden = false;
  document.getElementById("erreur").hidden = true;
  document.getElementById("avertissements").hidden = true;
  document.getElementById("sources").hidden = true;
  document.getElementById("reponse").replaceChildren();
  bouton.disabled = true;
  attente.hidden = false;
  try {
    const reponse = await fetch("/api/questions", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ question, profil })
    });
    if (!reponse.ok) {
      let detail = "";
      try { detail = (await reponse.json()).detail || ""; } catch { /* corps absent */ }
      afficherErreur(reponse.status === 400 && detail ? detail : (MESSAGES[reponse.status] || "Une erreur est survenue. Réessayez plus tard."));
      return;
    }
    afficherReponse(await reponse.json());
  } catch {
    afficherErreur("Impossible de joindre le service. Vérifiez votre connexion et réessayez.");
  } finally {
    bouton.disabled = false;
    attente.hidden = true;
  }
});

document.getElementById("effacer").addEventListener("click", effacerProfil);
// Décocher « Se souvenir » efface tout de suite, sans attendre la prochaine question
document.getElementById("memoriser").addEventListener("change", e => {
  if (!e.target.checked) {
    try { localStorage.removeItem(CLE_PROFIL); } catch { /* rien à effacer */ }
  }
});

const profilSauvegarde = lireProfilSauvegarde();
document.getElementById("memoriser").checked = Object.keys(profilSauvegarde).length > 0;
chargerConventions().then(() => {
  conserverConvention(profilSauvegarde.idcc);
  appliquerProfil(profilSauvegarde);
});
