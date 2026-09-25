# Handoff — Артём / PM / developer

## Актуально: source80 — оптимизация сортировки препятствий

Сначала [PRIMARY_ROUTING_80.md](PRIMARY_ROUTING_80.md). Query-local primitive ordinals сохраняют
точные состав/порядок STRtree query, без нового кэша/изменения геометрии.5новыхtests, scoped81PASS,
clean/fast1227cases/1224PASS/0fail/0error/3skip; web36/scripts37/lint/typecheckPASS. End-to-end gain
не измерен. Full80session59635 на `.tooling/source80-build.GwJekn/apps/api/target` запущен.
Full79session51226/roads79session69123 делят frozen target79; roads77session2599 — target77.
Не менять targets и не дублировать процессы. Нужны exact variants80==79 и all-role quality,
затем roads/native/изображения. Runtime61/VPS неизменны; на checkpoint оставалось2%лимита.

## Актуально: source79 — не перестраивать целиком допустимую сеть

**Обновление:** full78session46700 завершён1220cases/1216PASS/1compactFAIL/3skip;17of17/strict
exportPASS, но cheapest3bad angles остаются. Target78 свободен. Full79session51226 и оба
roads77session2599/roads79session69123 продолжают работать; их targets не изменять.

Сначала [PRIMARY_ROUTING_79.md](PRIMARY_ROUTING_79.md). `PRESERVE_VALID` сохраняет все рёбра
после независимой полной проверки сети при окончательных ДУ; поисковые буферы новых замен
не ослаблены. 6новыхtests,3REDна78→PASS79; clean/fast1222cases/1219PASS/0fail/0error/3skip.
Scoped59PASS; контроль доводки saved75:6/6PASS/exact edges/0bad angles, **не fresh79**.
Web36/scripts37/lint/typecheckPASS. Статическое review без замечаний.

Живые targets: full79session51226→`.tooling/source79-build.RQBOQ4/apps/api/target`,
full78session46700→`.tooling/source78-build.X5TXbQ/apps/api/target`,
roads77session2599→`.tooling/source77-build.FeShm8/apps/api/target`. Не изменять их до завершения.
Дополнительно запущен roads79session69123, runner `.tooling/scenario79.Vhd4HA`, тот же target79:
**не очищать target79 после full79 до окончания roads79**. Лог `source79-roads.log`, вход239features
проверен по SHA в79. Fresh results ещё нет; все четыре процесса подтверждены живыми.
Следом явная all-role engineering проверка свежего79 (cheapest не покрыт текущими assertions),
roads79/native smoke и сравнение с экспертом. Source79 не является принятым релизом;
runtime61/VPS/compact/G2/scale/Compose/R не изменены. Осталось4%лимита, сохраняем checkpoint.

## Актуально: correctness78; качество77 ещё не принято

Сначала [PRIMARY_ROUTING_78.md](PRIMARY_ROUTING_78.md). Terminal direction/normal/sections
исправлены вместе;50новых tests, конечный clean/fast1216cases/1213PASS/0fail/0error/3skip,
web36/scripts37/lint/typecheckPASS. Последний transitive-crossing reviewer finding исправлен
и закреплён тестами. Scope не включает все nonterminal reversal/undirected trunk callers.

**Живые процессы:** full78session46700 на `.tooling/source78-build.X5TXbQ/apps/api/target`;
roads77session2599 на `.tooling/source77-build.FeShm8/apps/api/target`. Оба target заморожены.
Логи/accepted/diagnostics — `.tooling/intake-20260925/`; дождаться, не дублировать расчёты.
Все агенты завершены. Main runtime61/VPS неизменны, full78 ещё не принят.

Следующий quality bugfix: `PRESERVE_VALID` отказывает ранее официально допустимой ветви из-за
поискового буфера accepted-route;77 разблокировал repair и добавил плохие углы. Bounded75/77
trace это подтвердил для двух ветвей; весь portfolio/третий угол ещё не объяснены. В78 это
**не исправлено**. Нужен regression и authoritative final-ДУ preservation, не ослабление
официальных отступов и не исключение целых соседних трасс. Затем fresh all-role quality.
На последней проверке оставалось6%недельного лимита; изменения сохраняются checkpoint'ом.

## Приоритетная актуализация: full77 завершён, quality regression открыт

Full77:1170cases/1166PASS/1compactFAIL/3skip;fixture526,533с/17of17/strict exportPASS.
Однако cheapest2073,965м/11камер/3плохих угла, shortest2192,300м/14камер: хуже75.
Не обновлять runtime и не считать существующий fixture достаточным quality gate всех ролей.
Roads75 завершён2838,622с/16of17,exact variants72,толькоdiagnostic. Snapshot75target свободен.
**Живой roads77session2599** использует `.tooling/source77-build.FeShm8/apps/api/target`,
runner `.tooling/scenario77.iPcahu`, `source77-roads.log`. Snapshot77target снова заморожен.
В checkout WIP78 направления ввода (99focusedPASS после incoming-normal fix); полный gate
не закончен. Параллельно bounded audit77 выясняет причину ухудшения retention/portfolio.

Сначала [PRIMARY_ROUTING_77.md](PRIMARY_ROUTING_77.md). Исправлен shared-junction self-blocking
в final-ДУ repair: локальное исключение по node identity, не исключение целого соседа.
29новых tests,111focusedPASS,65independentPASS; конечный clean/fast1166cases/1163PASS/
0fail/0error/3scale skip и web36/scripts37/lint/typecheckPASS. Полный итог выше.

- **Full77session79101 завершён**: `.tooling/source77-build.FeShm8/apps/api`, `source77-full.log`,
  accepted `source77-result.json`, diagnostic `source77-diagnostic.json`. Target заморожен.
- **Roads75session70338 завершён exit1**: `.tooling/scenario75.VxnM8P`, `source75-roads.log`,
  `.tooling/source75-build.9a8BDz/apps/api/target` больше не занят.

Логи/результаты — `.tooling/intake-20260925/`; fast77 reports сохранены отдельно. Сначала
дождаться текущих процессов, не дублировать fresh calculation из-за тихого лога. После77
отдельно исправлять road-entry direction demand→root versus stored root→demand, включая
выбор нормали и whole-line checks; нельзя требовать оба угла вместо правила входа.
Runtime61/VPS не обновлены; compact/G2/native/Compose/scale/R и цель остаются открытыми.

## Приоритетная актуализация: audit76 / full75 завершён

Сначала [PRIMARY_ROUTING_76.md](PRIMARY_ROUTING_76.md). Goal-priority pruning отклонён:
два реальных epsilon-chain контрпримера. Численная подготовка эквивалентна, но измеренного
выигрыша не дала: тоже удалена. Production **неизменён75**, только12новых SearchPriority tests
и evidence. Web gatesPASS; конечный clean/fast Maven1137cases/1134PASS/0fail/0error/3skip,
reports `source76-checkpoint-fast-reports/`, main побайтно как full75. Далее shared-junction self-blocking,
потом отдельный terminal-direction mismatch; реальные small RED в76, не ослаблять нормы.
Не использовать предварительные1135cases отклонённого patch как evidence конечного76.

Full75session70380 **завершён**:1129cases/1125PASS/1compactFAIL/3skip;fresh526,467с,17/17,
strict export3ролейPASS,exact variants74. Reports `source75-full-reports/`, accepted75 сохранён.
Roads72session19058 тоже **завершён**, но failure16/17 во всех ролях; только diagnostic.
Основной target теперь свободен. Snapshot75target всё ещё заморожен для **roads75session70338**,
его не перезаписывать. Runtime61/VPS не менялись; compact/G2/native/Compose/scale/R открыты.
Ниже исторические статусы; указания о живом full75 или roads72 уже не действуют.

## Приоритетная актуализация: source75 / full74 завершён

Сначала [PRIMARY_ROUTING_75.md](PRIMARY_ROUTING_75.md). Final-ДУ retention исправлен по
реальному RED,15постоянных tests, независимое review24PASS. Финальный быстрый Maven:
1125cases/1122PASS/0fail/0error/3scale skip;web36/scripts37/lint/typecheckPASS.
Это ещё не accepted fresh75; не объявлять compact/speed/G2/R готовыми.

**Один snapshot75 заморожен для двух живых задач:** `.tooling/source75-build.9a8BDz/apps/api`.
Не запускать там новый Maven и не менять target до завершения **обоих**:

1. Clean/full75 **session70380**, `source75-full.log`, accepted `source75-result.json`,
   before-assertions `source75-diagnostic.json` отдельно.
2. Fresh roads75 **session70338**, runner `.tooling/scenario75.VxnM8P`, `source75-roads.log`,
   accepted `source75-roads-result.json`, before-assertions `source75-roads-result.json.diagnostic.json`.

Fast75 reports сохранены в `source75-fast-final-reports/`; после full сохранить отдельный
snapshot, сравнить результат с74/72 и проверить качество cheapest дополнительно к helper.
Новый результат нарисовать рядом с Евгением в том же масштабе. Следующая гипотеза A* pruning
описана в75 и пока не реализована; сначала measured/equivalence tests, не ограничивать качество.

Full конечных74session37091 завершён:1114cases/1110PASS/1compact failure/0errors/3scale skip,
fixture532,720с,17/17/strict export3ролей PASS, все variants точно как73. Accepted
`source74-final-result.json`, отчёты `source74-final-full-reports/`; оба snapshot74 свободны.
Ранний pre-budget74session16835 завершён и сохранён отдельно, не подмена конечного gate.
Время fixture73 исправлено:529,767с;533,933с — весь класс из2тестов.

**Основной** `apps/api/target` всё ещё занят roads72session19058/PID33818. Не clean/не
перезапускать с нуля: процесс живой, CPU-поиск. Параллельное время не является SLA.
Fresh roads73/74 отдельно не выполнялся. Runtime61/VPS неизменены. Logical crossing chains,
остальные G2, compact/native/Compose/scale открыты. Все агенты75 завершены и закрыты.
Fetch подтвердил origin/master28c7059, новых upstream commits нет.
Старые блоки ниже — история, не действующие указания о живых сессиях.

## Следующий checkpoint72: road/tram + atomic special sections

Код **`a410259` pushed**,remote SHA проверен. Теперь работает **clean/full72 session53894**,
лог `source72-full.log`,без исключений,Java11/Xmx1g/CPU2. НЕ менятьtarget/не запускатьMaven
до завершения. Затем snapshot reports, accepted `source72-result.json`/before-assertions
diagnostic различать; fresh metrics/export/PNG, далее roads72/native. Последующий docs-only
commit не меняет замороженный compiled72.

Сначала [PRIMARY_ROUTING_72.md](PRIMARY_ROUTING_72.md). Финальный быстрый Maven1049cases/
1046PASS/0fail/0error/3scale skip (3долгих класса исключены);web36/scripts37/lint/typecheckPASS.
Предыдущий broad22190 завершён; его2fixture failures/2rounding errors исправлены и повторены.
Review3findings исправленRED→GREEN; export-agent38new+42existingPASS, оба агента закрыты.
Target compiled72; следующийclean/fullбезисключений. Fresh71 PNG создан и просмотрен;
full72/roads/native/Compose/scale остаются обязательными. Особый открытый риск — special
через границу логических рёбер. Runtime61 неизменён, цель не завершена.
Read-only recheck старого roads69 уже выявил8нарушенныхрёбер уshortest/cheapest:
7непрямыхspecial,1боковойотступ1,467297<1,755м. Это неfresh72; новая генерация обязательна.

## Актуализация25.09 ~05:28MSK: full71 завершён, идёт G2 fix72

Приоритет над историческими записями ниже: session28562 завершилась exit1 только из-за
прежнего compact-control.969cases/965PASS/1failure/0errors/3scale skipped. Fresh original
fixture513,704с;17/17,shortest/cheapest2068,786м/11камер/22поворота/283006479,92₽,
depth/strict export3ролей PASS. Snapshot `source71-surefire-reports/`,accepted
`source71-result.json`, before-assertions diagnostic отдельно. Target можно пересобирать.

Uncommitted72: road/tram clearance/actualboundaryangle/protective3m/порталы; новыеfocused
тесты, exporter guard интегрируется. Полного72/fresh/roads/native пока нет. Runtime61 сохранён.
Не принимать source71 strict export за доказательство G2: он воспроизведённо пропускает отступ.

## Текущий source71: bounded terminal shortening, ещё не fresh

Сначала [PRIMARY_ROUTING_71.md](PRIMARY_ROUTING_71.md).125focusedPASS, реальный80→60RED→GREEN.
Production-helper replay всех69ролей:shortest/cheapest2068,786м/11камер/22поворота/0expert/
283006479,92₽,balanced прежний;17/17/depth/strict export3ролей PASS. Не fresh71.
Код `0d4d462` отправлен. Full **compiled70** закончен:951cases/947PASS/1failure(compact-control)/
3skip,fresh500,035с/17of17/strict export3ролей PASS,всеvariants точно как69. Snapshot
`source70-surefire-reports/`/accepted result сохранён. Теперь full **compiled71** работает
в target (`source71-full.log`,session28562): не перезаписывать классы до завершения.
Потом snapshot71/fresh71/roads/картинки/native. Runtime61 не обновлялся. Isolated
`shortening71-focused.U9DmSn` — только focused/replay, не подмена full build.

Следующий correctness scope — [G2_SPECIAL_CLEARANCE.md](G2_SPECIAL_CLEARANCE.md): реальные
4RED/2controls для road/tram clearance. Нужен согласованный guard+spatial bounds+порталы+
export, не просто новая ошибка после фильтра, который уже выкинул близкую дорогу.
Fixed3м special и разрешённый угол не отменяют внешний габарит. Production пока не изменён.

## Предыдущий source70: bounded spatial validation, full/fresh ещё впереди

Сначала [PRIMARY_ROUTING_70.md](PRIMARY_ROUTING_70.md). Focused101PASS, validation-only
replay69 3роли×3повтора:exact issues0,655buffers/43183coord стабильно после прогрева.
Нормы/порог качества не менялись. Пока нет full/fresh70; runtime61 не обновлялся.
Roads69 завершён:1054,672с/17of17/strict export3ролей PASS, shortest/cheapest2260,959м/11камер/
33поворота/0expert. Accepted `source69-roads-result.json`; diagnostic отдельно, не подмена.
Target больше не занят69. JFR дорогого поиска описан в70. Далее clean/full/fresh70,
exact variants/export/time comparison, web gates; не обновлять runtime по одному replay.

## Предыдущий source69: подготовка ограничений внутри validation

Сначала [PRIMARY_ROUTING_69.md](PRIMARY_ROUTING_69.md): measured hot spot JFR67, сессия
точного валидатора, неизменные проверки/standalone-export. Subclass-hook сохранён после
реального RED, isolated86/86PASS, код `63b0072` отправлен; web36/scripts37/lint/typecheckPASS.
Compiled67 завершён:924cases/920PASS/1failure(compact-control)/3skip; fresh640,761с/17of17/
strict exportPASS, shortest/cheapest11камер/2090,416м/23поворота/0expert. Snapshot/PNG сохранены.
Clean69 завершён:936cases/932PASS/1failure(compact-control)/0errors/3skip. Fresh660,385с/
17of17/strict exportPASS, все variants точно совпали с67. Полный snapshot69 и PNG сохранены.
Ускорения нет:117constraints/~35kкоординат наДУ,100kбюджет вытесняется на цикле7–8ДУ.
Следующий безопасный пространственный отбор и80→60м quality-проба описаны в69; НЕ реализованы.
Сейчас отдельно выполняется roads+kindergarten через ignored `ScenarioRoutingProbe`,
лог `source69-roads.log`, цель `source69-roads-result.json`. Не перезапускать без проверки
живого процесса и не выдавать before_assertions diagnostic за accepted bundle. Проверить
17/17/3роли/depth/strict export, затем графику. Это не HTTP/PostGIS/scale проверка.
Не использовать старые isolated planner68-классы впереди target69. Runtime61 пока сохранён.

## Предыдущий source68: контрольные relocation-ветви сохранены

Сначала [PRIMARY_ROUTING_68.md](PRIMARY_ROUTING_68.md), код `ae452a7`. Независимое review
обнаружило в67 потерю исходного улучшения80→70м после ремонта конкурента до75м. Реальный
RED→GREEN;79focusedPASS. В relocation остаются исходные3роли+1repair, промежуточного
вытеснения нет. Full **compiled67** ещё выполняется; не переносить его результаты на68.
После него snapshot, метрики/JFR, затем fresh68/export и roads+kindergarten. Runtime61/VPS
не обновлялись; полного no-loss/performance/R-gate пока нет.

## Предыдущий source67: clean/fresh прогон выполняется

Сначала [PRIMARY_ROUTING_67.md](PRIMARY_ROUTING_67.md). Код `ff1a4c1`/`da44a56` отправлен.
Поздний cheapest получает существующую инженерную доводку после выбора portfolio, только
если это даёт полный compliant результат без роста цены/длины; исходные роли сохранены.
Replay66:2090,416м/11новых камер/23поворота/0expert/17of17/strict exportPASS. Это НЕ fresh67.
Подготовка obstacle buffer/hull ограничена одним поиском и бюджетом памяти;80router PASS.
Repair77focused/4guardsPASS; web36/scripts37/lint/typecheckPASS. Clean924/fresh67 выполняется,
надо дождаться результата, сохранить
surefire snapshot и сравнить все роли/экспорт/время. Runtime61 не обновлять по одному replay.

## Предыдущий source66: 11 камер у cheapest, общий quality gate открыт

Сначала [PRIMARY_ROUTING_66.md](PRIMARY_ROUTING_66.md). Код `8d58875` / `3c50437` в origin,
ветка `codex/routing-63-geometry`. Исходная aggregate-DU сетка сохранена; вторая добавляет
достижимые индивидуальные вводы без снижения отступов ствола. Synthetic79,0→≤63,05м,
rotation/UTM precision fix, focused55/55PASS. Web36/scripts37/lint/typecheckPASS.
Clean full914:910PASS/1failure/0errors/3skip; failure — прежний compact-control.
Fresh652,482с/17of17/strict export всех3ролей PASS. Cheapest2092,274м/11новых камер,
1существующий корень/24поворота/285145228,70₽/1неподходящий угол; balanced/shortest всё ещё
2192,523м/14/25/0expert. Меньше камер и дешевле65, но чуть длиннее и заметно медленнее;
это не общий no-loss PASS. PNG66/Evgeny просмотрен. Сначала устранить оставшийся угол179,426°
на реальной геометрии, затем fresh whole gate. Отдельный патч подготовки obstacle buffer/hull
ещё не принят, не путать его с compiled66. Локальный JAR61 и VPS не менялись.

## Предыдущий source65: наклонные вводы, общий quality gate открыт

Сначала [PRIMARY_ROUTING_65.md](PRIMARY_ROUTING_65.md). Код `aff1dcf` в origin,
ветка `codex/routing-63-geometry`; master и работающий JAR61 не менялись. Ограниченные
диагональные переходы добавлены только для наклонных нормалей, прежние прямоугольные вводы
сохранены. Весь Java-набор двумя прогонами909:905PASS/1failure/0errors/3skip; единственный
failure — compact-control. Fresh443,775с/17of17/strict export всех3вариантов PASS.
Balanced/shortest остался2192,523м/14камер/25поворотов/0expert; cheapest2080,633м,
13узловых+1новая корневая камера/37поворотов/4неподходящих угла,289824529,03₽,
score14,356985813. Цена ниже64, но длина иscore хуже; не принимать как no-loss improvement.
Web36/scripts37/lint/typecheck PASS; native65/Compose/scale и ускорение не заявлены.

Следующий RED/fix: сохранить общие anchors и дополнить индивидуальными вводами (сейчас
общийДУ может выбрать другую ближайшую стену, чем ДУ ветви). Контрпример79→63,04м,
точные координаты/границы проверки и снимки65 — в отчёте. Не снижать ДУ проверок ствола.
Отдельно проверить сохранение старых портфельных контролей, не менять objective вслепую.

## Предыдущий source64: строгие вводы, не принятый runtime

Сначала [PRIMARY_ROUTING_64.md](PRIMARY_ROUTING_64.md). Рабочая ветка по-прежнему
`codex/routing-63-geometry`; source64 — единственный основной алгоритм этой ветки.
`master=28c7059`, работающий API/JAR61 не менялся. Нельзя переносить исторический зелёный
full63 на новый код64: изменились нормали, отступы и достижимые порты.

Code checkpoints `2de2ceb`/`0270971` отправлены, remote проверен. Итог clean904:
900PASS/1failure/0errors/3skip; **full suite красный только на compact-control**.
Финальный свежий официальный fixture и strict export3вариантов PASS, concave cases PASS.
Инженерный2192,523м/14камер/25поворотов/17of17/0expert issues; cheapest1983,620м/14/28
с6нарушениями. Backend preferred=cheapest поscore, не путать с инженерной рекомендацией.
447,912с с JFR-срезом — не доказательство ускорения. Финальный bundle
`source64-final-result.json`; это не native64. Основная следующая цель — компактность/скорость.

Локальные safety-регрессии нормалей/actual-leg/округления/сжатия исправлены. R+W/2 включён
только для forbidden; G2 special/depth/existing-DU ещё открыт. Последний fast900/0/0/3skip
и web36/scripts37/lint/typecheck PASS. Reviewed dataset дал17/17,2192,523м/14камер/25поворотов,
0экспертных нарушений balanced/shortest, но strict export остановился на1копейке.
UTM rounding fix `2de2ceb` устранил причину: strict export replay3вариантов/568features PASS.
Одиночный concave fixture также исправлен. Эти промежуточные пробы предшествуют свежему
финальному результату выше (`source64-final-full.log`). Не поднимать
compact-control пороги вслепую и не убирать quality assertions.

Следующий приоритет: сократить длину/камеры/время инженерного дерева, сохраняя ноль
экспертных нарушений и strict export. Диагональные2поворотные предложения восстановили3/12
отдельных подходов в диагностике, но ещё не интегрированы/не дали нового полного дерева.
Проверить гипотезу
несогласованности aggregate-DU anchors и actual-DU spurs. Не расширять перебор без диагностики.
Далее закончить G2 и fresh roads+kindergarten сценарий. 0,25м — безопасный поисковый запас,
не новое требование ТЗ; принадлежность ОКС нельзя определять только по rounded endpoint.
G1/G2/G4/G5/G6, R-этапы, native64/Compose/scale и общая цель остаются открыты.

## Предыдущий source63: технический gate не является приёмкой64

Рабочая ветка `codex/routing-63-geometry`; `master` оставлен на `28c7059`.
Экспортный rounding fix — `3d9b3b0`, основной routing checkpoint — `d4d6ef5`; оба отправлены,
remote SHA проверен. Не сливать/разворачивать всю
ветку только потому, что технические gates зелёные: quality gate ниже открыт.

Последний clean Java gate **PASS**:879/0/0/3skip,876выполнены,5:51;
`source63-stable-full.log` / `source63-stable-result.json`.17/17 и strict export PASS.
Preferred1857,156м/13камер/13поворотов,276,574с; ускорение не доказано. Экспортные2копейки
исправлены в `OfficialGeoJsonExporter` (миллиметровые ценовые станции), RED2→GREEN61;
допуск1копейки остаётся строгим. **Не обновлять runtime61 только по зелёным тестам:**
balanced длиннее baseline, cheapest10камер/24поворота,7экспертных нарушений вместо0.
Ремонт cheap улучшил7→3, но остановился, не включён. Задан вопрос об обязательности
экспертной допустимости для всех ролей; не подменять objective молча. Отдельный PNG63/Evgeny
— fixture, не live job. Следующий шаг: решить качество экономической роли/профиля,
затем native fresh job+HTTP export; строгие нормали/R+W/2 всё ещё открыты.

Сначала [PRIMARY_ROUTING_63.md](PRIMARY_ROUTING_63.md): изменения направления ввода/портов,
baseline28c7059. Исправлены spacing через degree2 и положительные миллиметровые звенья.
Прямой почти соосный ввод — дополнительный кандидат, не безусловная замена L-контроля.
Последний законченный полный snapshot871/1failure/0errors/3skipped ещё нарушал spacing
у shortest. Добавлен приоритет сетей без экспертных нарушений для инженерных ролей при
том же полном охвате: RED1→GREEN57selector/terminal. Полный повтор и экспорт —
Промежуточный `source63-selector-full.log`:873/0failures/1error/3skip, отказ strict export
у cheapest по компонентам экономики; последующий fix/gate описан выше.
Bounded-подграф без перенаправленных коротких L-контролей уже вернул13-камерный черновик
1858,476м; RED1→GREEN1, focused403/403 PASS. Полный finish/export ещё не повторён на этой версии.
После него обязателен quality gate против62 (1857,155м/13камер/13поворотов).
Более ранний зелёный866-case gate регрессировал и не принят. Дополнительные штрафы/поисковые
веса и перебор всех первых терминалов не помогли и остались диагностикой.
Не включать строгие нормали поверх незавершённого quality gate. Runtime61/VPS не тронуты.

## Новый этап25.09: источники, осевые отступы и нормальные вводы

Сначала [EXPERT_ROUTING_2026_09_25.md](EXPERT_ROUTING_2026_09_25.md). База после pull —
`2e7b713`, основной алгоритм `global-tree-62`. Добавлен отдельный пользовательский сценарий
`datasets/scenarios/roads-kindergarten.geojson` (исходные144 +94дороги +1social_area,17потребителей),
с provenance/hash и тестом неизменности официальной части. Demo не заменён.

Критичный пробел ТЗ: §3.1 требует R+W/2 до оси; текущий forbidden-clearance использует только R.
Ещё нет обязательного наружного прямого ввода достаточной длины и истинной нормали к стене.
Подготовлены `OfficialAxisClearance` и `BuildingWallNormals`, но **не подключены к runtime**.
Глобальные пробы отклонены: первая потеряла нормали; вторая15/17; третья собрала17, но нарушила
углы на стыках порта. Интеграционный патч в `.tooling/intake-20260925/strict-egress-integration.patch`;
production-файлы сохранены как в HEAD, тесты17/17 не ослаблены. Следующий шаг — направление
обязательного ввода в состоянии поиска и совместный выбор порта/луча, затем включение R+W/2
одновременно в поиск и финальную проверку. Не повторять глобальную подмену одного helper.

Открытые вопросы пользователю:2м или3м до поворота камеры; есть ли разрешение организаторов
на выход через social_area для детсада. Текст ТЗ явно разрешает исключение только для своего oks.
G1–G6, цель, native/Compose/scale не закрыты. Сервер/VPS в этом этапе не обновлялись.
Предыдущие source61/runtime записи ниже — исторические. Итоговые gates — в `progress.md`.
Clean Java820/3skipped PASS +последние71focused PASS; web36/scripts37/lint/typecheck PASS.
Общий код планировщика не изменён. Публичный контракт не менялся; это component checkpoint,
не пройденная интеграция строгих правил и не готовая новая трасса.

## Source61: fresh dataset, native и первоначальная вкладка UI PASS

[PRIMARY_ROUTING_61.md](PRIMARY_ROUTING_61.md). Источники61;742 fast +3 datasets =745 Java
выполнено /3 skipped, web31+scripts35, lint/typecheck PASS. Preferred1857,155 м/13 камер/
13 поворотов/273953260,00 ₽/17of17 вместо58 с1887,222 м/13/16. Cheapest1895,501 м/11 камер/
21 поворот, без новой врезки. Planner279,503 с — одиночный fixture, не UI-время.
Защищены сжатие и стыки, добавлена одна ось по вводам для первого корня и refinement
регуляризованных победителей. Цель11 камер с хорошей формой не достигнута. Сейчас API61;
JAR/readinessUP, job614015d5-ad76-4b83-848f-003dd493c075 completed/attempt1,
run1a00e3a5-a818-49f7-abba-a920b9dfdc85,321,618 с против58:312,305 с — ускорения нет.
Native preferred shortest1857,155/13 камер/13 поворотов, cheapest1895,501/11/21 без новой
камеры врезки, balanced прежний1932,357/15/14. Все17/17, независимый topology/flow/slots/
length и HTTP export200/974 features PASS; оба actual61 PNG с Евгением просмотрены.
Native cheapest дороже58 на769810,36 ₽ при удалении одной новой камеры врезки.
UI выбирает preferred при valid/max-connected/явном отсутствии engineering issues,
иначе engineering fallback; refetch не отменяет ручной выбор. RED2 ожидаемых, итог36 web+
35 scripts, lint/typecheck/build, browser/map/console PASS; build предупреждает о крупном
MapLibre chunk. Java/JAR после полного gate неизменны.
Дальше — bounded совместный seed пары/тройки. Abstract oracle независимо повторён:
1024 подмножества/7 допустимых/минимум11;10 greedy/metric попыток упускают общий ствол.
Это не геометрический fixture. Начать с focused tests/oracle, затем ограниченные fresh
official-кандидаты; не ослаблять допуск и не увеличивать слепой перебор.
Исходники61 отправлены в Git commit9a8b9e0; новый checkpoint добавляет native evidence и UI.
Повторный review субагента упёрся в quota, это не пройденный gate. Пользователь разрешил
Git push/checkpoints при низком лимите; VPS не трогать. Compose/scale/R-этапы открыты.

## Исследование60: кандидат11 камер проверен, но не заменяет рабочую58

[PRIMARY_ROUTING_60.md](PRIMARY_ROUTING_60.md). Повторный перенос листа:1941,530 м/11 новых
узловых камер/20 поворотов/17of17; настоящие sizing/depth/export и отдельный checker
топологии/расходов/корневых лучей/углов PASS. Это replay, не fresh UI plan; форма хуже
preferred58, не интегрировано. Картинка `routing-comparison-60-candidate/side-by-side.png`
просмотрена: слева явно исследовательский кандидат, справа Евгений1913,859 м/11 маркеров/
15 поворотов. Не выдавать её за работающую версию. API58 readinessUP, target/JAR unchanged.
Оси от вводов и последующие локальные обмены не дали выигрыша; аудит104 raw draft не нашёл
пропущенного неухудшающего кандидата. Дальше — инвентарь направлений и bounded-проба целого
дерева в альтернативной общей системе осей, переданной одинаково grid/spur router.
Не расширять локальный перебор вслепую; не ослаблять допуск/selector. Вопрос о компромиссе
«Сбалансированного» пока без ответа, это не согласие на изменение приоритетов и не blocker.
Новых full-plan/live/Compose/scale gates, Git/VPS и закрытия цели/R-этапов нет.

## Исследование59: парные переносы, рабочая версия остаётся58

[PRIMARY_ROUTING_59.md](PRIMARY_ROUTING_59.md). Три ограниченных пробы,64 настоящих
candidate finish/export PASS, но не найдено неухудшающей замены среди финалистов.
13/16/7 нерегулярных пар стоит на80987,41 ₽ дороже;12/17 увеличивает нерегулярность до11.
Не интегрировано, новый fresh plan/UI job не запускался; API58 readinessUP.
Выяснены ограничения фиксированных вводов и конфликты направлений. Дальше — оси от
допустимых вводов и проверка вытеснения кандидатов в top-N; не расширять перебор вслепую.
Результаты/границы checker подробно в отчёте. Цель, Compose/scale/R-этапы открыты.

## Текущий checkpoint58: dataset и native smoke PASS

[PRIMARY_ROUTING_58.md](PRIMARY_ROUTING_58.md). Finalizer ошибочно сравнивал допустимый ввод
с направлением на удалённую камеру; теперь проверяет фактическое последнее звено, как validator.
Глобальное включение ухудшило свежий shortest13→14 камер; initial58 отклонён, не развёрнут.
Теперь старый способ сохраняется в core, а ограниченная доводка переносов камеры сравнивает
обе политики после полного допуска. Добавлена проверка препятствий на последнем вводе.
Final fast727 +datasets3 =730 выполнено /3 skipped, web31+scripts35, lint/typecheck PASS.
Весь result равен57 кроме верхней версии; corridor равен. Planner299,002 с, заметного ускорения
нет. JAR/API58 PID84531 readinessUP, fresh UI job76e08f8b-a3f9-447b-9f11-add6d902e895
completed/attempt1,312,305 с. Run9caa60ec-9a35-40f9-83e0-cadfe5485f8c. Весь native result
также равен57 кроме версии, HTTP export200/1030 features/тот же SHA, UI/карта17/17 PASS.
Preferred1887,223 м/13 камер/16 поворотов без изменения; обе actual58-live картинки просмотрены.
Browser console clean, server client-abort шум остаётся. Вкладка оставлена на shortest/rank1.
Compound probe12 камер не интегрирован:
выигрыш камер/узлов/цены, но+2 поворота
у лучшего компромиссного варианта. Не показывать candidate4 до исправления как улучшение:
его16 поворотов получались после разрушения ортогонального узла финализатором.
Дальше — согласованная перестройка части ствола и нескольких подходов с сохранением incumbent;
не расширять перебор вслепую. Git/VPS, Compose/scale и R-этапы не закрыты; цель активна.
Native57 ниже — отдельный предыдущий checkpoint.

## Предыдущий checkpoint57: dataset и native smoke завершены

[PRIMARY_ROUTING_57.md](PRIMARY_ROUTING_57.md):fast718 +datasets3 =721 Java выполнено /3 skipped,
web31+scripts35, lint/typecheck PASS. Убраны повторные sizing/экономика внутри сортировок;
исправлены лишние копии fallback и резерв координат индексов. Полный результат deep-equal56
кроме версии; corridor равен. Planner299,930→298,629 с — заметного ускорения нет.
JAR57 собран; API57 PID81078 readinessUP. Свежий UI job
`a80b9b0d-c25c-4a0d-9654-6eb7b2922b39` completed/attempt1,305,650 с;
run `961aff95-7280-4460-96b4-cda7f144e1c1`. Весь native result равен56 кроме версии,
HTTP export1030 features с тем же SHA PASS, UI/карта17/17, console clean (server client-abort
шум остался). Preferred1887,223 м /13 камер /16 поворотов, существенного улучшения нет.
Пользователю показывать `routing-comparison-57-live-preferred/side-by-side.png` и57-live-cheapest,
не depth-on fixture; обе картинки просмотрены. Git/VPS, Compose/scale и R-этапы без изменений.
Оба геометрических probe не интегрированы: Hanan-filter-before-cap и перенос листа дают12 камер,
но худший score и больше поворотов. Лучший допустимый перенос1906,953 м /12 камер /17 поворотов,
275817240,07 ₽,17/17, полный sizing/depth/export PASS. Это не улучшение preferred.
Дальше — bounded probe «перенос листа + перенос принимающей камеры со всеми подходами»;
при отсутствии выигрыша — согласованная перестройка ствола/нескольких ветвей. В57 этого нет.
Ниже56 — предыдущая история; текущий источник показателей — фактический native57 run.

## Предыдущий checkpoint56: ускорение точного предиката, 24.09.2026

[PRIMARY_ROUTING_56.md](PRIMARY_ROUTING_56.md). Java695 выполнено /3 scale skipped,
web31+scripts35, lint/typecheck PASS. Full all-17 с depth/sizing/economics/export PASS;
весь результат равен55 кроме версии, corridor равен полностью. Время350,860→299,930 с
в одиночных замерах;55 включает короткий JFR. Preferred17/17,1887,222 м /13 камер /16 поворотов.
Картинка `.tooling/routing-comparison-56-preferred/side-by-side.png` обновлена и просмотрена.
Локальный API уже56 PID78262, readinessUP; PostgreSQL:55432/Vite:5173 без Compose.
Свежий UI job `0871d44d-c89a-47f8-9526-0b1c4333c0c8` completed/attempt1,309,280 с;
карта/результат/HTTP export1030 features PASS. Реальный default2D preferred:
1887,223 м /13 камер /16 поворотов,276461145,20 ₽,17/17. Пользователю показывать
`.tooling/routing-comparison-56-live-preferred/side-by-side.png` (и56-live-cheapest),
не depth-on fixture: live отличается из-за иного режима/пути подготовки данных;
вклад каждого фактора отдельно не изолирован. Подробности в source56.
Следующий отдельный performance gate: oversized fallback без лишней копии + честный budget
дополнительных координат. Затем устранить повторный `draftScore` внутри сортировок
(sizing/economics уже выполняются при admission), с точным сохранением top-3/order/finish,
и измерить итог. Выигрыш пока гипотеза. Отдельно — смена топологии для13→11 камер.
Не увеличивать перебор вслепую/не ослаблять валидатор; Git/VPS, Compose/scale и R-этапы не закрыты.
Нижние записи — история, их live49/«следующий шаг» не заменяют этот checkpoint.

## Проверенный локальный checkpoint55: согласованный перенос камеры, 24.09.2026

[PRIMARY_ROUTING_55.md](PRIMARY_ROUTING_55.md). Изменение включено в исходный основной pipeline
после выбора трёх ролей. Fast668 выполнено /3 scale skipped +dataset3/3 PASS =**671 Java
выполнено /3 scale skipped**. Свежий полный plan55 подтвердил1898,684→1887,222 м,18→16 поворотов,
13 камер без изменения; цена снизилась на1 209 456,31 ₽,17/17 сохранены. Глубина, sizing,
экономика и экспорт проверены; web31+scripts35 и lint/typecheck PASS. All-17 350,860 с,
с45-секундным JFR, без заявления ускорения. Картинки55-preferred/55-cheapest построены
из `primary55-final-official.json` и просмотрены. Live49 readinessUP, новый runtime/Compose
не проверены; Git/VPS без изменений. Следующий резерв скорости — точный segment/polygon
predicate, геометрии — смена ветвлений для сокращения камер. Цель не закрыта.
Игнорируемый `.tooling/perf56/` содержит прототип быстрого predicate:112542 differential
сравнения,0 расхождений, синтетический microbenchmark1,18–1,97×. Не интегрирован; нужны
реальные ограничения, учёт подготовки/памяти и full-result equivalence. Это не версия56 runtime.

## Предыдущий локальный checkpoint54: упрощение допустимых ступенек, 24.09.2026

[PRIMARY_ROUTING_54.md](PRIMARY_ROUTING_54.md). Новый helper сохраняет концевые звенья и вводы
в ОКС, сокращает только наружную внутреннюю часть. Повторный полный finish обязан подтвердить
геометрию, ДУ, глубину и цену. Regression141→81 м /4→0 поворотов PASS, отрицательные случаи
удорожания, пересечения соседних веток и плохого угла на стыке тоже проверены.
Первоначальный Java fast622 выполнено /3 skipped, web31+scripts35 и lint/typecheck PASS.
Первый dataset3/3 PASS: corridor98,643 с, all-17 340,751 с; результат равен53 кроме версии.
В review добавлена защита пустых секций от недосчёта концов. После неё final fast642 выполнено
/3 skipped +final datasets3/3 PASS =**645 выполнено /3 scale skipped**. All-17 340,630 с,
corridor99,089 с, экспорт и полная включённая глубина PASS. Весь окончательный `run.result`
совпадает с53 кроме версии (`verify-primary54-result.mjs`, `primary54-final-result-equivalence.log`).
Preferred1898,684 м /13 камер /18 поворотов; cheapest1961,037 м /11 камер /24 поворота.
Форма не улучшилась, ускорения54 нет. Фактические картинки — `routing-comparison-54-preferred`
и `54-cheapest` в `.tooling`, вход — `primary54-final-official.json`, не первоначальный54.
Следующий резерв — перестройка ветвлений/камер; начать с причин отклонения, не с увеличения
всех бюджетов. У preferred ровно4 допустимые по длине/степени пары в80 м, лимит4 их не скрывает.
Нельзя удалять маркеры камер, оставляя скрытое ветвление. Live49 readinessUP, новый runtime
не развёрнут; Compose/scale открыты. Git/VPS не обновлялись.

## Предыдущий проверенный checkpoint: source53, 24.09.2026

Сначала [PRIMARY_ROUTING_53.md](PRIMARY_ROUTING_53.md). Упрощение теперь проверяет углы
по обоим концам shortcut; snap проверяет все три затронутых угла. Красные регрессии подтверждены,
включая реальную геометрию препятствий. Точные дубли хвостов при merge больше не проверяются
повторно: 38→27 проверок на fixture при неизменных восьми ответах и полном совпадении24 cases.
Java fast:622total /619 выполнено /3 scale skipped PASS (`primary53-fast.log`);
dataset3/3 PASS (`primary53-datasets.log`): corridor98,95 с, основной all-17 340,609 с.
Всего622 выполнено /3 skipped, включённая глубина, sizing/экономика и exporter validation PASS.
Web31 +scripts35 (`primary53-web-test.log`), lint/typecheck (`primary53-web-lint.log`,
`primary53-web-typecheck.log`) PASS. Все имена логов в этом блоке относительно `.tooling/`.

`node .tooling/verify-primary53-equivalence.mjs` → `primary53-result-equivalence.log` PASS:
весь `run.result` равен52 кроме верхнего `algorithm_version`, независимо от порядка свойств;
массивы сравниваются с порядком. Обёртка дополнительно отличается тремя timestamps и версией
run — это явно перечислено, не скрыто. Preferred прежний:1898,684 м /13 камер /18 поворотов,
17/17,277670360,94 ₽; cheapest1961,037 м /11 камер /24 поворота,275517854,67 ₽.
358,585→340,609 с — только два одиночных замера, не статистическое доказательство ускорения.
Актуальные просмотренные картинки: `.tooling/routing-comparison-53-preferred/side-by-side.png`
и `.tooling/routing-comparison-53-cheapest/side-by-side.png`; render logs `primary53-preferred-render.log`
и `primary53-cheapest-render.log`. Не показывать исторические52 как свежий53.

Следующий отдельный шаг54 — **retained-end interior simplification**, в53 ещё не реализован.
Воспроизведение уже есть: `CompliantDoglegFinishProbe53.java` и
`primary53-compliant-dogleg-finish-final.log`. Настоящий finish оставляет141 м /4 допустимых
поворота; альтернатива81 м /0 поворотов с теми же концами/лучами/нормальным suffix проходит
отдельные geometry, mandatory-egress, engineering, sizing и включённый depth без послаблений.
`regularizeFinishedEngineeringEdges` выбирает лишь `nonCompliantEdgeIds`, поэтому compliant-
ребро вообще не проверяется. Не ограничиваться расширением списка: regularize целого ребра
возвращает null из-за ввода в ОКС, а сокращение наружной части с сохранением конечного подхода
успешно. На54 сначала regression, затем ограниченные кандидаты и повторный допуск всей сети.
Отдельно остаётся ограничение seeds refinement только corridor-представителями.
Цель по форме, камерам и скорости не достигнута. Live49, Compose/scale для53 не проверены;
не выдавать source checkpoint за обновление live/Git/VPS или закрытие R-этапа.

## Активный шаг: source52, 24.09.2026

Сначала [PRIMARY_ROUTING_52.md](PRIMARY_ROUTING_52.md). Ранний контроль угла дополнен
двусторонней проверкой связности до направленного поиска. Парный fresh all-17 до/после
performance-изменения: **417,499 → 313,977 с**; весь `run.result` совпадает, не только метрики.
Отдельно исправлена потеря уже допустимого ввода при merge камер: ограниченное сохранение
префикса, до38 хвостов /8 путей. Java fast613total /610 выполнено /3 skipped PASS;
web31 +scripts35 и lint/typecheck PASS. Полные интегрированные corridor1 +dataset2 PASS:
**613 выполнено суммарно /3 scale skipped**. Итоговый all-17 с helper358,585 с (5:59),
не313,977 с: дополнительные кандидаты расходуют часть выигрыша; итог на14,1% быстрее angle-only.
Preferred теперь1898,684 м /13 камер /18 поворотов /277670360,94 ₽,17/17,экспорт PASS.
На камеру и3,123 млн ₽ меньше51, но поворотов18 вместо15 и нерегулярных пар6 вместо4.
Cheapest1961,037 м /11 камер /24 поворота /275517854,67 ₽. Картинки из финального run:
`.tooling/routing-comparison-52-preferred` и `52-cheapest`, не `52-component-cheapest`.
Не выдавать эти результаты за live API; live пока49.

Следующий подтверждённый дефект: `normalize` способен сделать угол135° из допустимой полилинии
при shortcut; воспроизводящий синтетический аудит — `.tooling/primary52-normalize-audit.log`.
Нужен отдельный bugfix с regression для соседних углов при normalize/snap, без ослабления
финального валидатора. Далее включить уже законченный shortest в seeds refinement: сейчас
передаются лишь corridor-представители. Не расширять перебор вслепую и не выбирать худший score
ради количества камер. У preferred ещё2 дополнительные камеры и3 поворота относительно эталона.

## Активный шаг: source51, 23.09.2026

Сначала прочитать [PRIMARY_ROUTING_51.md](PRIMARY_ROUTING_51.md): существующий ДУ учитывается
в новой камере до выбора варианта и при экспорте; обязательный предел поворота ≤90° проверяется
для всех ролей. Исправлен выявленный регрессией подход к камере, покрытие теста снова 2/2.
Итог: Java fast586 + corridor1 + dataset2 = **589 выполнено / 3 scale skipped**,
web31 + scripts35, lint/typecheck PASS. Свежий all-17 выбирает shortest: 17/17, 1899,927 м,
14 узловых камер, 15 поворотов, существующая ТК106 / два новых луча. Среднее отклонение от
фасадных осей 5,592°, но ещё четыре нерегулярные пары узлов и три лишние камеры против эталона.
Время407,177 с (с фрагментом JFR), ускорение не объявлять. Фактические картинки —
`.tooling/routing-comparison-51-preferred`, не balanced из `51-selected` и не cheapest.
Экспорт дополнительно проверяет именно секции; после исправления миллиметровой петли у конца
пути latest-adapter recheck свежего артефакта: 3 роли / 920 features PASS. Подробные логи в51.
Дальше: сократить лишние камеры без диагонального расползания; ранний запрет недопустимых
поворотов в направленном поиске до дорогой видимости, с проверкой полноты и новым all-17.
Live остаётся49. Новые проверки не заменяют Compose/scale и не закрывают цель по геометрии/скорости.
Нижеследующие открытые пункты ДУ/90° относятся к историческому checkpoint50.

## Сверка СП и новое исправление стоимости — 23.09.2026

Прочитать [ROUTING_STANDARDS_APPLICABILITY.md](ROUTING_STANDARDS_APPLICABILITY.md): в нём
зафиксированы актуальный DOCX и границы применения СП 124/315/41-105. Камеры на каждом
разветвлении обязательны для конкурса, даже если СП 315 описывает иной режим бесканальной
прокладки. Ограничения 90–135° / 2 м из экспертного профиля не выдавать за требования этих СП.
Обнаружена и воспроизведена лишняя плата 5 млн ₽ за новую камеру врезки: по §3.2 цена камеры
уже включает присоединение. Исправлено и проверено реальным planner→export regression:
общий focused gate 52/52, расширенный Java gate 521 выполнен / 3 scale skipped, без ошибок;
web 31 + scripts 35, lint/typecheck PASS. Новый полный основной pipeline: 2/2 PASS,
итого 523 выполненных Java-теста / 3 skipped. All-17 — 433,648 с; выбранные геометрия/метрики
не изменились. Два поворота >90° cheapest сохраняются и в новом результате.
Прежние метрики ниже не подтверждают новую ревизию. Отдельно проверить участие существующего
ДУ в цене новой камеры. Дедупликация одинаковых сетей внутри refinement также проверена,
включая повторный вход ранее не исследованного состояния; лучший коридорный кандидат не
изменился. Это ещё не доказывает ускорение полного расчёта. Live пока не менять.
Дополнительно: в прежнем cheapest обнаружены два изменения направления >90° (91,2055° и
91,6649°). Ограничение §2.1 должно проверяться для всех ролей отдельно от экспертных 90–135°.
Текущий `valid=true` не является доказательством этого пункта; допуск к экспорту также проверить.

## Текущий routing checkpoint — 2026-09-23

**Активная работа после checkpoint:** [PRIMARY_ROUTING_50.md](PRIMARY_ROUTING_50.md), динамические
ортогональные коридоры по застройке. Цель «как у Евгения» ещё не закрыта. Source развивает `50`,
проверенный запущенный API пока `49`; не путать сырые диагностические кандидаты с готовым run.
Актуальный кандидат `corridor-refined-16` прошёл настоящий finish/sizing/depth: 1986,104 м,
11 узловых камер, 17/17, стоимость +2,90% к balanced 49. Объединение камер уже подключено
к основному pipeline; отдельный полный прогон 2/2 PASS подтвердил исполнение refinement,
но итоговый selector сохранил прежние роли. Время all-17 — 440,828 с: ускорения нет.
Остаются лишние обходы, 24 поворота и две нерегулярные пары углов узлов при диагностическом
допуске 5° (у Евгения 15 поворотов и ноль таких пар). Новый Java gate: 492 выполнены, три
scale tests skipped; логи `.tooling/primary50-refinement-unit.log` и
`.tooling/primary50-refined-official.log`. Актуальные картинки кандидата:
`.tooling/routing-comparison-50-refined`; не выдавать кандидата за новый live-run.
Картинки фактически выбранного balanced — `.tooling/routing-comparison-50-refined-selected`.
Далее: подходы в сохраняемых узлах, лишние изгибы/стоимость и сокращение бесполезных повторных
завершений. Политику selector ради продвижения картинки не ослаблять.
Предыдущий полный Java pipeline 50 до объединения проверен: 2/2 dataset tests PASS, 444 выполненных Java-теста суммарно,
но выбранные результаты полностью совпали с 49 (кроме версии), а время стало 377,970 с против
прежнего замера 272,911 с. Следовательно, нельзя объявлять новую стратегию готовой или выкладывать
её ради номера версии: нужны выигрыш по камерам/качеству и сокращение бесполезной финализации.

Начинать с [PRIMARY_ROUTING_49.md](PRIMARY_ROUTING_49.md): единый основной `global-tree-49`,
ограниченная подготовка препятствий внутри расчёта, финальный отбор готовой геометрии и
воспроизводимые картинки рядом с разметкой специалиста. Изменение отбора описано в
[PRIMARY_ROUTING_48.md](PRIMARY_ROUTING_48.md).
Предыдущие изменения и измерения сохранены в [PRIMARY_ROUTING_47.md](PRIMARY_ROUTING_47.md);
отдельный экспериментальный запуск удалён. Исторические инструкции про два профиля ниже
не описывают текущий интерфейс. Старые результаты сохранены.

- Проверять новый расчёт, а не только открытие последнего сохранённого результата.
- Считать камеры ветвления, новые камеры врезки, места подключения и новые лучи отдельно.
- До обновления worker опустошить очередь старой версии; не подменять происхождение старого run.
- Запускать отдельную версионную копию JAR: не перезаписывать artifact под работающей JVM.
- Не объявлять оптимум: сравнивать полноту, стоимость, длину, камеры, углы и время одновременно.
- Локальные проверки не заменяют обязательные Docker/Compose и scale gates.
- Этот checkpoint не означает публикацию в Git или обновление VPS; это отдельные действия
  по команде владельца.

Правила нового кода, результаты аудита и поэтапный backlog качества зафиксированы в
[REFACTORING.md](REFACTORING.md) (2026-09-21). Разработка продолжается по активному roadmap;
масштабный рефакторинг не начат. Ошибки корректности и восстановления состояния не откладывать
вместе с косметикой. Перед структурным рефакторингом актуализировать RF-00 и его проверки.

Отложенная реконструкция и условия её повторного включения зафиксированы в
`docs/implementation/RECONSTRUCTION_DEFERRED.md`.

**Prepared:** 2026-09-16
**Repository:** `git@github.com:msmrc/heatroute-lct-2026.git`
**Branch:** `master`
**Public demo:** `https://130-49-150-217.sslip.io/`

> Текущий код зафиксирован в Git и проверен локально/в CI. Публичный VPS намеренно остаётся на
> более раннем демонстрационном checkpoint: не обновлять его без отдельной команды владельца.

## What changed today

- Java is now the only backend and lives at `apps/api`.
- Default/local/offline/VPS Compose all point to the Java image.
- The old Python application, Alembic, Celery/Redis services, Python tests and lockfiles are gone.
- The web app calls the Java official import/job contract and no longer calls legacy project/run
  endpoints.
- CI has Java verify/image, web quality/build and clean Ubuntu 22 / Compose 1.29.2 integration
  gates, including 50 concurrent imports, a real all-OKS calculation and restart recovery.
- OpenAPI comes from springdoc and is committed at `packages/api-client/openapi.json`; appendix input,
  official contest dataset and output JSON Schemas are published by the Java API.
- Commit `e47cd72` is deployed on the VPS. Production now runs only PostGIS, Java API, web and
  gateway; public HTTPS, Java readiness, official import, topology and immutable calculation run
  were verified.
- R8 vertical profiling доведён на официальном наборе: все 50 участков трёх вариантов имеют
  завершённый профиль, без `NO_VERTICAL_PASSAGE` и `VERTICAL_TRANSITIONS_OVERLAP`. Переходы рядом
  с камерой могут начинаться/заканчиваться на допустимой глубине, соседние пересечения на одной
  отметке объединяются в непрерывную полку, а выход вдоль выбранной сети не считается ложным
  пересечением.
- Последняя локальная проверка: Java 11 `mvn verify` — 111 тестов, 0 failures/errors, 3 явно
  отключённых scale-probe; web — 16 Vitest + 4 replay API tests, lint/typecheck/build/audit зелёные.

## Start in five minutes

```powershell
cd E:\job\_lct2026\heatroute_codex
git status --short
git pull --ff-only origin master
pnpm install --frozen-lockfile

# Терминал 1 — воспроизводимый локальный API на заранее рассчитанном официальном результате
node scripts/local-demo-server.mjs tmp/local-demo-bundle.json

# Терминал 2 — web; 5174 выбран, чтобы не конфликтовать с другим проектом на 5173
$env:VITE_DEV_API_PROXY = 'http://127.0.0.1:8000'
pnpm --filter @heatroute/web dev -- --host 127.0.0.1 --port 5174 --strictPort
```

Открой `http://localhost:5174/`. Нажми «Открыть демо» или загрузи
`datasets/official/lct-2026.geojson`: локальный replay принимает только точные официальные байты
по размеру и SHA-256, запускает тот же пользовательский happy path и не подменяет чужой файл
готовым результатом.

Если нужно заново рассчитать bundle из Java, используй Java 11 и Maven с кэшами на `E:`:

```powershell
$env:JAVA_HOME = 'E:\job\.tooling\apps\temurin-11\jdk-11.0.32.1+1'
& 'E:\job\.tooling\apps\apache-maven-3.9.16\bin\mvn.cmd' `
  '-Dmaven.repo.local=E:\job\.tooling\m2\repository' `
  '--batch-mode' '--no-transfer-progress' `
  '-Dheatroute.demo.output=E:\job\_lct2026\heatroute_codex\tmp\local-demo-bundle.json' `
  -f apps/api/pom.xml verify
```

## Current reality

Before using the older handoff below, read `ORGANIZER_VIDEO_CLARIFICATIONS.md`. The organizer Q&A
changed the active supplied-dataset scope: reconstruction is not mandatory and depth is
second-stage. The clarified 2D/economics rules are implemented in the current working tree but have
not yet passed the final Java/web/Compose verification. Older R4–R8 evidence predates these changes.

The mandatory 2D Java pipeline is implemented end to end on contract-complete fixtures. The public
UI exposes only what the backend can prove. Do not restore legacy screens or fabricate missing
organizer fields.

The current active engineering task is still routing performance on the supplied 17-point file.
Visibility checks were deduplicated, corridor selection was narrowed geometrically and cooperative
cancellation now terminates a CPU-bound run in under one second in the local Compose check. A
bounded 55-second probe still did not complete, so do not mark this gate closed; profile route-call
count and visibility-node count before changing the official routing rules further. The UI no
longer stays forever on the processing screen when its persisted run/import lookup fails.
Candidate evaluation now also uses admissible straight-line lower bounds to avoid obstacle searches
that cannot improve the current direct assignment or shared-pair plan. This is an optimization only:
the accepted route still passes the unchanged obstacle and final geometry validators. Its final
runtime has not yet been measured.
The next probe should read `Routing visibility profile` and `Routing phase profile` from API logs;
they report bounded search/node/pair counters without logging organizer geometry or properties.
That probe is now complete: the corrected circumscribed navigation hull reduced the supplied-file
runtime from 220.4 to 118.9 seconds and candidate pairs from 40.22M to 17.29M. The two correctness
findings from that run are now resolved. Run `cc9b8cf6-fef0-43a5-a01a-382a7093cfca` completed in
137.1 seconds; independent/shared/diverse are all valid and ranked, connect 14/16/9 demands, and
shared is rank 1. Its supplied-profile export returns HTTP 200 with a 487-feature GeoJSON covering
all three variants. Missing baseline tie-in diameter is allowed only in the supplied profile; the
  extended strict profile still requires it. After that checkpoint, `cost-tree-3` was implemented
  locally: third and subsequent demands may attach to an existing shared chamber or split an
  existing route edge at a new chamber; the choice is made by full-tree cost after bottom-up
  flow/diameter sizing. This newer stage has not yet passed the Java 11, supplied-file or Compose
  verification gates, so the older run above is not evidence for `cost-tree-3`.
  The subsequent `cost-tree-4` local-improvement pass also revisits independent root rays and
  replaces them with branches of the growing tree whenever full construction cost decreases (or
  cost is equal and the number of tie-in rays falls). It is likewise unverified.
  `cost-tree-5` extends the same operation to every terminal demand and contracts orphan/degree-two
  generated chambers before rerouting it against the remaining complete tree. Its lexicographic
  objective is full cost, tie-in rays, length and generated chamber count. Runtime is bounded to
  two accepted relocations and a nearest-chamber/edge shortlist per demand. This stage is unverified.

The supplied dataset is the confirmed judging format even though it is not shaped like the
published seven-type appendix contract. Read `SUPPLIED_DATASET_AUDIT.md` before changing validation
or routing. Use its 17 connection points as demand objects under the `official_contest_dataset`
profile; never fabricate missing existing flows or upstream links.

Already usable:

- streaming seven-type GeoJSON inspection and PostGIS persistence;
- WGS84 plus EPSG:32637 storage;
- existing-network topology diagnostics;
- deterministic tie-in candidates and 10 m chamber feasibility rule;
- durable PostgreSQL topology jobs with progress/cancel/recovery;
- pure Java official restriction catalog, crossing geometry and DU sizing primitives.
- immutable R4 runs with deterministic independent/shared/diverse variants, obstacle-aware
  polylines, partial no-route and a separate tree/chamber/crossing validator;
- integrated R6 construction/final validation for dynamic OKS buffers, hard forbidden zones and
  reproducible base/special crossings;
- bottom-up `flow_tph` and automatic DU selection across all flow/continuous-length catalog rows;
- upstream propagation, partial/common-section reconstruction and used-chamber reconstruction for
  contract-complete existing-network input;
- current reproducible evidence on the organizer file: the preferred independent variant connects
  all 17 demands with zero structural validator issues; shared connects 16 and diverse 14 while
  preserving explicit no-route diagnostics for the remaining objects. Across all three variants,
  all 50 route edges have complete validated depth profiles and zero depth issues.
- interactive result viewer with MapLibre GL/CARTO vector basemap, PostGIS source context, layer
  toggles, variant comparison, map-object inspection, no-route diagnostics, a retained metric
  schematic and a latest-completed-run demo endpoint. Complete variants render through the strict
  R7 seven-type adapter; the supplied incomplete file intentionally uses the internal preview.
- map-first result UX with floating inspector/results islands over one uninterrupted map, the real
  imported filename in the toolbar and a collapsible navigation rail. Technical stack, version,
  team and Swagger live on the separate `/system` page instead of the work screen.

Implemented locally; final verification required before an unconditional official P0 claim:

- allow complete supplied-profile cost/rank/export without reconstruction;
- enforce normal egress from the containing OKS for all 17 demand points;
- optimize connect versus official unconnected penalty;
- account for non-standard bend ×1.5, max overlapping `K_special` and one tie-in per new ray;
- separate optional depth from mandatory 2D.

Implemented scale slice; final verification required:

- calculation materializes only core network/connection objects and fetches restrictions and
  existing OKS through PostGIS metric windows. Verify PostGIS equivalence and memory behavior for
  an unusually dense single window before closing the scale gate.

External decisions still required:

- organizer approval that the passing full 2× topology gate represents the hidden maximum;
- organizer clarification of reduced output types and disputed depth/length rules;
- production-like Ubuntu 22 host rehearsal only if clean ephemeral CI is not accepted.

## Developer: next vertical slice

Preserve the immutable run/job contract. The local supplied-file Q&A-P0 routing/economics/export
cycle is verified; next verify it together with the spatial-window calculation in the full Java 11
gate. R7 component costs,
length, score/rank, strict seven-type serialization, independent whitelist/type/reference
validation, feature-by-feature preflight and incremental Jackson download are already integrated.
R7 is closed against the normative appendix tables and formulas. Section 10.8 explicitly calls its
numbers illustrative and differs by 19/33 RUB; keep the golden expectations derived from tables
4.1, 5.1, 8 and 9. Complete variants are fetched per `variant_id` and rendered from the strict
official output model. Dense constraint lookup uses adaptive JTS STRtree and is locked by a
1,001-constraint/20,000-query fixture. The project-owned full 2× topology gate passes on Ubuntu 22
/ Java 11 with 34/34 demands; obtain organizer approval before calling it the official maximum.
R8 is preserved evidence for the published rules, but must become a separate second-stage mode.
Do not change disputed 0.5 m / 0.7 m / slope semantics before a written organizer answer.
Repeated imports are idempotent by `(contract_version, raw_sha256)` and concurrent duplicates are
resolved by PostgreSQL `ON CONFLICT`; preserve this invariant in all future import changes.
Job execution is bounded by `HEATROUTE_JOB_CONCURRENCY` (default 2, hard maximum 16) and active
leases are renewed every minute. Use `docs/operations/R9_ACCEPTANCE.md` for scale evidence; do not
call the probes themselves a pass until their generated measurements are archived.
Manual run `35112046184` proves exact 3 GiB input and ≥500 MiB valid output on Ubuntu 22 / Java 11
under `-Xmx512m`. Final clean-stack run `35129162919` passes backend/web/integration, including the
50-user import race, the real calculation, schema/API contracts and restart recovery; topology run
`35120995991` passes 288 features, 34/34 demands and three variants in 2:14.65 with 406,608 KiB
peak RSS. Do not conflate this with a VPS deployment, which remains explicitly deferred.
Do not mix MVT or extra formats into the remaining external acceptance gate.

## Артём: с чего продолжать

1. Сначала проверь чистый `master`, запусти команды из раздела «Start in five minutes» и пройди
   основной сценарий `файл -> loader -> карта -> варианты -> предупреждения -> профиль`.
2. Не переписывай каркас R4–R8, но исправь перечисленные в
   `ORGANIZER_VIDEO_CLARIFICATIONS.md` Q&A-P0 правила. Реконструкция supplied dataset больше не
   должна считаться внешним блокером результата.
3. Для конкурсной сдачи собирай материалы по `docs/CONTEST_SUBMISSION.md`, а критерии сверяй с
   `docs/ACCEPTANCE.md`. Реконструкцию показывай только как расширенный strict-profile.
4. Если потребуется менять R8, сначала сохрани инварианты из
   `docs/implementation/R8_VERTICAL_EVIDENCE.md`: полка 4 м, шаг глубины 0.5 м, уклон не более
   0.10 м/м, независимая валидация, честный partial/no-route при невозможном проходе.
5. Любую новую контрольную точку: локальные тесты -> commit -> push -> дождаться всех GitHub Actions.
   На VPS не выкладывать, пока владелец явно не скажет это сделать.

Исторический checkpoint показывал 77 предупреждений и трактовал `railway` как алиас
`tram_tracks`. Обновлённые документы организатора отменили эту трактовку: `railway` теперь
запретная зона с отступом 1 м, а реконструкция существующих активов исключена из контракта.

## PM: tasks tomorrow

- include the published, CI-tested JSON Schemas from `docs/contracts` in the submission kit;
- confirm whether Ubuntu 22 is mandatory for judging even though the current demo VPS uses a
  newer Ubuntu release;
- supply or approve an official-like maximum-topology fixture and load-test environment;
- keep MVT and extra formats outside P0 until the external R9 decisions close;
- review every “complete” claim against `docs/ACCEPTANCE.md`, not old M-stage evidence.
- ask the organizer to resolve the remaining supplied-dataset questions: reduced output types,
  continuous-length branching and disputed depth rules.

## Known operational notes

## Latest product-flow decision (2026-09-16)

- Do not reintroduce the import/report page into the valid-file happy path. Upload must proceed as
  `file -> loader/progress -> completed map` and start the official run automatically.
- Input warnings remain available from the clickable `Проверка структуры` metric in the result
  island. Keep the full API diagnostics; do not replace them with a fake aggregate.
- The VPS intentionally remains on an older demonstrated baseline. Current checkpoints are
  Git/local/CI-only; do not deploy them without a separate user command.

- Local Docker data and tool caches must remain on `E:`.
- Never commit `.env.vps`, keys or dumps. The sole approved organizer dataset is the byte-identical
  `datasets/official/lct-2026.geojson`; do not add copies or synthetic dataset files.
- VPS updates follow `docs/operations/VPS_DEPLOYMENT.md`; take a DB backup first.
- Database schema history is now Liquibase under `apps/api/src/main/resources/db/changelog`.
- On the current Windows workstation Docker Desktop is blocked after reboot by a stale internal
  socket. CI and VPS are healthy. Do not factory-reset Docker or move its `E:` data; repair the
  local daemon separately before using the local `up` command.
# Checkpoint 2026-09-21 — amended documents

The `global-tree-7` adaptation was rebased onto the main developer's documentation commits before
publication. It restores forbidden `railway`, removes the non-standard-bend surcharge, disables
reconstruction, switches export to the four-type contract and installs the corrected dataset.

# Routing performance result 2026-09-21

`OfficialDatasetRoutingTest#officialDatasetProducesValidatedObstacleAwareVariants` now succeeds in
57.417 s: 263 visibility searches, 1,902,450 evaluated pairs, 17/17 connected, 2,098.903 m and score
14.937905995. The previous CI regression exceeded 20 minutes and roughly 78.8 million pair checks.
Only this focused method was executed; the complete suite was not run. The next algorithmic target
is quality, not another wider brute-force search: reduce the current approximately 308.6 million
cost and 2.099 km length toward the observed 286.2 million / 1.83 km reference.

# Local follow-up after CI #87

The unpushed `global-tree-8` working tree changes three routing decisions: reuse the nearest
degree-compatible branch chamber, attach otherwise failed separate rays to the existing forest,
and select a nearby own-OKS boundary side in the direction of the candidate network. CI #87 did not
fail on the official performance method; it failed on stale backend/web/integration expectations
left from the old contract. Web labels and integration complete-export expectations are updated.

A focused Java 11 run exposed two regressions in the first draft: the validator used a different
own-OKS exit than the builder, and evaluating three chambers per graft increased the official run.
The working tree now resolves the validator exemption from the actual terminal approach and
considers only the nearest reusable chamber. After aligning stale amended-contract expectations,
the final Java 11 `mvn verify` passed 143 tests with 3 opt-in scale tests skipped; the official
dataset class took 152.707 s. Web Vitest passed all 18 tests. Established labels were restored to
`Раздельные трассы`, `Общая сеть` and `Альтернативные врезки`. The changes remain local and must not
be pushed or deployed until separately requested; runtime and route quality are still open work.
