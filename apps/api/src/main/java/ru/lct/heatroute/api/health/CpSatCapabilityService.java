package ru.lct.heatroute.api.health;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import ru.lct.heatroute.domain.optimization.CpSatRuntime;

/** Один раз проверяет, что packaged runtime способен загрузить native CP-SAT и решить модель. */
@Service
public class CpSatCapabilityService {
    private static final Logger LOGGER = LoggerFactory.getLogger(CpSatCapabilityService.class);
    private final CpSatRuntime runtime;
    private volatile DependencyStatus cached;

    public CpSatCapabilityService(CpSatRuntime runtime) {
        this.runtime = runtime;
    }

    public DependencyStatus check() {
        DependencyStatus result = cached;
        if (result != null) return result;
        synchronized (this) {
            if (cached != null) return cached;
            try {
                CpSatRuntime.Capability capability = runtime.verifyCapability();
                cached = DependencyStatus.ok("ortools-" + capability.getVersion());
            } catch (RuntimeException | LinkageError exception) {
                LOGGER.error("CP-SAT native capability check failed", exception);
                cached = DependencyStatus.error("unavailable");
            }
            return cached;
        }
    }
}
