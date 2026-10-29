package fr.deseille.social.questions;

import fr.deseille.social.profil.ProfilSalarie;
import fr.deseille.social.questions.QuestionService.ReponseQuestion;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code POST /api/questions} : réponse à la question d'un salarié selon son profil, fondée sur les
 * articles du Code du travail et de sa convention, avec citations. Comme pour le profil, la
 * question et le profil ne sont ni stockés ni journalisés.
 */
@RestController
@RequestMapping("/api/questions")
public class QuestionController {

    private final QuestionService service;

    public QuestionController(QuestionService service) {
        this.service = service;
    }

    @PostMapping
    public ReponseQuestion repondre(@Valid @RequestBody QuestionSalarie demande) {
        return service.repondre(demande.question(), demande.profil());
    }

    public record QuestionSalarie(@NotBlank @Size(max = 1000) String question, @NotNull @Valid ProfilSalarie profil) {
    }
}
