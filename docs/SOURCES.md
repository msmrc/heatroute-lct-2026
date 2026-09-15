# Источники и границы достоверности

Проверено по открытой официальной документации 07.09.2026. Ссылки обосновывают возможности/ограничения библиотек, но не означают, что приложение уже реализовано, стек уже собран или инженерная методика согласована.

Проектные решения, budgets, структуры API и synthetic examples — предложенные требования этого ТЗ. Они не являются требованиями организаторов, нормативными отступами, рыночными ценами или результатами benchmark.

## Исходные материалы пользователя

Карточка задачи №2 международного хакатона: предложить варианты трассировки подключения здания на основе геоданных; ресурсы — геоданные и низкополигональные модели. Также предоставлены скриншоты калькулятора пентамино, формулы нормированной энтропии расстояний, таблиц и примера «оптимизации трубопровода».

Скриншоты использованы как материал для анализа идеи, а не как подтверждение готовности маршрутизатора. Исходники, полный набор данных, нормативный каталог, цены, параметры сети и официальный подробный регламент в этот комплект не предоставлены. Контактные телефоны/персональные сведения с презентации не копируются в техническое задание.

## Первичные технические источники

| ID | Документация | Применение в ТЗ |
|---|---|---|
| S01 | [shadcn/ui: Introduction](https://ui.shadcn.com/docs) | готовые компоненты с локально изменяемым кодом |
| S02 | [shadcn/ui: Vite](https://ui.shadcn.com/docs/installation/vite), [Tailwind v4](https://ui.shadcn.com/docs/tailwind-v4) | выбранный frontend scaffold и совместимость основы |
| S03 | [FastAPI: Background Tasks](https://fastapi.tiangolo.com/tutorial/background-tasks/) | выделение тяжёлых вычислений в worker-процессы |
| S04 | [Celery: Tasks](https://docs.celeryq.dev/en/stable/userguide/tasks.html), [Redis broker](https://docs.celeryq.dev/en/stable/getting-started/backends-and-brokers/redis.html) | task delivery, retry/acknowledgement и visibility timeout |
| S05 | [MapLibre GL JS](https://maplibre.org/maplibre-gl-js/docs/) | клиентская визуализация карты |
| S06 | [PostGIS: ST_Buffer](https://postgis.net/docs/ST_Buffer.html) | 2D-буферы, единицы CRS, ограничения Z |
| S07 | [Shapely: STRtree](https://shapely.readthedocs.io/en/stable/strtree.html) | spatial index для отбора геометрий |
| S08 | [pyproj: Transformer](https://pyproj4.github.io/pyproj/stable/api/transformer.html) | порядок осей, выбор операции преобразования, точность |
| S09 | [RFC 7946: GeoJSON](https://www.rfc-editor.org/info/rfc7946/) | WGS84 и порядок координат API |
| S10 | [NetworkX: astar_path](https://networkx.org/documentation/stable/reference/algorithms/generated/networkx.algorithms.shortest_paths.astar.astar_path.html) | веса и требования к A* heuristic |
| S11 | [Pyogrio](https://pyogrio.readthedocs.io/en/latest/) | файловые векторные adapters через GDAL/OGR |
| S12 | [GDAL: GeoPackage vector](https://gdal.org/en/stable/drivers/vector/gpkg.html) | формат, слои, metadata и ограничения driver |
| S13 | [pandapipes: Pipeflow procedure](https://pandapipes.readthedocs.io/en/latest/pipeflow/pipeflow_procedure.html) | отдельные гидравлические/тепловые расчёты |
| S14 | [pandapipes: Pipe component](https://pandapipes.readthedocs.io/en/latest/components/pipe/pipe_component.html) | параметры физической модели труб |
| S15 | [OWASP: File Upload Cheat Sheet](https://cheatsheetseries.owasp.org/cheatsheets/File_Upload_Cheat_Sheet.html) | безопасный приём/хранение файлов |
| S16 | [PostGIS: ST_AsMVT](https://postgis.net/docs/ST_AsMVT.html) | серверные векторные тайлы для крупных слоёв |
| S17 | [MapLibre: 3D buildings](https://maplibre.org/maplibre-gl-js/docs/examples/display-buildings-in-3d/) | необязательная экструзия зданий |
| S18 | [Pydantic: discriminated unions](https://docs.pydantic.dev/latest/concepts/unions/) | типизированные DTO по виду объектов |
| S19 | [OpenAI: AGENTS.md](https://developers.openai.com/codex/agent-configuration/agents-md) | короткие постоянные инструкции Codex в репозитории |
| S20 | [OpenAI: Codex best practices](https://developers.openai.com/codex/learn/best-practices) | контекст, план этапов и проверяемые критерии завершения |
| S21 | [GeoPandas: read_parquet](https://geopandas.org/en/stable/docs/reference/api/geopandas.read_parquet.html) | стандартное чтение GeoParquet metadata/geometry |
| S22 | [GDAL: Parquet / GeoParquet](https://gdal.org/en/stable/drivers/vector/parquet.html) | формат GeoParquet, geometry encoding и версии спецификации |
| S23 | [Pyogrio: supported formats](https://pyogrio.readthedocs.io/en/latest/supported_formats.html) | зависимость доступных vector drivers от сборки GDAL |
| S24 | [PostGIS: ST_AsMVTGeom](https://postgis.net/docs/ST_AsMVTGeom.html) | перевод, clipping и buffer геометрий в tile extent |
| S25 | [pandapipes: pipeflow options](https://pandapipes.readthedocs.io/en/develop/pipeflow/options.html) | режимы hydraulic/heat и явная конфигурация solver |

При реализации повторно проверить конкретные стабильные версии, лицензии зависимостей и совместимость. Документация с путём `latest` — справочный источник; это не разрешение использовать плавающие версии в поставляемом runtime.

## Что должно появиться после выдачи ресурсов

Отдельный каталог предоставленных источников правил/данных с названием, версией, датой, областью применимости и правами использования. Точный регламент хакатона и согласованные критерии приёмки. Подтверждения систем координат, единиц, topology и интерпретации мощности. Согласованные данные/методика физической и стоимостной оценки.

Без этих оснований продукт сохраняет статусы demo/draft/preliminary/insufficient_data, а не имитирует нормативную или инженерную сертификацию.
