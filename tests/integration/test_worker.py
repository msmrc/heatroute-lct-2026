import pytest

from heatroute.cli import worker_smoke


@pytest.mark.integration
def test_worker_reaches_postgis_and_redis() -> None:
    result = worker_smoke(timeout=30)
    assert result["status"] == "ok"
    assert result["database"] == "heatroute"
    assert result["postgis"]
    assert result["redis"] is True

