import pytest
from redis import Redis
from sqlalchemy import create_engine, text

from heatroute.config import get_settings


@pytest.mark.integration
def test_real_postgis_and_redis_are_available() -> None:
    settings = get_settings()
    engine = create_engine(settings.database_url, pool_pre_ping=True)
    try:
        with engine.connect() as connection:
            assert connection.execute(text("SELECT 1")).scalar_one() == 1
            assert connection.execute(text("SELECT PostGIS_Version()")).scalar_one()
            assert connection.execute(
                text("SELECT ST_Area(ST_GeomFromText('POLYGON((0 0, 2 0, 2 2, 0 2, 0 0))'))")
            ).scalar_one() == 4.0
    finally:
        engine.dispose()

    redis_client = Redis.from_url(settings.redis_url)
    try:
        assert redis_client.ping() is True
    finally:
        redis_client.close()

