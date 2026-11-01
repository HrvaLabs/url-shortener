package cc.hrva.urlshortener.service.signin;

import java.text.Normalizer;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.apache.commons.lang3.StringUtils;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

public final class CredentialForms {

    public static final List<String> SIGN_IN_WORDS = List.of(
            "login", "log in", "logon", "sign in", "signin", "username", "user name", "user id", "userid", "password", "passwd", "passcode",
            "pin", "identification", "card number", "cardnumber", "cvv", "cvc",
            "identificación", "usuario", "clave", "contraseña", "cédula", "número de tarjeta",
            "senha", "usuário", "cpf", "número do cartão",
            "mot de passe", "identifiant", "nom d'utilisateur", "numéro de carte",
            "Kennwort", "Passwort", "Benutzername", "Benutzerkennung", "Kartennummer",
            "nome utente", "codice fiscale", "numero carta",
            "lozinka", "zaporka", "korisničko ime", "OIB", "broj kartice",
            "user");

    private static final Set<String> CREDENTIAL_AUTOCOMPLETE = Set.of("current-password", "cc-number", "one-time-code");
    private static final Set<String> NOT_TYPED_IN = Set.of("hidden", "submit", "button", "checkbox", "radio", "image", "reset", "file");
    private static final String TYPED_INPUTS = "input";
    private static final String BUTTONS = "button, input[type=submit], input[type=button], [role=button]";
    private static final int WHOLE_TOKEN_MATCH_LENGTH = 6;
    private static final Pattern CAMEL_CASE = Pattern.compile("(?<=\\p{Ll})(?=\\p{Lu})");
    private static final Pattern MARKS = Pattern.compile("\\p{M}+");
    private static final Pattern NOT_A_LETTER_OR_DIGIT = Pattern.compile("[^a-z0-9]+");
    private static final List<String> WORDS = List.copyOf(new LinkedHashSet<>(SIGN_IN_WORDS.stream().map(CredentialForms::normalized).toList()));

    private CredentialForms() {}

    public static Optional<String> find(final Document page) {
        if (!page.select("input[type=password]").isEmpty()) {
            return Optional.of("a password field");
        }
        final var autocomplete = page.select("input[autocomplete]").stream()
                .flatMap(input -> Arrays.stream(StringUtils.split(input.attr("autocomplete").toLowerCase(Locale.ROOT))))
                .filter(CREDENTIAL_AUTOCOMPLETE::contains)
                .findFirst();
        if (autocomplete.isPresent()) {
            return Optional.of("a credential autocomplete (" + autocomplete.get() + ")");
        }
        final var labels = labelTextsById(page);
        return Stream.concat(page.select("form").stream().map(CredentialForms::typedInputs), Stream.of(formlessInputsNextToAButton(page)))
                .flatMap(List::stream)
                .map(input -> signInWordOf(labels, input))
                .flatMap(Optional::stream)
                .findFirst()
                .map(word -> "a sign-in field (" + word + ")");
    }

    private static List<Element> typedInputs(final Element container) {
        return container.select(TYPED_INPUTS).stream()
                .filter(input -> !NOT_TYPED_IN.contains(input.attr("type").toLowerCase(Locale.ROOT)))
                .toList();
    }

    private static List<Element> formlessInputsNextToAButton(final Document page) {
        if (page.select(BUTTONS).stream().allMatch(button -> button.closest("form") != null)) {
            return List.of();
        }
        return typedInputs(page).stream().filter(input -> input.closest("form") == null).toList();
    }

    private static Map<String, String> labelTextsById(final Document page) {
        final var labels = new HashMap<String, String>();
        page.select("label[for]").forEach(label -> labels.putIfAbsent(label.attr("for"), label.text()));
        return labels;
    }

    private static Optional<String> signInWordOf(final Map<String, String> labels, final Element input) {
        final var descriptions = Stream.of(input.attr("name"), input.attr("id"), input.attr("placeholder"), input.attr("aria-label"),
                        labelFor(labels, input), enclosingLabel(input))
                .filter(StringUtils::isNotBlank)
                .map(CredentialForms::normalized)
                .toList();
        return WORDS.stream().filter(word -> descriptions.stream().anyMatch(description -> mentions(description, word))).findFirst();
    }

    private static String labelFor(final Map<String, String> labels, final Element input) {
        final var id = input.id();
        return id.isEmpty() ? "" : labels.getOrDefault(id, "");
    }

    private static String enclosingLabel(final Element input) {
        final var label = input.closest("label");
        return label == null ? "" : label.text();
    }

    private static boolean mentions(final String description, final String word) {
        if ((" " + description + " ").contains(" " + word + " ")) {
            return true;
        }
        return word.length() >= WHOLE_TOKEN_MATCH_LENGTH && description.replace(" ", "").contains(word.replace(" ", ""));
    }

    static String normalized(final String text) {
        final var words = CAMEL_CASE.matcher(text).replaceAll(" ");
        final var folded = MARKS.matcher(Normalizer.normalize(words, Normalizer.Form.NFD)).replaceAll("").toLowerCase(Locale.ROOT);
        return NOT_A_LETTER_OR_DIGIT.matcher(folded).replaceAll(" ").strip();
    }

}
