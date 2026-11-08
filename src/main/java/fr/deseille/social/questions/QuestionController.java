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
    private final QuotaQuestions quota;
    private final DepensesJournalieres depenses;

    public QuestionController(QuestionService service, QuotaQuestions quota, DepensesJournalieres depenses) {
        this.service = service;
        this.quota = quota;
        this.depenses = depenses;
    }

    /**
     * Répond à la question, dans la limite du budget et du quota journaliers (429 au-delà, 503 si le
     * modèle est indisponible).
     */
    @PostMapping
    public ReponseQuestion repondre(@Valid @RequestBody QuestionSalarie demande) {
        depenses.verifier();
        quota.consommer();
        return service.repondre(demande.question(), demande.profil());
    }

    public record QuestionSalarie(@NotBlank @Size(max = 1000) String question, @NotNull @Valid ProfilSalarie profil) {
    }
}
