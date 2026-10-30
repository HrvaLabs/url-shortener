package cc.hrva.urlshortener.service.impl;

import cc.hrva.urlshortener.repository.UserRepository;
import cc.hrva.urlshortener.model.User;
import cc.hrva.urlshortener.security.SecureTokens;
import cc.hrva.urlshortener.security.StoredPasswords;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class DefaultUserDetailsService implements UserDetailsService {

    private final UserRepository userRepository;

    @Override
    @Transactional
    public UserDetails loadUserByUsername(final String email) throws UsernameNotFoundException {
        return userRepository
                .findByEmail(email)
                .map(this::createSpringSecurityUser)
                .orElseThrow(() -> new UsernameNotFoundException(String.format("User %s was not found in the database", email)));
    }

    private org.springframework.security.core.userdetails.User createSpringSecurityUser(final User user) {
        final var grantedAuthorities = user
                .getAuthorities()
                .stream()
                .map(authority -> new SimpleGrantedAuthority(authority.getName()))
                .toList();
        final var enabled = !Boolean.FALSE.equals(user.getActive());
        return new org.springframework.security.core.userdetails.User(
                user.getEmail(),
                StoredPasswords.isSet(user.getPassword()) ? user.getPassword() : NoPassword.HASH,
                enabled,
                true,
                true,
                true,
                grantedAuthorities);
    }

    private static final class NoPassword {

        private static final String HASH = new BCryptPasswordEncoder().encode(SecureTokens.random());

    }

}
