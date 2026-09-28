package ru.lct.heatroute.domain.topology;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.mockito.ArgumentCaptor;

class OfficialFeatureRepositoryTest {
    private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    private final OfficialFeatureRepository repository =
            spy(new OfficialFeatureRepository(jdbcTemplate, new ObjectMapper()));

    @Test
    void visitsFeaturesWithKeysetCursorAndBoundedPages() {
        UUID importId = UUID.randomUUID();
        ImportedOfficialFeature first = feature("a");
        ImportedOfficialFeature second = feature("b");
        ImportedOfficialFeature third = feature("c");
        doReturn(List.of(first, second)).when(repository).findPageByImport(importId, null, 2);
        doReturn(List.of(third)).when(repository).findPageByImport(importId, "b", 2);
        List<String> visited = new ArrayList<>();

        repository.forEachByImport(importId, 2, feature -> visited.add(feature.getFeatureId()));

        assertThat(visited).containsExactly("a", "b", "c");
        verify(repository).findPageByImport(importId, null, 2);
        verify(repository).findPageByImport(importId, "b", 2);
    }

    @Test
    void rejectsUnboundedPageSizeBeforeQueryingDatabase() {
        UUID importId = UUID.randomUUID();

        assertThatThrownBy(() -> repository.findPageByImport(importId, null, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("between 1 and");

        verifyNoInteractions(jdbcTemplate);
    }

    @Test
    @SuppressWarnings("unchecked")
    void calculationCoreIncludesLinkedFutureOksForDemandFlowResolution() {
        UUID importId = UUID.randomUUID();
        doReturn(List.of()).when(jdbcTemplate).query(
                anyString(), any(RowMapper.class), eq(importId), eq(2));

        repository.forEachCalculationCoreByImport(importId, 2, ignored -> { });

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).query(
                sql.capture(), any(RowMapper.class), eq(importId), eq(2));
        assertThat(sql.getValue()).contains("'oks_connection_point', 'oks_future'");
    }

    private ImportedOfficialFeature feature(String id) {
        ImportedOfficialFeature feature = mock(ImportedOfficialFeature.class);
        when(feature.getFeatureId()).thenReturn(id);
        return feature;
    }
}
