package ru.lct.heatroute.domain.routing;

import java.math.BigDecimal;
import java.math.RoundingMode;
import org.locationtech.jts.geom.Coordinate;

public class RouteCoordinate {
    private final BigDecimal xM;
    private final BigDecimal yM;

    public RouteCoordinate(double xM, double yM) {
        this.xM = rounded(xM);
        this.yM = rounded(yM);
    }

    public BigDecimal getXM() { return xM; }
    public BigDecimal getYM() { return yM; }

    public Coordinate toCoordinate() {
        return new Coordinate(xM.doubleValue(), yM.doubleValue());
    }

    private static BigDecimal rounded(double value) {
        return BigDecimal.valueOf(value).setScale(3, RoundingMode.HALF_UP);
    }
}
