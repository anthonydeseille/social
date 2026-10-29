// Page minimale : profil gardé dans le navigateur, question, réponse avec citations cliquables.
// Tout texte venant de l'API est inséré avec textContent (jamais innerHTML).
"use strict";

const CLE_PROFIL = "droits.profil";
const formulaire = document.getElementById("formulaire");
const champs = ["idcc", "statut", "typeContrat", "dateEntree", "classification"];

function lireProfilSauvegarde() {
  try {
    return JSON.parse(localStorage.getItem(CLE_PROFIL) || "{}");
  } catch {
    return {};
  }
}

function sauvegarderProfil(profil) {
  try {
    localStorage.setItem(CLE_PROFIL, JSON.stringify(profil));
  } catch {
    // stockage indisponible (navigation privée…) : le profil n'est simplement pas retenu
  }
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

  const reponse = document.getElementById("reponse");
  reponse.replaceChildren();
  for (const passage of donnees.passages || []) {
    reponse.append(document.createTextNode(passage.texte));
    const vus = new Set();
    for (const citation of passage.citations || []) {
      if (vus.has(citation.articleSourceId)) continue;
      vus.add(citation.articleSourceId);
      const lien = element("a", citation.num || "article", "citation");
      lien.href = "#";
      lien.title = citation.texteCite || "";
      lien.addEventListener("click", e => { e.preventDefault(); ouvrirArticle(citation.articleSourceId); });
      reponse.append(lien);
    }
  }

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

async function ouvrirArticle(sourceId) {
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
    document.getElementById("article-fil").textContent =
      [a.texte.titre].concat((a.filAriane || []).map(s => s.titre)).join(" › ");
    document.getElementById("article-titre").textContent = "Article " + (a.num || "");
    document.getElementById("article-etat").textContent = a.applicable ? "En vigueur" : "Non applicable aujourd'hui (" + a.etat + ")";
    const html = a.contenuTexte || (a.contenuHtml ? new DOMParser().parseFromString(a.contenuHtml, "text/html").body.textContent : "");
    document.getElementById("article-texte").textContent = (html || "").trim()
      + (a.nota ? "\n\nNota : " + new DOMParser().parseFromString(a.nota, "text/html").body.textContent.trim() : "");
  } catch {
    document.getElementById("article-titre").textContent = "Article indisponible pour le moment.";
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

appliquerProfil(lireProfilSauvegarde());
chargerConventions().then(() => appliquerProfil(lireProfilSauvegarde()));
