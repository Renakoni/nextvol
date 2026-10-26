package hnovel.network

/** Stable identity and live availability of a captured route, without host transport controls. */
class SourceRouteReference internal constructor(internal val route: SourceNetworkRoute) {
    val available: Boolean get() = route.available
}
