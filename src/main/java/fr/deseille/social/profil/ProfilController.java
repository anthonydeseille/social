package fr.deseille.social.profil;

import fr.deseille.social.profil.ProfilService.ContexteProfil;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code POST /api/profil/contexte} : contexte juridique d'un profil salarié. En POST pour que le
 * profil, potentiellement sensible, ne figure ni dans l'URL ni dans les journaux d'accès ; il
 * n'est ni stocké ni journalisé.
 */
@RestController
@RequestMapping("/api/profil")
public class ProfilController {

    private final ProfilService service;

    public ProfilController(ProfilService service) {
        this.service = service;
    }

    @PostMapping("/contexte")
    public ContexteProfil contexte(@Valid @RequestBody ProfilSalarie profil) {
        return service.contexte(profil);
    }
}
