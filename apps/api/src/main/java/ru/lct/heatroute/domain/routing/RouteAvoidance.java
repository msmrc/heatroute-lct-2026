package ru.lct.heatroute.domain.routing;

import java.util.List;
import org.locationtech.jts.geom.LineString;
import ru.lct.heatroute.domain.routing.OfficialRouteGeometryRules.Constraint;

/** Препятствия принятых ветвей; согласованный общий узел не теряется при переходе к геометрии. */
final class RouteAvoidance {
    private final List<LineString> routes;
    private final List<Constraint> constraints;
    private final boolean sharedJunction;

    RouteAvoidance(List<LineString> routes, List<Constraint> constraints, boolean sharedJunction) {
        this.routes = List.copyOf(routes);
        this.constraints = List.copyOf(constraints);
        this.sharedJunction = sharedJunction;
    }

    List<LineString> routes() { return routes; }
    List<Constraint> constraints() { return constraints; }
    boolean hasSharedJunction() { return sharedJunction; }
}
