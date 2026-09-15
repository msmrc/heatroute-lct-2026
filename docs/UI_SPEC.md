# Веб-интерфейс HeatRoute

## 1. Задача интерфейса

Сделать готовый рабочий GIS-инструмент, который не требует отдельного редизайна для демонстрации. База компонентов остаётся стандартной и легко заменяется/настраивается. Приоритет — понятный сценарий, читаемая карта и честный статус проверки, а не художественный dashboard.

UI не выполняет окончательную маршрутизацию, оценку стоимости, валидацию доступа или проверку инженерных норм. Он редактирует сценарий и показывает ответы бэкенда. Локальные helpers для подсветки/предпросмотра допустимы, но не подменяют server result.

## 2. Стек и дизайн-система

React + TypeScript strict + Vite + React Router. shadcn/ui из официального registry [S01–S02], Tailwind 4, Lucide, Sonner. Не смешивать shadcn, Ant Design, Material UI и собственную систему одновременно.

Компоненты shadcn сохранять локально. `packages/ui` содержит исходные компоненты, общие variants, theme tokens и небольшие domain-neutral wrappers. Domain components (`RouteCard`, `FindingBadge`, `DatasetVersionPicker`) живут в features/entities и не содержат сетевую/расчётную логику.

Токены: background, foreground, surface, muted, border, primary, destructive, warning, success, info, focus-ring; radius, spacing, panel widths, map-layer palette. Цвета трасс отдельные от семантических цветов статуса. Смена бренда не требует массового изменения JSX.

Визуально: современный спокойный B2B-интерфейс, нейтральный фон, ясные тонкие границы, один основной акцент, компактные профессиональные таблицы, умеренные скругления, без декоративных градиентов/стекла/неоновой карты. Основная светлая тема и полностью работающая тёмная. UI должен быть пригоден в обеих, а не просто инвертировать фон.

Системный шрифт или локально поставляемый open-source шрифт с корректной лицензией; никаких обязательных внешних fonts/CDN. Базовый текст 14px, заголовки 18–24px, пояснения не менее 12px. Моноширинные значения — только IDs/координаты/числа, где полезно.

UI — русский. Названия fields, API и кода — английские. Пользовательские строки централизованы для последующего i18n, без обязательного внедрения второго языка P0.

## 3. Информационная архитектура

Пути:

```text
/projects
/projects/:projectId/data
/projects/:projectId/imports/:importId
/projects/:projectId/workspace?scenario=…&run=…
/projects/:projectId/runs/:runId
/projects/:projectId/quality
/projects/:projectId/settings
/catalogs/rules
/catalogs/costs
/jobs
```

Deep link должен восстановить project/scenario/run после reload. Unsaved drafts имеют отдельный статус; browser back не должен молча терять важное изменение без предупреждения.

## 4. Главный рабочий экран

Desktop-first, оптимальный диапазон 1440–1920px. Основной сценарий работает и при 1280×800. На 1024px properties переносится в Sheet; на узком экране карта и параметры переключаются вкладками. Не пытаться одновременно поместить три панели на телефон.

```text
┌────────────────────────────────────────────────────────────────────────────┐
│ HeatRoute / Проект / Сценарий      Версия данных   [Сохранить] [Рассчитать]  │
├───────────────┬──────────────────────────────────────┬─────────────────────┤
│ Сценарий      │                                      │ Свойства            │
│ Здание        │            ОСНОВНАЯ КАРТА             │ Выбранный объект    │
│ Точка входа   │                                      │ Источник и версия   │
│ Кандидаты     │   карта, оси, коридоры, ограничения   │ Проверки            │
│ Методы        │                                      │ Неизвестные поля    │
│ Правила       │                                      │                     │
│ Слои          ├──────────────────────────────────────┤                     │
│               │ Варианты A/B/C · Сравнение · Задачи   │                     │
└───────────────┴──────────────────────────────────────┴─────────────────────┘
```

Header ~56px. Левая панель default 280px (240–420), правая 320px (280–480), нижняя 220px (160–400). Все размеры через tokens и Resizable panels, состояние пользователя сохраняется локально. Панели сворачиваются, карта автоматически resize.

На карте compact toolbar: select, choose entry, waypoint, draw forbidden zone, measure, fit project, layers, legend. Measure — вспомогательный инструмент с подписью метода; окончательная длина трассы только из backend.

Главный CTA «Рассчитать» запускает backend preflight. При незаполненных обязательных полях — конкретный inline список. Не запускать дорогой поиск при каждом движении курсора.

## 5. Компоненты

Применить готовые Button, Card, Input, Select/Combobox, Dialog, Sheet, Tabs, Tooltip, Badge, Accordion, ScrollArea, Resizable, DropdownMenu, AlertDialog, Progress, Skeleton, Sonner, Form. Таблицы — shadcn composition + TanStack Table.

Состояния каждого асинхронного блока: loading, empty, ready, error, stale, permission_denied. Skeleton не крутится бесконечно при network error; показывать retry. Empty state содержит одну предметную причину и одно релевантное действие, не декоративную «статистику 0».

Tooltips дополняют, но не заменяют видимые labels. Actions имеют понятные disabled reasons. Кнопка не может выглядеть работающей, если endpoint отсутствует: убрать или показать «недоступно» с причиной из capabilities.

## 6. Экран проектов

Карточки/таблица: название, режим synthetic/mixed/provided, актуальные версии, последний run/outcome, число открытых blockers, дата изменения. Поиск и sorting.

Создать проект: название, рабочая CRS/позже подтвердить, planning date. Отдельная кнопка «Открыть демонстрационный проект», без внешних ключей и ожидания реальных ресурсов.

Не придумывать экономию, подключённые здания, долю успеха и другие «цифры dashboard» до наличия вычисленных runs. Состояние пустого проекта должно быть полноценным.

## 7. Мастер импорта — обязательный сильный экран

Шаги:
1. **Файлы и источник.** Drag/drop, accepted formats из capabilities, прогресс upload, synthetic/provided source, примечание об использовании.
2. **Слои и координаты.** Названия слоёв, число объектов, preview geometry, CRS detected/unknown, unit/axis warnings.
3. **Сопоставление.** Слева исходные fields и samples, справа canonical field/kind, unit conversion, missing policy. Можно применить сохранённый profile.
4. **Проверка.** Counts accepted/quarantine/rejected, блокирующие проблемы, карта проблемных объектов, таблица с source rows.
5. **Публикация.** Сводка точных версий/repair actions/assumptions, кнопка подтверждения; потом новая версия появляется в проекте.

Ошибочные rows не скрывать. Для mixed CRS и отсутствующего source CRS запрещена «угадывающая» публикация. Пользователь может сохранить draft и продолжить позже. После reload текущий import сохраняется.

Ремонт геометрии требует preview before/after и отдельного действия. Красивый зелёный toast не заменяет отчёт о потерянных объектах.

## 8. Редактор сценария

Разделы: вход, кандидаты, параметры коридора, временной срез, правила/цены, цели, ограничения, вычислительный budget.

Вход: выбрать target building, назначить entry на границе или создать point-to-network scenario; тепловая нагрузка и её источник, layout подачи/обратки. Не требовать произвольную нагрузку только ради заполненного поля.

Кандидаты: карта + список; permission, provided capacity, reason при исключении. Unknown capacity явно серым статусом «нет данных», не 0 kW.

Геометрия: corridor width с единицами, construction methods, ordered waypoints. Рисование запрещённых polygon — создание user layer/revision, не изменение исходной поставки.

Rules/catalog selectors показывают version и demo/draft/reviewed. Демо-значения отмечены постоянно. Ширина/отступ/price допускают редактирование только через соответствующие scoped draft versions, а не магические hidden constants.

Режим strict/exploratory сопровождается кратким объяснением. Exploratory требует просмотра списка assumptions; нельзя общим чекбоксом «игнорировать все ограничения» включить запретные маршруты.

## 9. Расчёт и прогресс

При запуске сохранить revision, вывести job/run ID, показать stage: preflight, preparation, search, validation, evaluation, saving. Progress отображается процентом только при известном total; иначе stage + elapsed time.

Отмена — «Отменить расчёт» → cancel_requested → terminal state. Не завершать local animation и объявлять отмену, пока сервер продолжает работу без запроса cancel.

При разрыве SSE показать reconnect и продолжить с серверного snapshot/polling. Перезагрузка страницы не запускает новую задачу. Retry ошибки создаёт отдельный контролируемый attempt/run согласно backend semantics, не дубликат при каждом reconnect.

Partial result имеет отдельную плашку «поиск остановлен по лимиту; показаны проверенные найденные варианты». Timeout не отображается как «подключение невозможно».

## 10. Карта и варианты

Слои: buildings, roads, existing network, planned objects, coverage gaps, candidate nodes, user constraints, routes, full corridors, findings. Легенда и visibility toggles. Существующие сети показываются отдельно от новой трассы.

Выбранная трасса контрастная и сплошная, другие — менее выраженные/различные dash patterns. Цвет не единственный идентификатор: A/B/C, подписи, line styles. Геометрическая валидность не заменяется цветом маршрута.

Карточка альтернативы:
- буква и objective tags;
- выбранная connection point;
- длина коридора, оценка стоимости/partial badge;
- число переходов, длина неподтверждённого участка;
- раздельные статусы геометрии, capacity screening, гидравлики, качества данных;
- «Почему этот вариант», «Показать ограничения», «Сравнить», «Экспорт».

Если shortest и economical совпали — одна карточка с двумя tags. Если найден только один путь — честная одна карточка и объяснение limits/duplicates. Если ни одного — диагностика с candidate rejection reasons и возможными следующими проверками, без рисования прямой линии как результата.

Клик finding zoom/focus на участок и свойства. Возможность показать corridor width обязательна: так видно, почему тонкая линия проходила бы, но коридор не помещается.

## 11. Сравнение

Таблица альтернатив: candidate, route length, modeled cost + completeness, quantities, bends, crossings, unverified length, passed/unknown checks, assumptions, compute scope.

Сравнение «до/после»: тот же сценарий/версии или заметное предупреждение о несопоставимых версиях. Для local optimizer P1 — видимые изменения геометрии и количеств, а не только процент «улучшения».

Нормализованный search score не подписывается рублями. Экономия показывается только для comparable evaluations и положительного baseline. При missing rate позиция «не оценена» с именем недостающей ставки.

## 12. Паспорт, качество и история

Run details содержит ввод, версии и hashes, AOI/resolution/budget, algorithms, выполненные и невыполненные проверки, assumptions, findings, quantities, artifacts, timings. Показывать human-readable labels и копируемые IDs.

Quality page: coverage map по типам данных, missing fields, topology issues, публикация с quarantine, temporal inconsistencies. Completeness не называть вероятностью безопасности.

History: список scenario revisions и runs, authored changes, stale labels, возможность открыть старый run без его пересчёта/перезаписи.

## 13. 3D и визуальное расширение

P1: MapLibre extrusion для зданий, если есть heights [S17]; при default height — визуальная предположительность. 3D-модели и связь с features добавляются через adapter, не вшиваются в routing core.

Поворот камеры и «подземный вид» не должны создавать ложное ощущение проверки глубин. Если вертикальные данные отсутствуют, показывать предупреждение в 3D-режиме и не выдумывать z-координаты результатов.

P0 может оставаться полностью 2D и принимается при корректной функциональности. 3D не имеет права задерживать backend milestones.

## 14. Состояние приложения

TanStack Query — server cache/invalidation. Query keys включают project и immutable version/run IDs. Mutations обновляют/инвалидируют соответствующие keys. Не переиспользовать кэш одного проекта в другом.

Zustand — camera, selected object, panels, drawing draft. Запрещено хранить там единственную версию published dataset/scenario или результаты вычислений.

Map updates не должны пересоздавать весь React tree и карту. Debounced bbox requests, AbortController, cleanup map listeners/sources. Большие attribute tables — server pagination, затем virtualization при необходимости.

## 15. Приёмка UI

Проверить 1920×1080, 1440×900, 1280×800, узкий layout; светлую/тёмную темы; keyboard navigation; loading/error/empty/partial/stale states; перезагрузку посередине import/run; отсутствие внешней сети в demo.

Playwright сценарий должен дойти от demo/import до backend-computed route и server-generated export. Декоративный front-only mock не считается выполнением.

Дизайн принят, когда основная работа выполняется без объяснений разработчика, все данные читаемы, ни одна обязательная кнопка не является заглушкой, а локальная смена theme tokens меняет оформление без редактирования расчётных модулей.
