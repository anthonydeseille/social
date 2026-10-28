package fr.deseille.social.corpus;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TexteRepository extends JpaRepository<Texte, Long> {

    Optional<Texte> findBySourceId(String sourceId);
}
