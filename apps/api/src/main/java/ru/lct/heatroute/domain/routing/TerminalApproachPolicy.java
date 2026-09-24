package ru.lct.heatroute.domain.routing;

/** Альтернативы геометрии ввода, а не разные нормы допуска: обе проходят один полный finish. */
enum TerminalApproachPolicy {
    TOWARD_UPSTREAM,
    PRESERVE_VALID
}
