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
сохраняет более быстрый linear prepared-geometry path; следующий обязательный gate — R2/R9 scale.
Повтор того же файла в той же версии входного контракта возвращает существующий import по SHA-256;
не удаляй unique invariant и не загружай идентичные features повторно.
Расчётные jobs выполняются bounded executor-ом (default 2) с минутным heartbeat lease; R9 probes и
правила интерпретации evidence находятся в `docs/operations/R9_ACCEPTANCE.md`.
R7 component costing и score уже интегрированы; поставленный файл показывает только известную
стоимость и не получает score без данных реконструкции. Строгий seven-type adapter, независимый
whitelist/type/reference validator и download endpoint реализованы для contract-complete вариантов;
для поставленного неполного файла endpoint честно отвечает `409 OFFICIAL_EXPORT_INCOMPLETE`.
Экспорт проходит preflight-проверку и затем инкрементально пишется Jackson `JsonGenerator` без
сборки полного output tree. Published 2D R6 rules закрыты exact-value и
positive/boundary/negative matrix-тестами; касание границы допустимого отступа исправлено как
валидное. Полностью рассчитанный вариант карта получает из того же строгого export adapter через
`variant_id`; внутренний preview используется только когда официальный output честно недоступен.
R7 закрыт по нормативным ставкам/формулам; условные числа примера 10.8 расходятся с собственной
таблицей на 19/33 рубля и это закреплено golden-test и audit. Следующий критический этап —
route-performance evidence и R2/R9 scale acceptance.
Не публикуй текущие изменения на VPS без отдельной команды пользователя.

Быстрая проверка состояния:

```powershell
git -C E:\job\_lct2026\heatroute_codex status --short
pwsh -File E:\job\_lct2026\heatroute_codex\scripts\dev.ps1 test
```
