package com.xianda.freshdelivery.delivery.routing;

public interface RouteOptimizer {
    String name();

    boolean supports(RouteProblem problem, TravelMatrix matrix);

    RouteSolution solve(RouteProblem problem, TravelMatrix matrix);
}
