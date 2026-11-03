package cc.hrva.urlshortener.util;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.apache.commons.lang3.StringUtils;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;

public final class EmailPlainText {

    private static final Set<String> SKIPPED = Set.of("head", "style", "script", "noscript", "title", "template", "meta", "link", "xml");
    private static final Set<String> PARAGRAPHS = Set.of("p", "h1", "h2", "h3", "h4", "h5", "h6", "table", "ul", "ol", "blockquote", "pre", "hr");
    private static final Set<String> LINES = Set.of("div", "tr", "td", "th", "section", "article", "header", "footer", "dl", "dt", "dd");
    private static final Pattern HIDDEN = Pattern.compile("display\\s*:\\s*none", Pattern.CASE_INSENSITIVE);
    private static final Pattern BLANKS = Pattern.compile("[\\s\\u00a0\\u200b\\u200c\\u200d\\u034f\\u2060\\ufeff]+");
    private static final Pattern SCHEME_AND_WWW = Pattern.compile("^(?:https?://)?(?:www\\.)?", Pattern.CASE_INSENSITIVE);
    private static final String LIST_MARKER = "- ";
    private static final String CELL_SEPARATOR = "\t";

    private EmailPlainText() {
    }

    public static String of(final String html) {
        if (StringUtils.isBlank(html)) {
            return "";
        }
        final var converter = new Converter();
        converter.walkChildren(Jsoup.parse(html).body());
        return converter.result();
    }

    private static String collapsed(final String text) {
        return BLANKS.matcher(text).replaceAll(" ").trim();
    }

    private static boolean hidden(final Element element) {
        return element.hasAttr("hidden") || HIDDEN.matcher(element.attr("style")).find();
    }

    private static String addressOf(final String href) {
        final var lower = href.toLowerCase(Locale.ROOT);
        if (lower.startsWith("http://") || lower.startsWith("https://")) {
            return href;
        }
        if (lower.startsWith("mailto:")) {
            return StringUtils.substringBefore(href.substring("mailto:".length()), "?");
        }
        return null;
    }

    private static boolean sameAddress(final String text, final String address) {
        return withoutSchemeAndSlash(text).equalsIgnoreCase(withoutSchemeAndSlash(address));
    }

    private static String withoutSchemeAndSlash(final String address) {
        return StringUtils.removeEnd(SCHEME_AND_WWW.matcher(address).replaceFirst(""), "/");
    }

    private static boolean standsAlone(final Element link, final String label) {
        var block = link.parent();
        while (block != null && !isBlock(block)) {
            block = block.parent();
        }
        return block != null && collapsed(block.text()).equals(label);
    }

    private static boolean isBlock(final Element element) {
        return PARAGRAPHS.contains(element.normalName()) || LINES.contains(element.normalName()) || "li".equals(element.normalName())
                || "body".equals(element.normalName());
    }

    private static boolean isDataRow(final Element row) {
        return row.children().stream().filter(cell -> "td".equals(cell.normalName()) || "th".equals(cell.normalName())).count() > 1
                && row.select("table, p, h1, h2, h3, h4, h5, h6, ul, ol, li").isEmpty();
    }

    private static final class Converter {

        private final StringBuilder out = new StringBuilder();
        private final StringBuilder inline = new StringBuilder();
        private int gap;
        private String prefix = "";

        String result() {
            flush();
            return out.toString();
        }

        void walkChildren(final Element parent) {
            for (final var child : parent.childNodes()) {
                walk(child);
            }
        }

        private void walk(final Node node) {
            if (node instanceof TextNode text) {
                inline.append(text.getWholeText());
                return;
            }
            if (!(node instanceof Element element) || SKIPPED.contains(element.normalName()) || hidden(element)) {
                return;
            }
            final var name = element.normalName();
            if ("br".equals(name)) {
                flush();
                gap(1);
            } else if ("a".equals(name)) {
                appendLink(element);
            } else if ("li".equals(name)) {
                flush();
                gap(1);
                prefix = LIST_MARKER;
                walkChildren(element);
                flush();
                prefix = "";
                gap(1);
            } else if ("tr".equals(name) && isDataRow(element)) {
                flush();
                gap(1);
                emit(cells(element));
                gap(1);
            } else if (PARAGRAPHS.contains(name)) {
                block(element, 2);
            } else if (LINES.contains(name)) {
                block(element, 1);
            } else {
                walkChildren(element);
            }
        }

        private void block(final Element element, final int separation) {
            flush();
            gap(separation);
            walkChildren(element);
            flush();
            gap(separation);
        }

        private String cells(final Element row) {
            return row.children().stream()
                    .map(cell -> {
                        final var converter = new Converter();
                        converter.walkChildren(cell);
                        return collapsed(converter.result());
                    })
                    .filter(StringUtils::isNotEmpty)
                    .collect(Collectors.joining(CELL_SEPARATOR));
        }

        private void appendLink(final Element link) {
            final var label = collapsed(link.text());
            final var address = addressOf(link.attr("href").trim());
            if (address == null) {
                inline.append(label);
            } else if (label.isEmpty()) {
                inline.append(address);
            } else if (sameAddress(label, address)) {
                inline.append(address.startsWith("http") ? address : label);
            } else if (standsAlone(link, label)) {
                inline.append(label).append(": ").append(address);
            } else {
                inline.append(label).append(" (").append(address).append(')');
            }
        }

        private void flush() {
            final var text = collapsed(inline.toString());
            inline.setLength(0);
            if (!text.isEmpty()) {
                emit(prefix + text);
                prefix = "";
            }
        }

        private void emit(final String text) {
            if (text.isEmpty()) {
                return;
            }
            if (!out.isEmpty()) {
                out.append("\n".repeat(Math.max(gap, 1)));
            }
            out.append(text);
            gap = 0;
        }

        private void gap(final int separation) {
            gap = Math.max(gap, separation);
        }

    }

}
