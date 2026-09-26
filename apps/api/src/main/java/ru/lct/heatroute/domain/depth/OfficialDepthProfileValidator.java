package ru.lct.heatroute.domain.depth;

import org.springframework.stereotype.Component;
import ru.lct.heatroute.domain.engineering.OfficialPipeCatalog;

/** Независимый допуск профиля по фактическим пересечениям и полным участкам постоянной глубины. */
@Component
public class OfficialDepthProfileValidator extends ContinuousDepthProfileValidator {
    private final DepthNetworkAssessment network;

    public OfficialDepthProfileValidator(OfficialPipeCatalog pipes) {
        super(pipes);
        network = new DepthNetworkAssessment(pipes);
    }

    DepthNetworkAssessment networkAssessment() {
        return network;
    }
}
