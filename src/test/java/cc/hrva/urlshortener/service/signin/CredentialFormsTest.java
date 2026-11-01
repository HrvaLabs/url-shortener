package cc.hrva.urlshortener.service.signin;

import org.jsoup.Jsoup;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class CredentialFormsTest {

    @Test
    void aPasswordFieldIsACredentialForm() {
        assertThat(CredentialForms.find(Jsoup.parse("<div><input type=\"PASSWORD\" name=\"x\"></div>"))).contains("a password field");
    }

    @ParameterizedTest
    @ValueSource(strings = {"current-password", "cc-number", "one-time-code", "username current-password"})
    void anAutocompleteForCredentialsIsACredentialForm(final String autocomplete) {
        final var page = Jsoup.parse("<input name=\"a\" autocomplete=\"" + autocomplete + "\">");

        assertThat(CredentialForms.find(page)).isPresent();
    }

    @Test
    void theDaviviendaCloneWithAnIdentificationFieldIsACredentialForm() {
        final var page = Jsoup.parse("""
                <form action="/acceso"><label for="doc">Identificación</label><input id="doc" type="text">
                <button type="submit">Ingresar</button></form>""");

        assertThat(CredentialForms.find(page)).contains("a sign-in field (identificacion)");
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "<input name='txtUsuario'>|usuario",
            "<input placeholder='Contraseña'>|contrasena",
            "<input id='senha'>|senha",
            "<input name='cpf'>|cpf",
            "<input aria-label='Mot de passe'>|mot de passe",
            "<input name='identifiant'>|identifiant",
            "<input placeholder='Kennwort'>|kennwort",
            "<input name='benutzerName'>|benutzername",
            "<label>Nome utente <input name='a'></label>|nome utente",
            "<input placeholder='Lozinka'>|lozinka",
            "<input placeholder='Korisničko ime'>|korisnicko ime",
            "<input name='oib'>|oib",
            "<input placeholder='Card number'>|card number",
            "<input name='cvv'>|cvv",
            "<input name='user_pin'>|pin",
            "<input name='loginForm'>|login",
            "<input placeholder='Sign in'>|sign in"
    })
    void aFormWithASignInOrIdFieldIsACredentialForm(final String field, final String word) {
        final var page = Jsoup.parse("<form>" + field.replace('\'', '"') + "</form>");

        assertThat(CredentialForms.find(page)).contains("a sign-in field (" + word + ")");
    }

    @Test
    void outsideAnyFormASignInFieldCountsOnlyNextToAButton() {
        assertThat(CredentialForms.find(Jsoup.parse("<div><input placeholder='Usuario'><button>Ingresar</button></div>"))).isPresent();
        assertThat(CredentialForms.find(Jsoup.parse("<div><input placeholder='Usuario'></div>"))).isEmpty();
    }

    @Test
    void aPlainArticleWithASearchBoxAndANewsletterFormIsNot() {
        final var page = Jsoup.parse("""
                <html><head><title>Shipping news</title></head><body>
                <form role="search"><input name="q" placeholder="Search"><button>Go</button></form>
                <article><h1>Spinner shipping prices</h1><p>Our user guide explains the pinned password policy.</p></article>
                <form><input type="email" name="email" placeholder="Your email"><input name="userAgent" type="hidden">
                <input name="shipping_option"><button>Subscribe</button></form>
                </body></html>""");

        assertThat(CredentialForms.find(page)).isEmpty();
    }

}
