package io.github.ghostwritertje.seedkeeper;

import io.quarkus.arc.Arc;
import io.quarkus.qute.TemplateGlobal;
import io.quarkus.vertx.http.runtime.CurrentVertxRequest;
import io.vertx.ext.web.RoutingContext;
import org.jspecify.annotations.Nullable;

/**
 * PROTOTYPE, throwaway: lives on the prototype branch only, never on main.
 *
 * <p>Gives every template {@code variant} (the {@code ?variant=} of the request, "A" when absent) so a page can
 * render one of several prototype variants, and {@code nav} ({@code ?nav=}, else the {@code proto-nav} cookie the
 * switcher sets, else "A") so the header variant sticks across pages.
 */
@TemplateGlobal
public class PrototypeVariant {

    private PrototypeVariant() {}

    static String variant() {
        String value = param("variant");
        return value == null ? "A" : value;
    }

    static String nav() {
        String value = param("nav");
        if (value != null) {
            return value;
        }
        RoutingContext context = context();
        if (context != null && context.request().getCookie("proto-nav") != null) {
            return context.request().getCookie("proto-nav").getValue();
        }
        return "A";
    }

    private static @Nullable String param(String name) {
        RoutingContext context = context();
        if (context == null) {
            return null;
        }
        String value = context.request().getParam(name);
        return value == null || value.isBlank() ? null : value.strip().toUpperCase(java.util.Locale.ROOT);
    }

    private static @Nullable RoutingContext context() {
        var instance = Arc.container().instance(CurrentVertxRequest.class);
        return instance.isAvailable() ? instance.get().getCurrent() : null;
    }
}
