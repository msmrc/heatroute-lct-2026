package ru.lct.heatroute.domain.sizing;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import ru.lct.heatroute.domain.engineering.PipeCatalogEntry;

/**
 * Выбирает минимальные ДУ дерева снизу вверх по ТЗ §2.3 и Q1–Q2.
 * Связанные рёбра с одинаковым расходом имеют один ДУ. Длина проверяется
 * по самому длинному непрерывному пути этого ДУ, без суммирования ветвей.
 */
final class SameFlowDiameterAssignment {
    private SameFlowDiameterAssignment() {
    }

    /** Входные рёбра упорядочены от корней к листьям; граф уже проверен на циклы. */
    static Map<String, Integer> assign(
            List<NetworkTreeEdge> orderedEdges,
            Map<String, NetworkTreeEdge> upstreamByNode,
            Map<String, List<NetworkTreeEdge>> downstreamByNode,
            Map<String, BigDecimal> flows,
            List<PipeCatalogEntry> catalog,
            List<NetworkSizingIssue> issues) {
        List<Component> components = new ArrayList<>();
        Map<String, Component> componentByEdge = new HashMap<>();
        for (NetworkTreeEdge edge : orderedEdges) {
            checkCancellation();
            NetworkTreeEdge parent = upstreamByNode.get(edge.getUpstreamNodeId());
            Component component = parent == null ? null : componentByEdge.get(parent.getId());
            if (parent == null || flows.get(parent.getId()).compareTo(flows.get(edge.getId())) != 0) {
                Component child = new Component(edge, flows.get(edge.getId()));
                if (component != null) {
                    component.children.add(child);
                }
                components.add(child);
                component = child;
            }
            component.edges.add(edge);
            componentByEdge.put(edge.getId(), component);
        }

        Map<String, Integer> diameters = new LinkedHashMap<>();
        Map<String, BigDecimal> suffixLengths = new HashMap<>();
        for (int index = components.size() - 1; index >= 0; index--) {
            checkCancellation();
            select(components.get(index), componentByEdge, downstreamByNode, catalog,
                    diameters, suffixLengths, issues);
        }
        return diameters;
    }

    private static void select(
            Component component,
            Map<String, Component> componentByEdge,
            Map<String, List<NetworkTreeEdge>> downstream,
            List<PipeCatalogEntry> catalog,
            Map<String, Integer> diameters,
            Map<String, BigDecimal> suffixLengths,
            List<NetworkSizingIssue> issues) {
        int childMinimum = 0;
        for (Component child : component.children) {
            Integer childDiameter = diameters.get(child.root.getId());
            if (childDiameter != null) {
                childMinimum = Math.max(childMinimum, childDiameter);
            }
        }
        PipeCatalogEntry selected = null;
        Map<String, BigDecimal> trial = new HashMap<>();
        boolean fitsLength = false;
        for (PipeCatalogEntry pipe : catalog) {
            checkCancellation();
            if (pipe.getDiameter() < childMinimum || pipe.getMaxFlowTph().compareTo(component.flow) < 0) {
                continue;
            }
            selected = pipe;
            measureSuffixes(component, componentByEdge, downstream, pipe.getDiameter(),
                    diameters, suffixLengths, trial);
            if (trial.get(component.root.getId()).compareTo(BigDecimal.valueOf(pipe.getMaxContinuousLengthM())) <= 0) {
                fitsLength = true;
                break;
            }
        }
        if (selected == null) {
            for (NetworkTreeEdge edge : component.edges) {
                issues.add(new NetworkSizingIssue("FLOW_EXCEEDS_CATALOG", edge.getId(),
                        "No official diameter can carry " + component.flow.toPlainString() + " t/h"));
            }
            return;
        }
        if (!fitsLength) {
            issues.add(new NetworkSizingIssue("MAX_CONTINUOUS_LENGTH_EXCEEDED", component.root.getId(),
                    "No official diameter supports a continuous path of "
                            + trial.get(component.root.getId()).toPlainString() + " m"));
        }
        for (NetworkTreeEdge edge : component.edges) {
            diameters.put(edge.getId(), selected.getDiameter());
            suffixLengths.put(edge.getId(), trial.get(edge.getId()));
        }
    }

    /**
     * Для заданного ДУ считает длину до наиболее удалённого потомка того же ДУ.
     * Повышение минимального ДУ потомка не может сократить этот путь при прежнем ДУ родителя.
     */
    private static void measureSuffixes(
            Component component,
            Map<String, Component> componentByEdge,
            Map<String, List<NetworkTreeEdge>> downstream,
            int diameter,
            Map<String, Integer> diameters,
            Map<String, BigDecimal> suffixLengths,
            Map<String, BigDecimal> trial) {
        for (int index = component.edges.size() - 1; index >= 0; index--) {
            checkCancellation();
            NetworkTreeEdge edge = component.edges.get(index);
            BigDecimal longestChild = BigDecimal.ZERO;
            for (NetworkTreeEdge child : downstream.getOrDefault(edge.getDownstreamNodeId(), List.of())) {
                BigDecimal childLength = BigDecimal.ZERO;
                if (componentByEdge.get(child.getId()) == component) {
                    childLength = trial.get(child.getId());
                } else if (Integer.valueOf(diameter).equals(diameters.get(child.getId()))) {
                    childLength = suffixLengths.get(child.getId());
                }
                longestChild = longestChild.max(childLength);
            }
            trial.put(edge.getId(), edge.getLengthM().add(longestChild));
        }
    }

    private static void checkCancellation() {
        if (Thread.currentThread().isInterrupted()) {
            throw new CancellationException("Network diameter assignment cancelled");
        }
    }

    private static final class Component {
        private final NetworkTreeEdge root;
        private final BigDecimal flow;
        private final List<NetworkTreeEdge> edges = new ArrayList<>();
        private final List<Component> children = new ArrayList<>();

        private Component(NetworkTreeEdge root, BigDecimal flow) {
            this.root = root;
            this.flow = flow;
        }
    }
}
