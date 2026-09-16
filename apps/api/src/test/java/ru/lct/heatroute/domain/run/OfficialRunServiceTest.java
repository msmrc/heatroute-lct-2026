package ru.lct.heatroute.domain.run;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Optional;
import org.junit.jupiter.api.Test;
import ru.lct.heatroute.domain.job.OfficialJobRepository;

class OfficialRunServiceTest {
    private final OfficialRunRepository runRepository = mock(OfficialRunRepository.class);
    private final OfficialJobRepository jobRepository = mock(OfficialJobRepository.class);
    private final OfficialRunService service = new OfficialRunService(runRepository, jobRepository);

    @Test
    void returnsLatestCompletedRunForVisualDemo() {
        OfficialRunView expected = mock(OfficialRunView.class);
        when(runRepository.findLatestCompleted()).thenReturn(Optional.of(expected));

        assertThat(service.findLatestCompleted()).isSameAs(expected);
    }

    @Test
    void returnsNullWhenNoCompletedRunExists() {
        when(runRepository.findLatestCompleted()).thenReturn(Optional.empty());

        assertThat(service.findLatestCompleted()).isNull();
    }
}
