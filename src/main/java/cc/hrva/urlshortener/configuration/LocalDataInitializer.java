package cc.hrva.urlshortener.configuration;

import cc.hrva.urlshortener.model.User;
import cc.hrva.urlshortener.model.codebook.Authorities;
import cc.hrva.urlshortener.repository.AuthoritiesRepository;
import cc.hrva.urlshortener.repository.UserRepository;
import java.time.LocalDateTime;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

@Component
@Profile("local")
public class LocalDataInitializer implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(LocalDataInitializer.class);

    private static final String ADMIN_EMAIL = "admin@localhost";
    private static final String ADMIN_PASSWORD = "admin123";
    private static final String USER_EMAIL = "user@localhost";
    private static final String USER_PASSWORD = "user123";

    private final UserRepository userRepository;
    private final AuthoritiesRepository authoritiesRepository;
    private final PasswordEncoder passwordEncoder;

    public LocalDataInitializer(
            final UserRepository userRepository,
            final AuthoritiesRepository authoritiesRepository,
            final PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.authoritiesRepository = authoritiesRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    public void run(final String... args) {
        createUserIfNotExists(ADMIN_EMAIL, ADMIN_PASSWORD, List.of(1L, 2L), 10L);
        createUserIfNotExists(USER_EMAIL, USER_PASSWORD, List.of(1L), 4L);
    }

    private void createUserIfNotExists(
            final String email,
            final String rawPassword,
            final List<Long> authorityIds,
            final Long apiKeySlots) {
        if (userRepository.findByEmail(email).isPresent()) {
            log.info("Local dev user already exists email={}", email);
            return;
        }

        final var authorities = authorityIds.stream()
                .map(id -> authoritiesRepository.findById(id)
                        .orElseGet(() -> {
                            log.warn("Authority id={} not found, creating inline", id);
                            return Authorities.builder()
                                    .id(id)
                                    .name(id == 2L ? "ROLE_ADMIN" : "ROLE_USER")
                                    .active(true)
                                    .build();
                        }))
                .toList();

        final var user = User.builder()
                .email(email)
                .password(passwordEncoder.encode(rawPassword))
                .apiKeySlots(apiKeySlots)
                .authorities(authorities)
                .createDate(LocalDateTime.now())
                .active(true)
                .emailVerified(true)
                .authProvider("local")
                .build();

        userRepository.save(user);
        log.info("Created local dev user email={} authorities={}", email, authorities.stream().map(Authorities::getName).toList());
    }

}
