package fr.deseille.social.recherche;

import fr.deseille.social.recherche.RechercheService.Resultat;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /api/recherche?q=&idcc=&applicables=&limite=} : recherche plein texte dans le Code du
 * travail et, si {@code idcc} est fourni, dans la convention collective. {@code q} accepte la
 * syntaxe web (« expression exacte », or, -exclusion) et ignore les accents.
 */
@RestController
@RequestMapping("/api/recherche")
public class RechercheController {

    private final RechercheService service;

    public RechercheController(RechercheService service) {
        this.service = service;
    }

    @GetMapping
    public List<Resultat> rechercher(@RequestParam @NotBlank @Size(max = 200) String q,
                                     @RequestParam(required = false) @Min(1) @Max(9999) Integer idcc,
                                     @RequestParam(defaultValue = "true") boolean applicables,
                                     @RequestParam(defaultValue = "20") @Min(1) @Max(50) int limite) {
        return service.rechercher(q, idcc, applicables, limite);
    }
}
