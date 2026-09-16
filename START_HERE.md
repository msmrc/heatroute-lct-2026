# START HERE

Продолжай разработку из `E:\job\_lct2026\heatroute_codex` на ветке `master`.

Перед изменениями прочитай в этом порядке:

1. `docs/implementation/TOMORROW_HANDOFF.md`;
2. `docs/implementation/OFFICIAL_ALIGNMENT_AUDIT.md`;
3. `docs/implementation/OFFICIAL_TZ_ROADMAP.md`;
4. `docs/TECH_SPEC.md` и `docs/DATA_CONTRACTS.md`;
5. `docs/ACCEPTANCE.md`;
6. `AGENTS.md`.

Backend только Java 11 / Spring Boot 2.6.3 в `apps/api`. Не возвращай Python, FastAPI, Celery,
Alembic или Redis. Не считай старые M0–M7 evidence текущей готовностью. Первый R4 vertical slice
расширен до obstacle-aware R4/R6 routing с тремя стратегиями. R5 закрыт на строгих fixtures:
bottom-up sizing автоматически повышает ДУ по расходу и предельной длине, а добавленный расход
распространяется по существующей сети с partial reconstruction участков и камер. В поставленном
GeoJSON реконструкция честно недоступна из-за отсутствующих `flow_tph/upstream_object_id`.
Для плотных ограничений candidate lookup использует JTS STRtree, а для малого официального набора
сохраняет более быстрый linear prepared-geometry path. Full 2× supplied-geometry gate с 34 ОКС
проходит на Ubuntu 22 / Java 11; внешний остаток R9 — согласование максимального профиля.
Повтор того же файла в той же версии входного контракта возвращает существующий import по SHA-256;
не удаляй unique invariant и не загружай идентичные features повторно.
Расчётные jobs выполняются bounded executor-ом (default 2) с минутным heartbeat lease; R9 probes и
правила интерпретации evidence находятся в `docs/operations/R9_ACCEPTANCE.md`.
Clean Ubuntu 22 + checksum-pinned docker-compose 1.29.2 проверяется CI; локальный/VPS deployment
не выполнять без отдельной команды пользователя.
3 GiB parser evidence и границы его применимости находятся в
`docs/implementation/R9_INPUT_SCALE_EVIDENCE.md`; не выдавай byte-padding probe за topology scale.
Там же зафиксирован прошедший на Java 11 500 MiB production writer/validator probe; он не
заменяет full calculation scale или отдельное 50-user measurement.
Измеренная 50-user API-приёмка и её точные границы зафиксированы в
`docs/implementation/R9_CONCURRENCY_EVIDENCE.md`; не выдавай её за 50 одновременно исполняемых
тяжёлых расчётов.
R7 component costing и score уже интегрированы; поставленный файл показывает только известную
стоимость и не получает score без данных реконструкции. Строгий seven-type adapter, независимый
whitelist/type/reference validator и download endpoint реализованы для contract-complete вариантов;
для поставленного неполного файла endpoint честно отвечает `409 OFFICIAL_EXPORT_INCOMPLETE`.
Строгий вход, supplied-dataset compatibility profile и выход опубликованы как JSON Schema Draft
2020-12 в `docs/contracts`, упаковываются в Java runtime и доступны через
`/api/v1/official/contracts/*.schema.json`; CI проверяет реальный файл и результат экспортера.
Экспорт проходит preflight-проверку и затем инкрементально пишется Jackson `JsonGenerator` без
сборки полного output tree. Published 2D R6 rules закрыты exact-value и
positive/boundary/negative matrix-тестами; касание границы допустимого отступа исправлено как
валидное. Полностью рассчитанный вариант карта получает из того же строгого export adapter через
`variant_id`; внутренний preview используется только когда официальный output честно недоступен.
R7 закрыт по нормативным ставкам/формулам; условные числа примера 10.8 расходятся с собственной
таблицей на 19/33 рубля и это закреплено golden-test и audit. R8 depth-перетрассировка закрыта по
опубликованным правилам: immutable диапазон глубины, отдельный XY detour, независимый validator,
piecewise cost, technical nodes, XYZ и продольный профиль. Точное full-calculation scale evidence
находится в `docs/implementation/R9_TOPOLOGY_SCALE_EVIDENCE.md`.
Не публикуй текущие изменения на VPS без отдельной команды пользователя.

Быстрая проверка состояния:

```powershell
git -C E:\job\_lct2026\heatroute_codex status --short
pwsh -File E:\job\_lct2026\heatroute_codex\scripts\dev.ps1 test
```
