# Source103 — production deployment and full VPS benchmark

**Дата проверки:** 29.09.2026. **Статус:** production PASS для развёртывания, полного
официального stable depth-on расчёта и строгого экспорта. Это evidence конкретного прогона,
а не доказательство глобального оптимума или закрытие всех исследовательских R/G/N-gates.

## Что развёрнуто

- `master` и checkout VPS: `33eae276d8582cd833c4b2f66403aa3f5b345b1b`.
- Алгоритм результата: `global-tree-103`; профиль API: `stable`.
- Перед обновлением создан backup БД
  `/opt/heatroute/backups/heatroute-20260928T204832Z-aa608cb8bf4e4bf696600fabe3241e39ae903b51.dump`,
  1 467 923 байта, mode 600.
- API пересобран из checkout и контейнеры запущены без удаления volume. PostGIS, API, web и
  Caddy healthy; OpenAPI и публичный HTTPS прошли smoke.
- Readiness решает настоящую контрольную CP-SAT модель и сообщает
  `cp_sat: ok / ortools-9.15.6755`. Для executable Spring Boot JAR native runtime OR-Tools
  помечен `requiresUnpack`; до исправления вложенный native JAR нельзя было прочитать на Linux.

## Полный официальный расчёт

Официальный `datasets/official/lct-2026.geojson` был распознан как уже существующий immutable
import по SHA-256, поэтому повторный upload не выдаётся за свежий parse benchmark. Использован
valid import `f92e4ae2-fd49-47a4-a131-bdeecadef086`, 144 объекта / 17 ОКС, input SHA-256
`cffb7133419d93fe364a53015a7d3ead289f671cbfaf6f4befe87f2a21914130`.

Новый run `c61ce71e-9763-48f8-9752-80748b9c6cdd`, job
`5221b94a-125c-456b-86fa-51c506dc9a6c`, attempt 1, параметры stable,
`depth_enabled=true`, 0,7–10 м. Серверные timestamps: 28.09.2026 21:04:17.636213 UTC —
21:58:31.555113 UTC; чистое время job **3 253,918900 с (54 мин 13,919 с)**.

Предыдущий production source102 на том же VPS и depth-on профиле занимал 4 382,648 с.
Наблюдаемое source103 время ниже на 1 128,729 с, или 25,8%, но это не изолированный
speedup benchmark: source103 одновременно изменил обязательный axis-shift контроль и итоговый
portfolio/геометрию. Утверждение «полный расчёт за 30 секунд» неверно; 30,016 с относились
только к локальному corridor oracle с одним кандидатом.

## Результат и экспорт

Оба сохранённых варианта valid и подключают 17/17 ОКС; массивы `validation_issues`,
`engineering_issues` и `sizing_issues` пусты.

| Вариант | Ранг | Длина | Рёбра / узлы | Стоимость |
| --- | ---: | ---: | ---: | ---: |
| `cheapest` (preferred) | 1 | 2 192,535 м | 29 / 30 | 296 013 322,58 ₽ |
| `balanced` / engineering | 2 | 2 242,303 м | 30 / 31 | 304 973 207,18 ₽ |

Строгий HTTP export всех вариантов занял **16,542543 с** и вернул валидный
`FeatureCollection`: 134 feature / 55 720 байт, варианты `balanced,cheapest`, из них
65 `heat_network`, 25 `heat_chamber`, 42 `technical_node`, 2 `variant_summary`.
SHA-256 экспорта:
`68d7782fd2d420e4857ffbd520ce6aeb981deaa60a1f72718aa3d281f8092c6b`.

Production UI открыт по HTTPS с этим run: выбран `cheapest`, карта показывает 17/17,
2,19 км, 29 участков, 12 камер ветвления, 0 новых камер врезки, одно место подключения и
два новых луча; браузерная консоль — 0 errors / 0 warnings. UI-карточка «0 ошибок ·
76 предупреждений» относится к неблокирующей диагностике структуры входных данных и не
заменяет пустые обязательные issue-массивы вариантов. Локальный проверенный screenshot:
`output/playwright/source103-vps-latest-success.png`.

## Границы evidence

- source103 не доказывает глобальный минимум стоимости, длины или числа поворотов;
- в этом run сохранены два, а не три материально различных варианта;
- Ubuntu 22 compatibility, R9 и оставшиеся масштабные/исследовательские gates не закрыты;
- DNS credentials не использовались и секреты не сохранены в репозитории или артефактах.
