package cc.hrva.urlshortener.controller;

import org.thymeleaf.TemplateEngine;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;

public final class LinkWarningTemplates {

    private LinkWarningTemplates() {}

    public static TemplateEngine engine() {
        final var resolver = new ClassLoaderTemplateResolver();
        resolver.setPrefix("templates/");
        resolver.setSuffix(".html");
        resolver.setTemplateMode(TemplateMode.HTML);
        resolver.setCharacterEncoding("UTF-8");
        final var engine = new SpringTemplateEngine();
        engine.setTemplateResolver(resolver);
        return engine;
    }

}
