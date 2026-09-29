# Контракты данных HeatRoute

Актуальная трактовка основана на официальном ТЗ и письменных разъяснениях организаторов от
29.09.2026. Машиночитаемые схемы находятся в [`docs/contracts`](contracts/README.md).

## Вход

`POST /api/v1/official/imports` принимает multipart-поле `file` с одним GeoJSON
`FeatureCollection` в EPSG:4326. Импорт потоково считает SHA-256, проверяет геометрию, типы
свойств, уникальность ID и типизированные ссылки. Повторный импорт тех же байтов не создаёт копию
набора.

`POST /api/v1/official/imports/demo` импортирует
`datasets/official/lct-2026.geojson` тем же кодом. Это удобный bootstrap, а не подмена результата
предрасчитанными данными.

| `object_type` | Геометрия | Назначение |
|---|---|---|
| `source` | Point | Источник теплоснабжения |
| `heat_network` | LineString | Существующий участок сети |
| `heat_chamber` | Point | Существующая камера |
| `oks_future` | Polygon / MultiPolygon | Перспективный ОКС |
| `oks_connection_point` | Point | Точка подключения ОКС |
| `oks_existing` | Polygon / MultiPolygon | Существующий ОКС / препятствие |
| `restriction` | По типу ограничения | Пространственное ограничение |

Основные инженерные поля: `id`, `object_type`, `diameter`, `flow_tph`, `heat_load`,
`oks_id`, `restriction_type` и `upstream_object_id`. Требования к ним зависят от типа объекта.

Конкурсный файл принимается как `baseline_input`. Отсутствующие данные не заменяются
«правдоподобными» значениями: блокирующие проблемы попадают в `errors`, ограничения исходного
набора, не мешающие обязательному 2D-расчёту, попадают в `warnings`.

## API расчёта

| Метод | Путь | Назначение |
|---|---|---|
| GET | `/api/v1/health/live` | Жив ли процесс |
| GET | `/api/v1/health/ready` | Готовы PostGIS и CP-SAT |
| POST | `/api/v1/official/imports` | Импорт GeoJSON |
| POST | `/api/v1/official/imports/demo` | Импорт конкурсного fixture |
| GET | `/api/v1/official/imports/{importId}` | Состояние импорта |
| GET | `/api/v1/official/imports/{importId}/map` | Данные исходной карты |
| POST | `/api/v1/official/imports/{importId}/runs` | Запуск расчёта HeatRoute |
| GET | `/api/v1/official/jobs/{jobId}` | Прогресс durable job |
| DELETE | `/api/v1/official/jobs/{jobId}` | Кооперативная отмена |
| GET | `/api/v1/official/runs/{runId}` | Run и варианты |
| GET | `/api/v1/official/runs/latest` | Последний run |
| GET | `/api/v1/official/runs/{runId}/export` | Строгий GeoJSON |

Swagger UI доступен по `/api/v1/swagger-ui.html`, OpenAPI JSON по `/api/v1/openapi`.

## Параметры run

```json
{
  "minimum_depth_m": 0.7,
  "maximum_depth_m": 10.0,
  "depth_enabled": false
}
```

Все поля необязательны. По умолчанию расчёт глубины выключен. API всегда запускает один
production-алгоритм HeatRoute, поэтому клиенту не нужно выбирать профиль или реализацию.

Параметры сохраняются вместе с run. Wire-JSON использует `snake_case`; ответ содержит
`input_sha256` и `algorithm_version`, поэтому результат можно связать с конкретным входом и
версией расчётного ядра.

## Выход

Актуальный экспорт содержит только четыре типа features:

1. `heat_network`;
2. `heat_chamber`;
3. `technical_node`;
4. `variant_summary`.

`tie_in`, `heat_network_reconstruction` и `heat_chamber_reconstruction` не входят в текущий
строгий whitelist. Информация о выбранном присоединении представлена топологией узлов и участков,
а актуальная экономика находится в summary.

`OfficialOutputContractValidator` проверяет:

- разрешённые и обязательные поля каждого типа;
- геометрию и диапазоны WGS84;
- глобальную уникальность ID;
- ссылки участков на узлы;
- один `variant_summary` на вариант;
- согласованность стоимости и итоговых показателей.

Экспорт можно ограничить одним вариантом через `variant_id`. Без параметра сервер выгружает все
сохранённые ranked-варианты одного run.

## Геометрия и размеры

- API и экспорт: EPSG:4326;
- метрические вычисления: EPSG:32637;
- positions могут содержать две координаты, а при включённом depth ещё и Z;
- предельный вход: 3 ГБ;
- предельный выход: 500 МБ;
- импорт и запись результата выполняются потоково.

JSON Schema проверяет форму каждой feature. Топологические и арифметические инварианты между
features остаются обязанностью Java-валидатора.

## Опубликованные схемы

- `GET /api/v1/official/contracts/input.schema.json`;
- `GET /api/v1/official/contracts/provided-dataset.schema.json`;
- `GET /api/v1/official/contracts/output.schema.json`.

Схемы компилируются и проверяются в тестах. Их изменение считается изменением API-контракта, а не
редакцией справочного текста.
