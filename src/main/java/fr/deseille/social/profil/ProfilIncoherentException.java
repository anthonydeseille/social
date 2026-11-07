package fr.deseille.social.profil;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** Profil dont les informations se contredisent (400) : aucune réponse fiable n'est possible. */
public class ProfilIncoherentException extends ResponseStatusException {

    public ProfilIncoherentException(String raison) {
        super(HttpStatus.BAD_REQUEST, raison);
    }
}
