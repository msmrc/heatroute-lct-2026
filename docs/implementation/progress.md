# Current implementation progress

## 2026-09-25 — свежий81: регрессия поворотов устранена, качество ещё не целиком принято

Full1234cases/1230PASS/1прежний compactFAIL/0errors/3scale skip; original462,736с/concave5,845с.
Все17/17/geometry/sizing/depth/economics/strict export PASS. Shortest/cheapest2068,786м/11новых
камер/22поворота/0bad angles/0close pairs; exact variants75. Balanced2194,257м/14камер/26поворотов,
exact79/80. Сравнение времени под параллельной нагрузкой не доказывает ускорение.
Отдельно проверены topology/flows/slots/endpoints/actual lengths. Картинка81/Евгений построена
и просмотрена, SHA+все source geometry сверены. +154,927м к эталону;22поворота против15.
**Advisory:2почти параллельных выхода камер177/560** — остаются отдельной задачей, хотя bend
compliance проходит. Усилен постоянный dataset test на все роли: fresh повтор78303 завершён
2/2PASS,494,770с/concave7,637с, strict exportPASS, exact variants первогоfull81. Reports сохранены.
Roads77exit1:2990,305с/16of17; roads79exit1:3529,887с/15of17/только2роли. Targets77/79 свободны.
Новый roads81session30394 использует frozen source81 target; source81-quality.iLac2n теперь свободен.
Runtime61/VPS прежние, исходники production81 не менялись. Все границы/handles/evidence в81/handoff.
Subagent+parent воспроизвели3малых trunk-контрпримера/52assertions: неверная сторона проверки
в grid, канонический порядок вместо rooted и требование complete-special от технических pieces.
[Следующий fix](CORRIDOR_TRUNK_ADMISSION.md) ещё не внесён. Снят30сJFR дорог81,1477main samples,
не end-to-end benchmark. Новый web36/scripts37/lint/typecheck PASS (`source81-quality-web.log`).

## 2026-09-25 — full80 завершён: свежая эквивалентность79 подтверждена

1231cases/1227PASS/1прежний compactFAIL/0errors/3scale skip,120классов. Original fixture613,702с,
concave4,838с; под параллельной нагрузкой, не доказательство ускорения или регрессии времени.
Node deep equality подтвердил exact `run.result.variants`80==79 без исключения полей.
17/17, geometry/sizing/depth/economics/strict export3ролейPASS; cheapest2073,965м/11новых камер
по-прежнему3bad angles, balanced/shortest2194,257/2194,034м/14камер/0engineering issues.
Это equivalence gate, **не приёмка качества**. `source80-full-reports/` сохранены; target80 свободен.
Full81session87519/roads79session69123/roads77session2599 подтверждены живыми; их targets frozen.
Runtime61/VPS/compact/G2/native/Compose/scale/R/общая цель не закрыты; итоги и продолжение в80/81.

## 2026-09-25 — correctness81: preservation в основном TOWARD

Full79:1226cases/1222PASS/1compactFAIL/3skip;595,410с, exact variants79==78, cheapest3bad angles.
Выяснено: основной pipeline использует TOWARD, тогда как79 изменил только PRESERVE для
переноса камер. Bounded TOWARD79 trace на saved75:две допустимые ветви меняются из-за avoidance,
при уже подходящем вводе; badAngles0→2. В81 такой случай сохраняет сеть после полного независимого
допуска, а другая ось ввода остаётся альтернативой.3tests/2RED80→GREEN81,scoped62PASS;
clean/fast1230cases/1227PASS/0fail/0error/3skip,web36/scripts37/lint/typecheckPASS. TOWARD81 replay
saved75 теперь exact/0bad angles; это не fresh plan. Full81session87519 запущен; full80session59635/
roads79session69123/roads77session2599 живы. Target79 остаётся занят после full79. Runtime61/VPS
не менялись,1%лимита, исходники и продолжение в [81](PRIMARY_ROUTING_81.md) сохраняются в Git.

## 2026-09-25 — performance80: query-local primitive ordinals

[Source80](PRIMARY_ROUTING_80.md): заменена boxed comparator sort при том же STRtree и исходном
порядке. Массив только от3hits, без cache/общего scratch; geometry/expanded envelopes/порог128
не менялись.5постоянныхtests,1500mixed+400concurrent differential queries и growth boundaries;
scoped81PASS. Clean/fast1227cases/1224PASS/0fail/0error/3skip; web36/scripts37/lint/typecheckPASS.
Full80session59635 запущен, full79session51226/roads79session69123/roads77session2599 продолжаются.
Свежие exact variants и общее ускорение не доказаны; quality/compact/G2/scale/Compose/R/цель открыты.
Runtime61/VPS прежние, осталось около 2% лимита; checkpoint сохраняется в Git.

## 2026-09-25 — контрольный full78 завершён, проверка79 продолжается

1220cases/1216PASS/1compactFAIL/0errors/3skip;fixture599,957с.17/17, geometry/sizing/depth/
economics/strict export3ролейPASS. Cheapest2073,965м/11камер/3bad angles, как77;
balanced/shortest2194,257/2194,034м/14камер/0expert. Улучшение качества/скорости не подтверждено.
Full78session46700 завершён, target78 свободен. Full79session51226/roads77session2599/
roads79session69123 продолжаются на frozen targets. Runtime61/VPS/compact/R/цель не закрыты.
Отдельный scalar-ordinal prototype:1614exact query comparisons/0mismatch, устранено лишнее
выделение массива на1–2hits; плотные запросы быстрее, mostly-empty чуть медленнее. Только
synthetic microprobe, не production и не end-to-end gain; подробности/evidence в79.

## 2026-09-25 — correctness79: whole-network preservation

[Source79](PRIMARY_ROUTING_79.md): `PRESERVE_VALID` сохраняет целиком независимо проверенную
сеть при конечных ДУ, не смешивая preservation с поисковым буфером. Новые replacements остаются
под прежними guards. 6новыхtests;3REDна78→PASS79; clean/fast1222cases/1219PASS/0fail/0error/3skip,
scoped59PASS, web36/scripts37/lint/typecheckPASS, независимое bounded static review без замечаний.
Повтор доводки saved75 на79:6/6exactPASS,shortest/cheapest2068,786м/0bad angles; **не fresh79**.
Full79session51226 запущен; full78session46700/roads77session2599 ещё работают, targets frozen.
Дополнительно fresh roads79session69123 запущен на compiled target79; этот target остаётся
замороженным и после full79 до завершения roads79. Вход проверен по SHA, результат ещё не получен.
Нужен fresh all-role quality (включая cheapest), roads79 и native smoke. Runtime61/VPS не менялись.
Релиз/compact/G2/Compose/scale/R/цель не закрыты;4%недельного лимита, checkpoint в Git.

## 2026-09-25 — correctness78: incoming terminal/normal/sections

[Source78](PRIMARY_ROUTING_78.md): явное физическое направление без смены порядка поиска;
normal selection, depth/final-ДУ repair, corridor terminal и sections согласованы с stored
root→demand. Demand suffix сохраняет локальный own-OKS ввод. Reviewer finding транзитивного
straight continuation через связанные компоненты исправлен без расширения допусков.
50новыхtests; конечный clean/fast1216cases/1213PASS/0fail/0error/3scale skip;web36/scripts37/
lint/typecheckPASS. Snapshot exact. Full78session46700 запущен; roads77session2599 продолжается.
Quality regression77 не исправлен: trace выявил, что синтетический accepted-route buffer
отвергает ранее final-valid ветви, после чего успешный repair добавляет плохие углы. Следом
нужен отдельный PRESERVE_VALID regression/fix и fresh all-role quality. Ускорение query-sort
пока только micro-гипотеза. Runtime61/VPS/compact/G2/native/Compose/scale/R/цель не закрыты.

## 2026-09-25 — source77: shared-junction repair

**Итог full77:**1170cases/1166PASS/1compactFAIL/0errors/3skip;fixture526,533с/17of17/
depth/economics/strict exportPASS. Но quality regression: cheapest2073,965м/11камер/3плохих
угла вместо75 2068,786м/11/0;shortest2192,300м/14камер. Не считать fixture-accepted77
приёмкой качества всех ролей: cheapest engineering не входил в assertions. Runtime61 сохранён.
Roads75session70338 завершён:2838,622с/16of17,exact variants72,accepted отсутствует.
Новый roads77 **session2599** читает frozen snapshot77target; full77 уже завершён.
В checkout отдельно разрабатывается terminal-direction78; первые99focusedPASS,
полного78 gate нет. Гипотеза ускорения query-sort пока только micro/audit, не production.

[Source77](PRIMARY_ROUTING_77.md): ранее принятая ветвь больше не блокирует собственный общий
узел при final-ДУ repair. Точная node identity, только локальный прямой контакт; overlap,
повторный вход и пересечения вдали запрещены. UTM overlay исправлен без расширения допусков;
whole-line guards не позволяют сделать общий узел внутренней точкой при склейке ввода.
29 новых постоянных tests; 111 focused PASS, independent65/65 (48 rotated/translated).
Конечный clean/fast Maven1166cases/1163PASS/0fail/0error/3scale skipped; web36/scripts37/
lint/typecheckPASS. Full/fresh77 и roads75 завершены с ограничениями выше.
Ограниченный final-ДУ replay75 не заменяет fresh/sizing/depth/economics и не доказывает ускорения.
Следующий отдельный дефект — направление road entry после обращения terminal route.
Runtime61/VPS прежние; compact/G2/native/Compose/scale/R и полная цель не закрыты.

## 2026-09-25 — full75 завершён; рискованное A* отсечение отклонено

[Checkpoint76](PRIMARY_ROUTING_76.md): обе performance-гипотезы отклонены, main source неизменён75.
Два reviewer-контрпримера показали изменение маршрута из-за цепочек epsilon; верхняя граница
по цели полностью удалена, оба RED закреплены среди12новых regression-тестов. Точная численная
подготовка прошла6962differential/80192bitwise checks, но ускорения вmicro не дала и тоже удалена.
Web36/scripts37/lint/typecheckPASS; конечный clean/fast Maven1137cases/1134PASS/0fail/0error/3skip.
Main source побайтно совпал с full75;12новых tests включены,112классов. Предварительные
1135cases отклонённого patch не являются его заменой. Найдены реальные shared-junction
self-blocking и terminal-direction дефекты; следующее исправление отдельно от performance.
Full75:1129cases/1125PASS/1compactFAIL/0errors/3skip;fresh526,467с/17of17/strict export3ролейPASS,
exact variants74. Roads72 завершился failure16/17 во всех ролях, diagnostic сохранён,
принятого результата нет. Roads75session70338 ещё работает на замороженном snapshot75target.
Основной target72 освободился. Runtime61/VPS/compact/G2/native/Compose/scale/R остаются открытыми.

## 2026-09-25 — full74 завершён; final-ДУ retention исправлен в75

Full конечных74session37091:1114cases/1110PASS/1прежний compact failure/0errors/3scale skip;
fresh532,720с,17/17,strict export3ролей PASS; все variants точно как73. Отчёты/accepted сохранены.
Ранний pre-budget74 тоже завершён (1113cases/1109PASS/1failure/3skip), хранится отдельно.

[Source75](PRIMARY_ROUTING_75.md): whole-road проверка готового ввода вместо двух обрезанных
проверок. Реальный2RED/4controls→GREEN,15постоянных regression tests, independent review24PASS;
сохранение исходного объекта и локальность льгот подтверждены. Финальный быстрый Maven
1125cases/1122PASS/0fail/0error/3scale skip;web36/scripts37/lint/typecheckPASS.
Clean/full75session70380 и fresh roads75session70338 работают на одном отдельном snapshot
`.tooling/source75-build.9a8BDz/apps/api`; его target заморожен для обоих. Roads72session19058
по-прежнему использует основнойtarget. Не смешивать diagnostic до assertions с accepted output.
Все агенты75 завершены. Fetch подтвердил origin/master28c7059, новых upstream commits нет.
Fresh75/compact/G2/native/Compose/scale/runtime/R и полная цель ещё не закрыты.

## 2026-09-25 — full73 завершён; performance74 проходит полный gate

Full73session8395:1080cases/1076PASS/1прежний compact failure/0errors/3scale skip.
Fresh529,767с (весь класс533,933с),17/17,strict export3ролей PASS, все значения variants совпали с72.
Accepted73 и snapshot reports сохранены; отдельный roads73 не запускался.

[Source74](PRIMARY_ROUTING_74.md): индекс road/tram-интервалов только в поиске, bounded
подготовка текущего расчёта без ready-route replay. Два настоящих numerical RED исправлены
fallback на исходный JTS, отступы/углы/3м защиты не ослаблены; full-validator/export независимы.
123focusedPASS; чистый быстрый Maven1110cases/1107PASS/0fail/0error/3scale skip,
web36/scripts37/lint/typecheckPASS. 7520запросов реальных дорог совпали; microbenchmark не
равен скорости полного расчёта. Последний RED исправил резерв packed-double locator3N→4N.
Clean/full конечных74session37091 в отдельном snapshot уже работает; предыдущий full74
session16835 — pre-budget версия. Roads72session19058 продолжает использовать основнойtarget.
Не перезаписывать занятые target. Agent Chandrasekhar отдельно воспроизводит final-ДУ retention,
production пока не меняет.
Fresh74/roads74/compact/native/Compose/scale и R/цель ещё не закрыты; runtime61/VPS сохранены.

## 2026-09-25 — full72 завершён; техническая граница ввода исправлена в73

Full72:1053cases/1049PASS/1compact failure/0errors/3scale skip. Fresh521,534с,17/17,
geometry/ДУ/depth/economics/strict export3ролей PASS; значения всех variants совпали с71.
Snapshot/accepted/PNG72 сохранены. Roads72session19058 ещё работает, основнойtarget заморожен.
[Source73](PRIMARY_ROUTING_73.md): genuine RED→GREEN на поиске, сборке и коридорных подходах;
157focusedPASS, web36/scripts37/lint/typecheckPASS. Fast Maven73 завершён в отдельном snapshot:
1076cases/1073PASS/0fail/0error/3scale skip; это не full/fresh. Final-DU retention, логические crossing chains, compact и runtime
остаются открытыми. Runtime61/VPS не менялись, G2/R/цель не закрываются.
`c4749e1` отправлен, SHA проверен; clean/full73session8395 без исключений запущен в отдельном
snapshot, `source73-full.log`. Оба target (основной72 и snapshot73) пока заняты.

## 2026-09-25 — source72 отправлен; clean/full запущен

`a410259` pushed,remote SHA проверен. Clean/full без исключений запущенsession53894,
`source72-full.log`;target не перезаписывать до завершения. Fast1049cases/1046PASS/3skip
не заменяетfull;accepted72 ещё не получен. Read-only audit старогоroads69 обнаружил8невалидных
рёбер уshortest/cheapest (7непрямыхspecial+1отступ), поэтому прежнийexport69не доказываетG2.
[Текущий evidence и следующие gates](PRIMARY_ROUTING_72.md). Runtime61 не обновлялся.

## 2026-09-25 — source72: завершён быстрый gate исправленного road/tram patch

[PRIMARY_ROUTING_72.md](PRIMARY_ROUTING_72.md):1049cases/1046PASS/0fail/0error/3scale skip,
106классов; исключены3долгих original/corridor/compact класса, поэтому это не full gate.
Web36/scripts37/lint/typecheckPASS. Independent review выявил3ошибки в новом коде — bounds
rotatedbuffer,depth retry,phantom entry; исправлены с RED→GREEN. Export38new+42existingPASS,
включая oblique millimetre sections; отступы не ослаблены. Код готов к clean/full проверке.
Fetch подтвердил origin/master28c7059. Runtime61/VPS не менялись, G2/R/цель открыты.

## 2026-09-25 — fresh71 подтверждён, G2 road/tram fix72 в работе

Clean71:969cases/965PASS/1failure(compact-control)/0errors/3scale skip. Fresh original
fixture513,704с,17/17,shortest/cheapest2068,786м/11камер/22поворота/0expert/283006479,92₽;
balanced2192,523м/14камер/25поворотов. Depth и strict export3ролей PASS. Accepted bundle и
snapshot71 сохранены; target больше не занят71. [Evidence](PRIMARY_ROUTING_71.md).

Correctness72 (ещё не принятая версия): новые road/tram RED для search/final/export;
интегрируются DU-зависимые отступы, реальные границы polygon, локальные прямые special3м,
защитные порталы и повторная проверка экспортируемых секций.32новых geometry/preparation
focused PASS, ещё2navigation/6fallback PASS отдельным запуском. Полных gates72 пока нет.
Runtime61/Compose/scale/roads72/compact goal не закрыты; [G2](G2_SPECIAL_CLEARANCE.md) открыт.

## 2026-09-25 — source71: короткие вводы после выбора готовых ролей

[PRIMARY_ROUTING_71.md](PRIMARY_ROUTING_71.md): отдельный bounded local-pass после relocation,
исходные сети сохранены, каждый кандидат пересчитывается полностью. RED80→60 на реальном
planner, component16GREEN, общий focused125PASS. Production-helper replay69:shortest/cheapest
2068,786м/11камер/22поворота/0expert/283006479,92₽,balanced неизменён;17/17/depth/strict export
3ролей PASS,2,520с; web36/scripts37/lint/typecheckPASS. Это не fresh71;
код `0d4d462` отправлен. Compiled70 завершён:951cases/947PASS/1failure(compact-control)/3skip;
fresh500,035с/17of17/strict exportPASS,exact variants69. Примерно−24,3% в этом не полностью
изолированном замере. Snapshot70 сохранён; clean/full/fresh71 теперь выполняется отдельно.
Runtime61 сохранён, общий quality/performance/R-gate открыт.

G2: read-only audit и отдельный Java11 RED подтвердили пропуск бокового отступа road/tram
(4FAIL/2controlPASS): приДУ100 ось1,700м от полигона принимается вместо требуемых1,755м.
[План исправления](G2_SPECIAL_CLEARANCE.md) включает оба spatial отбора, разрешённые прямые
special-интервалы, порталы, final/export и PostGIS equivalence. Production fix ещё не включён;
full71 не содержит этот isolated specification RED. Не объявлять G2 закрытым по strict export.

## 2026-09-25 — source70: пространственная подготовка точного валидатора

[PRIMARY_ROUTING_70.md](PRIMARY_ROUTING_70.md): bounded source/bounds/heavy buffers устраняют
повторное buffer для дальних объектов; первое вычисление/отказы и осевые границы сохранены.
Focused101PASS, включая14новых component tests и RED→GREEN для порядка исключений eligibility.
Validation-only replay69:3роли×3повтора,exact issues0,655buffers/43183coord стабильно после
прогрева. Это не fresh70/benchmark. Web36/scripts37/lint/typecheckPASS; full/fresh70 ещё впереди.
Независимое static review не выявило подтверждённых дефектов. Roads69 завершён:
1054,672с/17of17/strict export3ролей PASS; shortest/cheapest2260,959м/11камер/33поворота,
все роли0expert. Его JFR выявил дорогой sorted spatial query в поиске видимости. Target больше
не занят69, runtime61/R-gates сохранены.

## 2026-09-25 — source69: расчётная подготовка геометрии для валидатора

[PRIMARY_ROUTING_69.md](PRIMARY_ROUTING_69.md): JFR67 локализовал повторное построение
JTS-буферов внутри validation. Четыре geometry-вызова planner используют сессию точного
валидатора; сама проверка маршрута повторяется, standalone/export остаётся независимым.
Integration RED поймал обход subclass-hook; сохранён публичный override-путь.
Isolated86/86PASS, включая11новых exact-equivalence/real-buffer/ownership тестов и
relocation68. Код `63b0072` отправлен, web36/scripts37/lint/typecheckPASS. Compiled67
завершён:924cases/920PASS/1failure(compact-control)/3skip. Fresh640,761с/17of17/strict export
всех3ролей PASS. Shortest/cheapest2090,416м/11новых камер/23поворота/0expert; balanced прежний.
Snapshot и новое PNG67/Evgeny сохранены, изображение просмотрено/показано. Clean69 завершён:
936cases/932PASS/1failure(compact-control)/0errors/3skip. Fresh660,385с/17of17/strict export
всех3ролей PASS; все variants точно совпадают с67. PNG69/Evgeny отрендерен/просмотрен.
Ускорения нет: профиль и bounded-probe выявили thrashing подготовки между ДУ при100k
координатах. Следующий spatial-подход требует сохранения исключений/реальных buffer bounds;
review/синтетический quality-контрпример записаны в69, не реализованы. Исправлен только
устаревший комментарий о зависимости forbidden отДУ, без изменения поведения.
Теперь отдельно выполняется fresh roads+kindergarten239features (`source69-roads.log`).
Runtime61 сохранён, native/Compose/scale/релиз и R-этапы не закрываются.

## 2026-09-25 — source68: сохранение исходных relocation-контролей

[PRIMARY_ROUTING_68.md](PRIMARY_ROUTING_68.md), код `ae452a7`. Review67 выявил потерю ещё
не выполненного улучшения исходной сети после промежуточного отбора нового победителя.
RED на реальном planner:75м вместо достижимых70м из80м. Теперь relocation получает три
исходные роли плюс один repair, без вытеснения. Isolated Java11 focused79/79PASS.
Полный compiled67 продолжает работу без изменения его `target`; включён30сJFR, замер не
изолированный. Full/fresh68 и roads+kindergarten ещё впереди, runtime61 сохранён.

## 2026-09-25 — source67: поздняя инженерная доводка и повторная подготовка контуров

[PRIMARY_ROUTING_67.md](PRIMARY_ROUTING_67.md), код `ff1a4c1`/`da44a56` отправлен.
Отдельный диагностический replay66 подтвердил2090,416м/11новых камер/23поворота/0expert,
17of17/strict exportPASS после существующего regularizeEngineeringDraft(false). Он был
пропущен для позднего cheapest. В67 добавлен этот этап после отбора, с сохранением исходных
ролей и запретом ухудшения длины/цены. RED→GREEN и отказ от более дорогого park-detour PASS.
Отдельно obstacle buffer/hull готовится один раз между расширениями одного поиска;
router80/80PASS, реальные6→1вычисления, точная геометрия сохранена. Repair focused77/77,
затем guards4/4PASS. Web36/scripts37/lint/typecheckPASS. Clean924/fresh67 выполняется;
итог пока не получен.
Replay66 не объявляется fresh67. Runtime61/VPS не менялись, общий quality gate открыт.

## 2026-09-25 — source66: индивидуальные anchors и UTM-точность

Код `8d58875` / `3c50437` отправлен, remote SHA проверен.
[PRIMARY_ROUTING_66.md](PRIMARY_ROUTING_66.md): исправлен доказанный локальный дефект
aggregate-DU anchors при индивидуальном вводе. Дополненная сетка строится отдельно от
контрольной, чтобы thinning осей не вытеснял старые варианты; ДУ/отступы ствола не снижены.
RED79,0м→GREEN≤63,05м на полном синтетическом дереве. Rotation/UTM regression также
выявил и устранил численную потерю минимального перехода без ослабления финальных правил.
Focused55/55PASS; web36/scripts37/lint/typecheckPASS. Clean full914:910PASS/1failure/0errors/
3scale skipped; compact-control остаётся красным. Fresh652,482с/17of17/strict export3ролей PASS.
Cheapest2092,274м/11новых камер+1существующий корень/24поворота/1неподходящий угол,
285145228,70₽,score14,260888404. Относительно65 новых камер−3, цена−4679300,33₽, но длина
+11,641м и расчёт медленнее. Balanced/shortest2192,523м/14/25/0expert не улучшился.
Сравнение66/Evgeny отрендерено и просмотрено; это fixture, не live job. Runtime61 сохранён;
quality/native/Compose/scale/R-этапы открыты. Следующие проверки — оставшийся угол179,426°
и отдельная подготовка контуров между расширениями одного поиска.

## 2026-09-25 — source65: двухповоротные переходы наклонных вводов

Код `aff1dcf` отправлен в origin и remote SHA проверен. [PRIMARY_ROUTING_65.md](PRIMARY_ROUTING_65.md)
содержит scope, RED→GREEN и результаты. Прежние прямоугольные контроли сохранены; наклонная
нормаль может соединяться с осью коридора диагональным средним звеном. Правила не ослаблялись.
Широкий Java906:903PASS/0failures/0errors/3skip (включая corridor dataset), затем3dataset:
2PASS/1failure/0errors. Вместе весь набор909:905PASS/1failure/0errors/3skip, не один clean-run.
Compact-control FAIL с прежними порогами. Fresh443,775с,17/17/ДУ/глубина/экономика/strict
export всех3вариантов PASS. Balanced/shortest остался2192,523м/14камер/25поворотов/0expert.
Cheapest2080,633м/13узловых+1новая корневая камера/37поворотов/4неподходящих угла;
289824529,03₽, score14,356985813. Цена ниже64, но длина иscore хуже — не no-loss улучшение.
Web36/scripts37/lint/typecheck PASS. Runtime61/readinessUP сохранён; quality/native/Compose/
scale gates открыты. Подтверждён синтетический дефект anchors по общемуДУ вместо учёта
индивидуального ввода: следующий RED/fix; код и точные координаты примера — в отчёте65.

## 2026-09-25 — source64: интеграция нормалей, полный quality gate ещё не пройден

Финальный код `0270971`, экспортный fix `2de2ceb` отправлены; remote проверен.
Clean full904:900PASS/1failure/0errors/3scale skipped. Единственный failure — исторический
compact-control≤13камер/<1860м; пороги не ослаблялись. Fresh official17/17/ДУ/глубина/
экономика/strict export всех3вариантов PASS; одиночные concave cases PASS.
Инженерный balanced/shortest2192,523м/14камер/1корень/25поворотов/302839881,84₽,0expert issues.
Cheapest1983,620м/14/1/28/290549818,37₽ с6нарушениями; backend preferred=cheapest поscore,
это не общий инженерный PASS. All-demand447,912с со30с JFR-срезом — не чистый benchmark.
Bundle `source64-final-result.json`, не live job. Runtime61/readinessUP сохранён.

[PRIMARY_ROUTING_64.md](PRIMARY_ROUTING_64.md): включены фактические нормали ближайших
допустимых стен и R+W/2 для forbidden-ограничений; полный собственный ввод и предыдущая
линия проверяются раздельно. Закрыты reentry в другой компонент ОКС, обратный луч, пропуск
отсутствующего выхода, обход через округление demand, ошибочный обязательный запас0,25м
и ложный отказ projected cut. Сжатие не срезает миллиметровые изгибы; коридор пробует
достижимые порты и локальные переходы до fallback. Предупреждение cheapest не теряется.

Последний fast Java900/0/0/3skip PASS (897выполнены), включая ownership/cut и экспортный
UTM rounding fix `2de2ceb`. Web36+scripts37/lint/typecheck PASS. Повтор dataset snapshot
дошёл до17/17,2192,523м/14камер/25поворотов,0экспертных нарушений balanced/shortest;
cheapest1983,620м/14/28 с6нарушениями. Fresh all-demand436,170с, ускорения нет.
Но dataset gate4/2failures/1error: компактность, projected cut одиночногоОКС8 и1копейка
экспорта. Последние два дефекта исправлены: свежий concave focused PASS и strict export
replay всех3вариантов/568features PASS. Эти промежуточные свидетельства не заменяют
финальный fresh результат, приведённый выше (`source64-final-full.log`).

Рабочая ветка `codex/routing-63-geometry`, `master=28c7059`, runtime61 не обновлялся.
G1/G2/G4/G5/G6 и R-этапы открыты. Новые нормы social_area/2vs3м не объявлены согласованными.
Ускорение, native64, Compose и scale не подтверждены; checkpoints не означают deploy/приёмку.

## 2026-09-25 — source63 в работе: направление ввода и совместные подходы

Git checkpoint: ветка `codex/routing-63-geometry`, commits `3d9b3b0` и `d4d6ef5` отправлены,
remote проверен. `master=28c7059`, runtime61; никаких merge/deploy/закрытия R-этапов.

**Последний gate:** clean879/0failures/0errors/3skipped PASS (876выполнены),5:51;
fresh17/17/ДУ/глубина/экономика/strict export PASS. Предпочтительный1857,156м/13камер/13поворотов,
без экспертных нарушений;276,574с — ускорение не доказано. Экспортные2копейки исправлены
округлением ценовых станций, не расширением допуска. Quality gate остальных ролей открыт:
cheapest10камер, но7экспертных нарушений против0у baseline; balanced длиннее baseline.
Runtime61 не обновляется; технический PASS не объявлен общим улучшением. PNG63/Evgeny
отрендерены/просмотрены как fixture, не новый live run. Вопрос о приоритете инженерных
правил во всех ролях отправлен пользователю. Подробные метрики/пробы — в отчёте ниже.

[PRIMARY_ROUTING_63.md](PRIMARY_ROUTING_63.md). Направление ввода включено в основной поиск,
его упрощение и восстановление после ДУ/глубины; совместный выбор геометрии портов сохраняет
полные контрольные деревья. Исправлены проверки bend spacing через degree2 и размерность
порога коротких звеньев (RED2 и RED5). Сеточный порт не получает льготу существующей врезки.
Focused371 PASS на промежуточной политике прямого подхода; после сохранения L-контроля
и нового отбора compliant-сетей57selector/terminal PASS. Web36+scripts37/lint/typecheck PASS.
Полный871/1failure/0errors/3skip snapshot ещё имел близкие повороты у shortest. Более ранний
866/0/0/3 PASS оказался хуже baseline62 по длине/камерам/времени и не был развёрнут.
Предыдущий полный `source63-selector-full.log`:873/0failures/1error/3skip,6:15; геометрические
assertions PASS, но strict export отклонил экономику cheapest. Причина исправлена в итоговом
прогоне, приведённом выше.
Прямая альтернатива исправляет сантиметровые доглеги локально. Дополнительный bounded
подграф без перенаправленных контролей вернул13-камерный черновик1858,476м, но это ещё не
finish/export. Его отдельный regression RED1→GREEN1; последний focused403/403 PASS.
Полная проверка и сравнение открыты. Строгие нормали/R+W/2 не подключены. Runtime61/VPS не менялись.

## 2026-09-25 — экспертные материалы и компоненты строгой геометрии, без включения в runtime

[EXPERT_ROUTING_2026_09_25.md](EXPERT_ROUTING_2026_09_25.md): G0/intake завершён на `2e7b713`.
239-feature сценарий сохранён отдельно;144 исходных Feature совпадают,94дороги+1детсад.
Два Node provenance-теста PASS. DOCX3/3страницы и PDF1/1 просмотрены; DWG только определён
как DWG2007–2009, CAD-данные не прочитаны. Google Docs прочитаны, не изменены.

`OfficialAxisClearance`61 focused PASS; `BuildingWallNormals`10 focused PASS, включая4отрицательные
находки независимого review (касание другой части, почти касательная нормаль, узкий проход и
допустимое равенство между касающимися отступами). Независимые360UTM-проб PASS.
Компоненты НЕ включены в активный алгоритм. Строгая интеграция: RED6 прежнего ввода → GREEN6;
полный plan2 максимум15/17,240,995с, геометрия хуже; проба3 собирает17, но валидатор отклоняет
стыки с поворотами>90°. Эти gates FAIL, не новая «улучшенная» сеть.3production-файла возвращены
к HEAD через apply_patch; патч/регрессии в ignored intake-каталоге для продолжения.

Следующий шаг — обязательное направление ввода в поиске и совместный выбор луча порта,
затем R+W/2 во всех стадиях. Не ослаблять17/17/углы ради зелёного теста.2/3м у камеры и исключение
social_area требуют подтверждения источника; ответы пользователя пока не получены.
Финальный clean Java:820случаев,0failures/errors,3scale skipped,5:29 (817выполнены).
После последней поправки helper повторены71/71 component tests; по совокупности818уникальных
Java-кейсов выполнены. Web36+scripts37, lint/typecheck иdiff-check PASS. Real Java inspector
дополненного входа:valid239,0errors,76warnings. Native/Compose/scale не заявляются;pwsh/Docker нет.
Локальный сервер и VPS не перезапускались. Общая цель и R-этапы не закрыты.

## Source61 — improved fresh plan, native and UI gates passed, 2026-09-24

[PRIMARY_ROUTING_61.md](PRIMARY_ROUTING_61.md): bounded terminal-derived frame at the first
group root, explicit shared grid/spur orientation, early exact port-turn admission, protected
collinear compression and regularized corridor winners included in existing refinement.
Final fast742 executed /3 skipped; datasets/export3/3 PASS =745 executed Java /3 skipped.
Web31+scripts35, lint/typecheck PASS. Fresh preferred1857.155m/13 new branch cameras/13 bends,
RUB273953260.00,17/17;58 was1887.222m/13/16,RUB276460904.63. Cheapest1895.501m/11 branch
cameras/no new tie-in camera/21 bends. Planner279.503s, not a statistical or native speed claim.
JAR/API61 readinessUP. Fresh UI run1a00e3a5-a818-49f7-abba-a920b9dfdc85,
job614015d5-ad76-4b83-848f-003dd493c075 completed/attempt1,321.618s (58:312.305s):
native speed did not improve. Preferred shortest1857.155m/13 cameras/13 bends; cheapest11/21
without new tie-in; balanced remains1932.357m/15/14. All17/17; independent topology/flow/slots/
endpoint/length and HTTP export200/974 features/cent arithmetic PASS; both actual61 PNG viewed.
Cheapest removes one new tie-in but costsRUB769810.36 more than native58 cheapest.
UI initially selects valid/max-connected preferred with explicitly empty engineering issues,
otherwise engineering fallback. Manual selection survives same-run refetch. RED2 expected,
final36 web +35 scripts, lint/typecheck/build and browser/map/console PASS (large MapLibre
chunk warning remains). Backend unchanged
after745 Java/3 skipped; no additional fresh job after UI-only edit.
Independent abstract tree oracle:1024 subsets/7 feasible/minimum11,10 greedy/metric misses.
Next bounded joint-terminal seed; not geometric evidence or integrated behavior.
Source9a8b9e0 pushed, remote verified. No Compose/scale/R-stage/goal closure. User authorizes
Git checkpoints/push, not VPS deployment. Independent review61 hit quota error.

## Research60 — eleven-camera diagnostic, no production change, 2026-09-24

[PRIMARY_ROUTING_60.md](PRIMARY_ROUTING_60.md): second dynamic leaf exchange produces an
admitted1941.530m/11-camera/20-bend diagnostic network,17/17, full sizing/depth/export PASS;
independent topology/flow/root/turn checker PASS within its stated scope. Not an improvement
over preferred58 and not a fresh end-to-end/UI result; not integrated. Exact comparison
image rendered/viewed against Evgeny's1913.859m/11-marker/15-bend reference. Terminal-derived
local frames, receiver relocation, capacity-chain and leaf-swap probes do not improve it.
Independent104-raw-draft shortlist audit finds zero raw nonregressing and zero missed
raw nonregressing candidates, despite near-duplicate crowding. Next: bounded alternate
whole-corridor frame/tree generation upstream, not more equivalent local relocation.
Runtime/class/JAR hashes unchanged58, readinessUP. No new main test/native/Compose gates,
R-stage closure, Git/VPS changes or speed claim. Balanced-objective preference unanswered.

## Research59 — bounded paired relocation, no production change, 2026-09-24

[PRIMARY_ROUTING_59.md](PRIMARY_ROUTING_59.md): three diagnostic searches rebuild two
adjacent cameras and their shared/outer approaches together. Length-first, bends-first
and joint-interval positions:62/62/82 attempts,16/24/24 real candidate finish/export calls,
all pass mandatory admission. No fully non-regressive replacement among finalists:
13 cameras/16 bends/7 irregular pairs adds1.063m andRUB80987.41;
12 cameras/17 bends worsens irregular pairs to11. Not integrated; runtime stays58.
Independent geometry analysis identifies fixed-egress lower bounds and signed-port conflicts.
Next: terminal-derived axes and explicit audit of bounded candidate selection, not larger
blind budgets. No new full-plan/native/Compose gate, overall completion, Git or VPS change.

## Source58 — scoped terminal alternatives, dataset and native gates passed, 2026-09-24

[PRIMARY_ROUTING_58.md](PRIMARY_ROUTING_58.md): real red/green identifies the finalizer aiming
an already valid terminal approach at a remote camera instead of the actual adjacent point.
Global application passed initial datasets but regressed shortest13→14 cameras and price;
that candidate is rejected, never deployed. Now the old core finalization is retained; bounded
coordinated relocation finishes BOTH policies, admitting only non-regressive improvements.
Full-DU prefix, extra terminal-obstacle check and full final admission remain. Final fast727
executed /3 skipped, web31+scripts35, lint/typecheck PASS. Final datasets/export3/3 PASS:
730 executed Java /3 skipped overall; full result exactly equal57 except top-level version,
full corridor equal, negative controls checked. Planner299.002 s vs298.629 s: no speed claim.
JAR/API58 readinessUP; fresh UI job76e08f8b-a3f9-447b-9f11-add6d902e895 completed/attempt1,
312.305 s end-to-end (57:305.650 s). Run9caa60ec-9a35-40f9-83e0-cadfe5485f8c;
native result also exactly equal57 except version, real HTTP export200/1030 features/same SHA.
Full UI→DB→map→export PASS, console clean; existing server client-abort noise remains.
Actual preferred1887.223 m/13 branch cameras/16 bends; shape unchanged. Both actual58 comparison
images rendered and viewed; browser left on shortest/rank1. Verification skills used with CUA fallback.
Compound leaf/receiver relocation probe yields12 cameras but extra bends; not integrated.
Independent saved-artifact topology/flow/root/angle checker24 synthetic checks PASS.
No overall shape/speed success, goal/R-stage/Compose/scale closure, Git or VPS update claimed.

## Source57 — assessment/resource changes, dataset and native gates passed, 2026-09-24

[PRIMARY_ROUTING_57.md](PRIMARY_ROUTING_57.md): admission metrics are reused only within the
unchanged draft-selection pass, not recomputed inside comparators; no final admission removed.
Real red/green:8→0 comparator sizing calls, same three completed drafts in both depth modes.
Oversized/unsupported constraints reuse existing prepared fallback without copying; preparation
retention reserves additional explicit index coordinates before allocation (not heap-byte cap).
Java fast721 total /718 executed /3 scale skipped PASS; web31+scripts35, lint/typecheck PASS.
Full dataset/export3/3 PASS: **721 Java executed /3 scale skipped overall**. Full56→57 result
deep equality excluding only the top-level version; corridor identical. Planner299.930→298.629 s
is no material overall speed gain (single runs, short isolated probe overlapped57).
JAR57 packaged; local API57 PID81078 readinessUP. Fresh UI job
`a80b9b0d-c25c-4a0d-9654-6eb7b2922b39` completed/attempt1,305.650 s end-to-end;
native run `961aff95-7280-4460-96b4-cda7f144e1c1`. Full native56→57 result deep-equal except
the top-level version; HTTP export200/1030 features, same SHA as56, cost/node/WGS84 checks PASS.
UI opens result automatically; rank1 selected, map/17of17 verified, console clean, server
client-abort noise remains. Actual preferred1887.223 m /13 chambers /16 bends; no geometry gain.
Both57-live-preferred/57-live-cheapest images rendered and viewed, using actual default2D result.
Two bounded geometry probes rejected: clearance-before-cap and leaf exchange both reduce a
camera but worsen score/bends. Best validated exchange1906.953 m /12 cameras /17 bends,
RUB275817240.07,17/17; real sizing/depth/export passed but not integrated.
Next bounded geometry probe: leaf exchange plus receiver-chamber relocation/rebuilt approaches,
then coordinated trunk/multiple-branch change if needed. These are not implemented in57.
Geometry/goal/Compose/scale remain open; no Git/VPS update.

## Exact segment/polygon predicate — source56 local gates passed, 2026-09-24

[PRIMARY_ROUTING_56.md](PRIMARY_ROUTING_56.md): bounded prepared boundary index, exact JTS
semantics including touches/holes; no objective/topology changes or cross-run route reuse.
Fast692 executed /3 skipped +datasets3/3 PASS =**695 executed Java /3 scale skipped**.
Web31+scripts35 and lint/typecheck PASS. Full55→56 result deeply equal except its top-level
version; input/profile and full corridor output equal, including negative comparison controls.
All-17 350.860→299.930 s in single local runs (55 included short JFR; not a clean benchmark).
Preferred unchanged:17/17,1887.222 m /13 branch chambers /16 bends,RUB276460904.63.
Image56-preferred rendered and viewed. JAR56 built; API56 readinessUP, native PostgreSQL/Vite.
Fresh UI job `0871d44d-c89a-47f8-9526-0b1c4333c0c8` completed/attempt1 in309.280 s;
real map/result and strict HTTP export1030 features PASS. Actual default2D preferred:
1887.223 m /13 chambers /16 bends,RUB276461145.20,17/17; live images viewed in56-live-preferred
and56-live-cheapest. Live is not exactly the depth-on/Proj4J fixture: differences explicitly
recorded, not hidden by fuzzy equality. Browser console clean, server has client-abort noise.
Review found oversized fallback copying and unaccounted additional preparation coordinates;
explicit follow-up in source56 doc. Compose/scale and geometry/speed goal remain open.
Historical live49 statements below refer to their old checkpoints, not the current process.

## Coordinated chamber relocation — source55 local gates passed, 2026-09-24

[PRIMARY_ROUTING_55.md](PRIMARY_ROUTING_55.md): bounded relocation jointly rebuilds all incident
approaches, keeps the original finished roles and reselects after full admission. Real-network
probe and now the fresh full55 plan both improve1898.684→1887.222 m and18→16 bends,
13 cameras unchanged, cost277670360.94→276460904.63 RUB, all17 connected. Fast671 total /668
executed /3 scale skipped plus datasets3/3 PASS: **671 executed Java /3 scale skipped overall**.
All-17:350.860 s, corridor100.209 s; sizing/depth/economics/export preflight PASS. Web31+scripts35,
lint/typecheck PASS. Independent snapshot arithmetic/root/capacity checks PASS; the unchanged
legacy1.414 mm endpoint discrepancy is explicitly recorded. Images55-preferred/55-cheapest
rendered and viewed. Short45s JFR identifies intersection predicates as a follow-up target,
not a proven overall speedup; the run includes profiling overhead. Live49 readinessUP unchanged,
new runtime/Compose/scale pending. No R-stage is closed; geometry/speed goal remains active.

## Compliant interior simplification — source 54, local gates passed, 2026-09-24

[PRIMARY_ROUTING_54.md](PRIMARY_ROUTING_54.md): the primary finalizer can now shorten a legal
dogleg while preserving complete endpoint segments, local own-OKS approaches and topology.
A second full sizing/depth/geometry/economics admission accepts only a shorter, non-costlier
result with fewer bends and no engineering regression. The141→81 m /4→0 fixture passes all
three strategies and both depth modes. Negative controls cover extra special-construction
cost, crossings of separate/shared-root branches and illegal splice angles.

Initial fast625 total /622 executed /3 scale skips, web31 +scripts35, lint/typecheck PASS.
Initial datasets3/3 PASS; corridor98.643 s, all-17 340.751 s and complete result equals53 except
the version. Additional helper review caught an empty-section fallback cost omission: the guard
now declines that case. Final fast645 total /642 executed /3 skips PASS after the guard and
extra regressions; final dataset3/3 PASS (corridor99.089 s, all-17 340.630 s), with enabled
depth, sizing/economics and independent export validation. Overall **645 executed /3 scale
skips**. `.tooling/verify-primary54-result.mjs` → `primary54-final-result-equivalence.log` PASS:
the complete final result equals53 except the top-level version. Preferred remains1898.684 m,
13 cameras,18 bends; cheapest1961.037 m,11 cameras,24 bends. Time340.609→340.630 s is not
a speed gain. Actual images: `.tooling/routing-comparison-54-preferred` and `54-cheapest`.
Live49 unchanged, readiness UP; this is not a smoke54/Compose gate. No R-stage is closed.
Next: trace rejected chamber contractions/branch reassignment before increasing search budgets.

## Safe shortcut/corner processing — source 53, 2026-09-24

[PRIMARY_ROUTING_53.md](PRIMARY_ROUTING_53.md) records two reproduced postprocessing bugs:
normalization could introduce an obtuse downstream turn, and corner snapping could invalidate
a neighbour. Both shortcut endpoints and all three affected snap turns now use the existing
millimetre-aware mandatory angle predicate. Only adjacent rounded duplicate points are removed.
The six new tests include real forbidden polygons, large translated/rotated coordinates,
all preferences, valid simplification, non-mutation and cancellation. No validator was weakened.

Separately, exact ordered rounded-tail deduplication avoids repeated chamber-approach checks:
38 -> 27 on the fixture, same eight accepted outputs; complete output digests match across24
geometry combinations. Raw clearance precheck and original bounds are preserved.
Integrated Java fast gate: **622 total /619 executed /3 scale skips**, no failures/errors,
`.tooling/primary53-fast.log`. Separate dataset gates are now **3/3 PASS**
(`.tooling/primary53-datasets.log`): corridor98.95 s and main dataset2/2 including all-17,
complete enabled depth, sizing/economics and independent export validation. Overall fast +
dataset: **622 executed /3 scale skips**. Web31 +scripts35 PASS
(`primary53-web-test.log`), lint/typecheck PASS (`primary53-web-lint.log`, `primary53-web-typecheck.log`).

Saved source53 `run.result` is deeply equal to integrated52 excluding ONLY its top-level
`algorithm_version`: `.tooling/verify-primary53-equivalence.mjs` and
`.tooling/primary53-result-equivalence.log` PASS, independent of object-property order, with
ordered arrays and geometry-mutation negative controls. Input SHA/parameters match too.
Whole bundle bytes are not equal: the wrapper additionally differs in three timestamps and
the duplicate run version; the complete difference inventory is asserted and logged.
Actual preferred shortest remains17/17,1898.684 m,13 branch cameras,18 bends,
RUB277670360.94,score13.470822106; cheapest1961.037 m,11 cameras,24 bends,RUB275517854.67.
Shortest and cheapest use existing TK106/two rays; balanced has15 branch cameras plus
one new tie-in chamber on segment126, not TK106. Internal all-17 **358.585 ->340.609 s**:17.976 s less
in these single runs, not a statistical speedup or SLA. No geometric improvement claimed.

Actual53 preferred/cheapest renderings were regenerated and visually inspected in
`.tooling/routing-comparison-53-preferred` and `53-cheapest`, with reference source-byte/geometry
verification; exact logs `primary53-preferred-render.log`, `primary53-cheapest-render.log`.
Advisory-only `.tooling/primary53-geometry-quality.json` confirms unchanged18/24 bends.

Next bounded opportunity is reproduced, **not implemented in53**: compliant doglegs never
reach `regularizeFinishedEngineeringEdges` because it only visits `nonCompliantEdgeIds`.
Real-domain isolated finish retains141 m/4 bends;81 m/0 bends with the same terminal rays
and OKS suffix passes independent geometry, engineering, sizing and enabled-depth validation.
Full-edge regularize returns null here; exterior-only regularize plus retained final approach
passes finish. Evidence: `.tooling/CompliantDoglegFinishProbe53.java`,
`.tooling/primary53-compliant-dogleg-finish-final.log` (0.663 s process CPU).
Follow-up54: bounded retained-end interior simplification, without weakening admission.
Live remains49; Compose/scale for53 unverified; no Git push, VPS deployment or R-stage closure.

## Early turn pruning and disconnected-graph rejection — source 52, 2026-09-24

Current checkpoint: [PRIMARY_ROUTING_52.md](PRIMARY_ROUTING_52.md). Mandatory <=90-degree
turns now participate in directed search. Subsequent performance-only changes reject disconnected
visibility components before directed state expansion and use a conservative fast angle bound.
Fresh all-17 A/B: **417.499 -> 313.977 seconds** (24.8% less in this pair of local runs).
The complete `run.result` and input SHA are equal, including every variant, geometry, DU,
depth, economics and rank: `.tooling/primary52-component-equivalence.log`.
This is not a statistical benchmark or a new-machine SLA. No stored calculation result was reused.

The separately reproduced missing terminal-incumbent candidate is addressed by bounded prefix
retention during chamber merging (<=38 tails / <=8 accepted paths); 18 new tests cover its
geometry and work bounds. Main integrated Java fast gate: **613 total / 610 executed / 3 scale
skipped**, no failures/errors, `.tooling/primary52-integrated-fast.log`. Web31 + scripts35,
lint/typecheck PASS (`primary52-web-*`). Full integrated corridor1 + main dataset2 subsequently
PASS (`primary52-integrated-datasets.log`): **613 executed Java tests overall,3 scale skips**.
Final all-17 time358.585 s:14.1% less than angle-only417.499 s, but44.608 s more than the
performance-only run because the new alternatives/finishes spend part of the gain.
Actual preferred shortest:17/17,1898.684 m,13 branch cameras,18 bends,RUB277670360.94,
score13.470822106,existing TK106/two rays,complete sizing/economics/depth and export PASS.
Against51:one fewer camera and RUB3123112.43 less, but18 vs15 bends and6 vs4 advisory oblique
junction pairs. Not a Pareto improvement in geometric quality. Cheapest:1961.037 m,11 cameras,
24 bends,RUB275517854.67. Actual final comparisons, visually reviewed:
`.tooling/routing-comparison-52-preferred` and `52-cheapest`; advisory report `primary52-geometry-quality.json`.

Separate follow-up reproduced: normalize can introduce a 135-degree turn into a legal path.
`.tooling/primary52-normalize-audit.log` is a synthetic visibility-model diagnostic, not an
official-dataset failure or a completed fix. Preserve adjacent mandatory angles during shortcut
and corner snapping, then rerun the pipeline. Current final validator still rejects such paths.
Live49 readiness is UP; no runtime replacement, Compose/scale closure, Git push or VPS update.

## Mandatory turns and existing support DU — source 51, 2026-09-23

Current implementation/evidence: [PRIMARY_ROUTING_51.md](PRIMARY_ROUTING_51.md).
Existing-network DU now participates in new-chamber sizing/cost before selection and is rechecked
at export. Role-independent <=90-degree deflection validation covers polyline vertices and
degree-two nodes. A resulting 1/2 coverage regression exposed the old obtuse perpendicular
approach; a checked local geometric repair restores 2/2 without weakening validation.
Final fast Java gate: 589 total, **586 executed**, 3 opt-in scale skips, no failures/errors
(`.tooling/primary51-final-fast.log`). Plus real corridor1/1 and fresh main dataset2/2:
**589 executed Java tests overall**, 3 skipped. Web31 + scripts35, lint/typecheck PASS.
Post-review export checks actual section geometry, joins and monotone equivalence to the edge;
a reproduced endpoint-tolerance loop regression is fixed. Latest adapter revalidates the fresh
all-17 artifact: all3 variants / 920 exported features PASS (`primary51-final-export-recheck.log`).
Fresh main result selects shortest: 17/17, 1899.927 m, RUB280793473.37, 14 branch cameras,
one existing TK106 root/two rays, 15 bends, no mandatory or engineering issues.
Facade-axis deviation is 5.592 degrees, but four advisory irregular junction pairs and three
extra cameras vs the expert remain. Cheapest uses9 cameras but2061.120 m and28 bends, so it is
not the desired geometric solution. Internal all-17 time407.177 s includes a120s JFR window;
not an isolated speed benchmark. Actual selected comparison: `.tooling/routing-comparison-51-preferred`.
Live remains49. No Compose/scale gate, R-stage closure, Git push or VPS update.
Open-defect statements in the source50 checkpoint below describe that historical source.

## Standards applicability and attachment pricing — 2026-09-23

The supplied SP references prompted a source/version check against the amended organizer DOCX.
[ROUTING_STANDARDS_APPLICABILITY.md](ROUTING_STANDARDS_APPLICABILITY.md) separates contest
requirements from expert geometry heuristics and records the verified SP status and limits.
This is not a full 2026-SP compliance assessment. Old roadmap bend tariffs and per-ray fees for
new chambers are superseded; route compensation/structural design is not inferred from 2D lines.

Concrete pricing regression reproduced through the real planner/export test: a new tie-in chamber
is wrongly charged an additional RUB 5 million despite appendix §3.2 including attachment in the
chamber price. `.tooling/tie-in-economics-export-red.log`: 1 test, 1 expected regression failure
(all three new-chamber variants). Correction is implemented: only rays at existing chamber roots
are charged. Main focused Java gate is 52/52 PASS (`.tooling/primary50-standards-focused.log`),
including the real planner/export regression. Extended Java gate is 524 tests, 0 failures/errors,
3 opt-in scale skips: **521 executed**, `.tooling/primary50-standards-unit.log`. This includes the
real all-17 corridor component with final sizing/economics/depth/refinement, not the full main
pipeline. Separate full main-pipeline rerun subsequently passed 2/2: **523 executed Java tests
overall, three scale skips**. Evidence: `.tooling/primary50-standards-official.log/.json`.
Selected geometry/length/cost/coverage remain unchanged: balanced/shortest 17/17, 1899.051 m,
RUB 269806210.70, 12 branch chambers; cheapest 1894.445 m, RUB 264775893.97, 11 branch chambers.
All selected variants use existing chambers; the new-chamber fee fix does not lower their cost.
Internal all-17 time: 433.648 s vs previous 440.828 s, non-isolated measurements; no established
speed gain. The two excessive deflections in cheapest persist in this fresh result.
Web 31 + scripts 35, lint/typecheck PASS in `.tooling/primary50-standards-web-*`.
Separate open audit item: maximum diameter of a newly built chamber on the existing network must
also account for the existing incident network, not only the new route edges.
Mandatory bend audit is also open: the saved preceding main result's cheapest geometry contains
two deflections above the appendix's 90-degree limit (91.2055 and 91.6649 degrees). The current
`valid` flag and passing tests do not certify this untested official invariant. See the standards
applicability note; implement an independent role-agnostic bound, not a new arbitrary bend tariff.

Invocation-local exact-state deduplication is implemented for corridor refinement. Main Java 11
initial focused gate: 22/22 PASS, `.tooling/primary50-dedup-focused.log`; no route-result cache
across runs. Review then reproduced incorrect suppression of an unexpanded pruned seed. It is
fixed with separate expanded/returned/frontier identity sets and two additional regression tests,
included in the renewed gates above. Real corridor candidates remain unchanged (best 11-camera
candidate 1986.104 m / RUB 277626080.01). No speed gain is inferred from this component gate.
This changes beam diversity on duplicate states; old full main-pipeline results below do not
verify the revised search or corrected attachment economics.
No source-50 promotion, R-stage closure, Git push or live API replacement has occurred.

## Expert-like corridor network — active work, 2026-09-23

Latest checkpoint, 22:46: main refinement is integrated; renewed Java gate is 493 tests,
0 failures/errors, 3 skipped (490 executed). Evidence: `.tooling/primary50-refinement-unit.log`.
The production refinement probe produces `corridor-refined-16`: 11 branch cameras, 17/17,
1986.104 m / RUB 277626080.01, complete final sizing/economics/enabled depth and no bend warnings.
It remains a development candidate, not a selected/live result: +2.90% cost vs balanced 49,
24 bends and two oblique junction-angle pairs under the advisory 5-degree diagnostic.
Same-scale images and verified reference provenance are in `.tooling/routing-comparison-50-refined`.
Renewed web/script gate: 31 + 35 tests, lint/typecheck PASS (`primary50-refined-web-*` logs).
Renewed full main-pipeline gate is 2/2 PASS: **492 executed Java tests** in total, three scale
tests skipped. All selected result fields exactly equal the pre-refinement 50 result (and 49
except version): balanced/shortest still 1899.051 m / 12 cameras. Actual main pipeline executes
refinement, but final selection does not promote it. Internal all-17 time is **440.828 s**,
versus previous non-isolated 377.970 s for 50 and 272.911 s for 49. Faster operation is not proven.
Evidence: `.tooling/primary50-refined-official.log/.json`; actual selected-result images are
separate in `.tooling/routing-comparison-50-refined-selected`. Live remains 49; no R-stage closes.
Earlier entries below describe intermediate states, not current verification claims.

Current follow-up after the full gate below: bounded chamber consolidation is under development.
The first real finish probe reached 11 cameras / 1875.812 m / RUB 265810817.85, but four bend-angle
findings and worse junctions prevent promotion. Orthogonal assignment, retained-edge checks and
normal-to-corridor transitions are being added; focused regressions expose missing free-space
approaches. Main portfolio integration and renewed full verification are still pending. Earlier
444-test/full-pipeline evidence does not cover these subsequent edits; live remains 49.
New focused component gate: 16/16 PASS for `CorridorLinkApproachesTest`,
`CorridorJunctionAssignmentTest`, `NormalCorridorTransitionsTest`; see
`.tooling/corridor-transitions-gate.log`. This covers bounded checked geometry construction,
crossing/retained-node handling, rotation/translation and cancellation, not the full new algorithm.
Follow-up focused gates pass 52/52 (including both reproduced regressions), then 14/14 after adding
local axis-intersection chamber positions. Best 11-camera candidate is 1985.974 m / RUB 277611239.00,
with no bend warnings but remaining junction findings; still not a successful quality promotion.
The bounded two-state, three-level refinement is now connected to the main portfolio, retaining
all controls and requiring real finish before admission. Renewed full verification is pending.

The user goal now requires dynamically generating expert-like common corridors, not only keeping
the `49` result faster. [PRIMARY_ROUTING_50.md](PRIMARY_ROUTING_50.md) records the objective,
geometric baseline and unfinished acceptance checks. A new bounded facade-oriented graph and
degree-constrained tree candidate are implemented; early official-data candidates connect 17/17
and pass the geometric validator but remain too long / chamber-heavy. They are not promoted as
finished. The verified live API stays on `49`; no acceptance or deployment claim is made for `50`.
The next focused gate passed 39 tests, including metric-closure trees and rotated corridor grids.
All 14 early candidates also passed real final sizing/economics/enabled depth in a 13.568-second
focused dataset test; they still have excessive length/cameras and expert-angle findings, so this
is not a successful quality promotion. Dominant facade-cluster orientation, strict root-local
exceptions, checked terminal prefixes and bounded conflict-aware port retries are now implemented.
Current Java gate: 445 tests / 0 failures / 3 skipped (442 executed), excluding the separately
running long full-planner dataset class. The new self-intersection, foreign-obstacle and cancellation
regressions pass. Evidence: `.tooling/primary50-unit-current.log`.
Fresh candidate `corridor-22` is 1901.581 m, 17/17, 14 branch chambers, 0 new tie-in chambers,
one existing root / two outgoing rays, RUB 280954359.27, complete sizing/economics/enabled depth.
Its expert-like alignment improves, but cost is +4.13% vs 49, chambers are worse and near-parallel
junction rays remain. It is **not promoted**. Same-scale comparison images use actual result/reference
vertices and explicitly say development candidate; see [PRIMARY_ROUTING_50.md](PRIMARY_ROUTING_50.md).
Full-planner follow-up: `OfficialDatasetRoutingTest` 2/2 PASS, bringing the current executed Java
total to 444 (three scale tests skipped). All result fields equal 49 except version: balanced stays
1899.051 m / 12 cameras, cheapest 1894.445 m / 11 with ten bend-angle findings. Full-pipeline internal
time is 377.970 s versus the previous non-isolated 272.911 s. New strategy currently costs time
without improving selected outputs; it must not be promoted as success. Fresh selected-result images
are separate from the development-candidate comparison. Live API remains 49; no R-stage closes here.

## Invocation-local obstacle preparation — 2026-09-23

Current local code is `global-tree-49`; [PRIMARY_ROUTING_49.md](PRIMARY_ROUTING_49.md) owns its
verification record. JFR identified repeated constraint buffering as the main sampled hotspot.
Preparation now reuses only exact immutable geometry within one calculation, bounded by entry
and coordinate counts; source windows, per-path exemptions and independent final validation stay
unchanged. New jobs do not replay previous route results. Unit gate: 301 executed Java tests,
three opt-in scale tests skipped. Full dataset/live equivalence and timings are recorded there.
Final evidence: two additional dataset tests pass (303 Java tests executed overall); all result
fields match `48` except algorithm version. Full 17-demand case: 273.074 s vs 492.612 s; fresh live
run: 286.960 s vs 554.817 s, with overlapping local runs rather than an isolated benchmark. The
348-feature browser export is exactly equal. Web 31 + scripts 21, lint/typecheck/build and reference
validation pass. Comparison images show 12 main-route branch chambers versus 11 expert markers,
not a claimed global optimum. No R-stage, Compose, scale or restart gate is closed by this checkpoint.

## Finalized portfolio and expert comparison — 2026-09-23

The preceding local implementation is `global-tree-48`. See
[PRIMARY_ROUTING_48.md](PRIMARY_ROUTING_48.md) for the change boundary and verification record.
Local-search control trees are retained; length/cost roles use fully finished candidates, with
mandatory profile admission when depth is enabled. Identical failed pocket graphs are skipped
without changing the search on any different graph. Reproducible comparison maps retain all real
vertices, common metric extent and the original expert overlay provenance. The preceding `47`
checkpoint below remains historical evidence, not a claim about this revision.

## Consolidated primary routing — 2026-09-23

Active routing is now `stable` / `global-tree-47`. The separate experimental implementation and
launch button have been removed; legacy profile requests dispatch to the same primary component,
and archived results remain readable. This supersedes the profile-isolation description below.

The implementation combines exact search/validation acceleration with group trunks, reusable
degree-compatible chambers, legal fourth-ray approaches, shared physical tie-ins and courtyard
fallback. Final sizing now follows all geometry changes and blocks invalid results. The UI opens
the fullest valid engineering variant without changing the official objective ranking and shows
separate camera/site/ray counts. See [PRIMARY_ROUTING_47.md](PRIMARY_ROUTING_47.md) for rationale,
regressions, measured tradeoffs and the final verification record.

Final local checks passed: 253 executed Java tests (three opt-in scale tests skipped), 31 web
tests, eight script tests, lint/typecheck/build, reference validation, and the real browser →
Java/PostGIS → export flow. The full 17-demand testcase took 451.725 s versus 930.455 s at the
baseline; the live run took 467.942 s. Balanced now has 12 new chambers, one tie-in site/ray and
zero expert bend/spacing findings, but length increased 5.48% and the official score worsened
1.95%. This is a geometry/camera/runtime improvement, not an improvement in every objective.

No R-stage is closed by this change. Native Java/PostGIS/UI checks and Docker/Compose acceptance
are separate evidence; the latter remains mandatory in an equipped environment.

## Bundled local demo bootstrap — 2026-09-23

The web workspace now handles an empty local database correctly. `Открыть демо` still opens the
latest completed run when one exists; otherwise it imports the exact tracked
`datasets/official/lct-2026.geojson` from the Java application classpath and presents the validated
144-feature report with separate stable and experimental launch actions. It does not fabricate a
route result or start a long calculation without the user's choice. Repeated requests reuse the
content-addressed official import.

The focused controller/service tests cover resource packaging and the six workspace cases cover
the empty-database fallback and profile selection. The Java 11 suite excluding the dedicated
full-dataset routing integration test passes 157 tests with three opt-in scale tests skipped. All
20 web tests, lint, typecheck, production build, the reference benchmark and a live PostGIS/API/UI
smoke pass. Docker is unavailable on this workstation, so the repository's full Compose gate and
the long official-dataset calculation remain CI gates. This UX fix does not close a routing
performance or official acceptance stage.

## Isolated routing profiles — 2026-09-23

Routing experiments now have an end-to-end profile boundary. Existing and body-less run requests
remain on `stable` / `global-tree-46`; the separate `expert_experimental` / `expert-tree-1` Spring
component uses its own bounded search tuning. The immutable run parameters select the algorithm
through a registry, so job replay after a worker restart cannot silently fall back to another
profile. Validators, sizing, economics and export remain shared and mandatory.

The workspace exposes separate main and experimental actions, labels completed results with their
profile and algorithm version, and keeps a separate pointer to the latest run of each profile for
the current import. The development and promotion process is recorded in
[EXPERIMENTAL_ROUTING.md](EXPERIMENTAL_ROUTING.md).

The 36 focused Java 11 profile/planner tests and all 20 web tests, lint, typecheck, production
builds and the reference benchmark pass. The 2026-09-23 CI follow-up fixed the two former baseline
failures in `OfficialObstacleRouterTest` and `OfficialGeoJsonExporterTest`; the wider local Java
suite now passes 157 tests with only three explicitly opt-in scale tests skipped. A full
official-dataset comparison and live Compose import → both profiles → export smoke remain pending;
this infrastructure change does not close an R-stage or promote the experimental result.

## Advisory routing reference corpus — 2026-09-23

The expert overlay supplied as `1.geojson` is now preserved separately from organizer input as
`datasets/reference/professional-routing-01.geojson`. Its embedded old 144-feature baseline and
map-editor styles were excluded; source hash and feature indexes remain for provenance.

The focused Node benchmark reconstructs the reference in EPSG:32637 and verifies 17/17 demand
leaves, 11 junction markers, 28 logical sections, 29 graph nodes, one acyclic component, maximum
junction degree four and two root flows of 96.90/391.82 t/h at existing chamber 106. Two internal
marker splits are covered. Exact coordinate similarity remains advisory and cannot override the
official validator, economics or organizer documents.

This adds a route-quality regression process, not an R-stage completion claim. Production planner
behavior is unchanged; a complete `global-tree-46` corrected-dataset run and the required Java 11,
lint, typecheck and live Compose gates remain open.

## Code-quality rules and refactoring backlog — 2026-09-21

[REFACTORING.md](REFACTORING.md) records the Java-focused audit baseline (`0096da4`), concrete
rules for new/changed code, open correctness findings B-01–B-12, and staged work RF-00–RF-06.
Feature development continues; broad structural refactoring is deferred until the active scope
and its behavioral baseline are stable. Correctness and recovery fixes remain separate priority
work, with reproducing tests before changes. AGENTS and handoff now link to these rules.
The rules also require concise Russian JavaDoc/JSDoc for key entry points, domain algorithms
and non-obvious contracts, with examples and a reviewer-oriented documentation check.

This is documentation only. No application code was changed, and no B/RF/R gate was completed.
The audit's Java findings are static; Java 11/Compose verification remains required. The evidence
and limits of the previously run web checks are recorded in the new document.

## Organizer video clarification review — 2026-09-17

The full organizer Q&A recording was reviewed against `origin/master` at `c5413b8`. The active
decisions and unresolved contradictions are recorded in
`ORGANIZER_VIDEO_CLARIFICATIONS.md`; `docs/ALGORITHM.md`, `docs/IMPLEMENTATION_PLAN.md`, the roadmap,
handoff, contracts and acceptance gates now point to that interpretation.

The review was followed by a local Q&A-P0 implementation pass:

- supplied-profile reconstruction no longer blocks cost/rank/export; strict-profile gating remains;
- demand points inside OKS receive validated nearest-boundary normal egress with DU 5/7/9 m clearance;
- direct exclusive spurs are compared with penalty; bend ×1.5, overlap max `K_special` and per-ray
  tie-in cost are integrated;
- default runs are mandatory 2D; optional R8 requires `depth_enabled=true`;
- calculation materializes only core network/connection features; restrictions and existing OKS
  are fetched from PostGIS by EPSG:32637 route windows. A dense-window and PostGIS equivalence gate
  is still required before this scale slice can be accepted.

Focused planner/export tests, a final shared-OKS regression, a healthy local Compose build and the
real supplied-file import → run → export cycle have now been run. The full Java 11/lint/typecheck/R9
gate has not been repeated on this final working tree and remains the next verification checkpoint.

Historical R5/R7/R8 evidence below remains valid for the implementation that was tested, but it is
not proof that the clarified supplied-dataset P0 is complete.

**Updated:** 2026-09-17

## Active baseline

The production path is Java-only. `apps/api` contains Java 11 / Spring Boot 2.6.3; default,
offline and VPS Compose use that image. Python application code, dependencies, migrations, tests
and runtime services were removed. The frontend calls only the current official Java endpoints.

### Official contest input correction

- Organizer Q&A confirmed that the supplied GeoJSON shape is the judging input and that
  `oks_connection_point.flow_tph` directly represents demand. The active profile is now
  `official_contest_dataset`, not a compatibility exception.
- Numeric IDs, direct-demand connection points and `restriction_type=oks` are accepted without
  warnings. The unchanged organizer file now produces 76 actionable warnings: 29 missing existing
  flows, 38 inferred upstream links and 9 chamber diameters. `railway` is treated as `tram_tracks`.
- The UI presents the file as ready for calculation and keeps only reconstruction warnings.
  The routing, sizing and depth algorithms are unchanged by this contract correction.
- Final Java 11 `OfficialDatasetRoutingTest` passes on the untouched 233,277-byte organizer file
  and generates a completed three-variant replay bundle with 76 input warnings and zero route/depth
  issues. A local browser pass confirms the map, 17/17, 16/17 and 14/17 variants, grouped diagnostics
  and a validated longitudinal profile.
- The same browser pass exposed a remaining UI defect: after switching from profile mode back to
  the map and then changing variants, the CARTO basemap and some route overlays can disappear while
  the result data remains present. Treat this as an open renderer-lifecycle bug before demo freeze.

## Verified in this cutover

- Maven verifies 76 Java tests plus opt-in scale probes (the production/CI gate remains pinned to
  Java 11).
- Web ESLint, TypeScript, Vitest (9 tests) and production Vite build pass.
- Compose starts PostGIS, Java API and web; all three become healthy.
- `/api/v1/health/ready` reports PostGIS ready.
- Java OpenAPI is saved as `packages/api-client/openapi.json`.
- Browser smoke at 1264×712 shows the official workspace, Java-ready status and upload action
  without a blank page or framework error overlay.
- Live fixture import `63d30d86-e1db-4d03-b839-0300f583df4f` persisted as `valid` with eight
  features across the seven required types.
- Durable topology job `0afabe99-408e-43d4-84b5-148bc8835bb8` completed on attempt 1 and returned
  a valid topology with two deterministic tie-in candidates.
- GitHub Actions run `35073870126` passed all backend, web and integration gates for commit
  `e47cd72`; integration uploads the official dataset and waits for a real calculation run.
- GitHub Actions run `35075903157` passed backend, web and integration gates for the visual
  result viewer and latest-completed-run API in commit `becdaca`.
- Commit `e47cd72` is deployed to the VPS. The production Compose project contains only `db`,
  Java `api`, `web` and `gateway`; all four services are healthy. The former Python API,
  worker/scheduler, migration container and Redis were removed from the running project.
- External HTTPS smoke returned HTTP 200, Java readiness reported PostGIS `ok`, and the VPS
  official fixture import persisted eight valid features. Its topology job completed on attempt 1
  with two deterministic tie-in candidates.
- Commit `becdaca` is deployed to the VPS after a PostgreSQL backup. All four services are healthy;
  the public latest-run endpoint returns the completed 17-demand official calculation with two
  variants and the browser viewer renders it without console errors.

## Supplied dataset received on 2026-09-16

- The organizer GeoJSON contains 144 features: 17 demand connection points, 88 restrictions,
  29 network sections, 9 chambers and one source.
- It materially differs from the published input table: numeric IDs, no `oks_future`, no `oks_id`,
  no existing flow/upstream links and no chamber diameters.
- Input contract v2 now has a named official contest profile with explicit actionable warnings; the strict
  official profile remains available.
- The byte-identical organizer file is now the only tracked geodata at
  `datasets/official/lct-2026.geojson`; synthetic GeoJSON fixtures and the old demo pack were
  removed. Narrow invalid-input cases are constructed inline in unit tests.
- Topology analysis now falls back to geometric source connectivity when the whole dataset omits
  upstream links, while preserving explicit-link validation for the strict profile.
- Full findings and PM questions are in `SUPPLIED_DATASET_AUDIT.md`.
- Production verification passed on the untouched file: import `valid`, 144 features, zero blocking
  errors, 76 actionable warnings. The durable topology job completed on attempt 1
  with zero issues and 204 deterministic candidates for the 17 demand points.
- Repository and production dataset cleanup is complete: the synthetic imports and the obsolete
  pre-compatibility invalid import were removed after a database backup. Production retains one
  valid import with the official SHA-256.
- Production calculation run `660c203d-287a-4fb7-bc8a-eb3debe5f1c0` completed on attempt 1 for
  all 17 demands. The independent variant validly connected 16 and preserved one explicit
  `NO_NON_CROSSING_ROUTE`; the preferred shared variant connected all 17 with 21 sections,
  3,448.671 m total length and zero validator issues.
- The official workspace now defaults to a MapLibre GL GIS view: CARTO vector basemap, calculated
  route/nodes transformed from EPSG:32637, and bounded WGS84 source layers from PostGIS. Users can
  toggle the basemap, existing heat network, restrictions and result, inspect map objects, switch
  route variants, or return to the EPSG:32637 engineering diagram. The bounded
  `GET /api/v1/official/imports/{id}/map` endpoint caps a viewport at 10,000 features and reports
  truncation. `GET /api/v1/official/runs/latest` powers the dataset-independent “open demo” action.
  Complete variants render from the strict seven-type R7 adapter; the supplied incomplete dataset
  intentionally uses the internal R4 preview because official reconstruction fields are absent.
- The result viewer now uses a map-first planning workspace: route variants and layer controls sit
  on the map, while selected-object details and route totals live in separate floating islands over
  one uninterrupted map canvas. The toolbar shows the actual imported filename and the left
  navigation collapses to an icon rail with persisted state. Framework/runtime status, theme
  controls and developer-only labels were removed from this flow; stack, version, team Dragons and
  Swagger moved to `/system`.
- The map now uses the same MapLibre renderer and vector CARTO Positron treatment as the local
  GdeBenzin project: warm background, amber road hierarchy, calm water/parks and Russian labels.
  OpenLayers and the raster OSM tile path were removed from the web dependency graph. Four
  persistent layer buttons and the map legend remain consolidated in one compact menu;
  restrictions start hidden and layer visibility changes in-place without rebuilding the map.
- MapLibre GL JS was upgraded to the patched 6.10.0 release after auditing production dependencies
  against GHSA-jrc7-96c5-q579. The Vite worker is now loaded as an explicit module worker, the
  basemap customization uses the stricter v6 style types, and CI rejects new high-severity runtime
  dependency advisories. A real-browser replay confirmed vector tiles, route overlays, all three
  variants, vertical profile, diagnostics modal and the direct official-file upload flow.

## Roadmap truth

## UX update — direct upload and diagnostics (2026-09-16)

- Valid GeoJSON uploads now start the calculation immediately. The technical import/report screen
  is skipped in the happy path; one loader covers validation, queueing and calculation until the
  map result is ready.
- The result validation metric is clickable and opens all import warnings in a scroll-contained,
  keyboard-accessible modal with code, message, object and field context.
- Enabled buttons and links expose a pointer cursor. The sidebar toggle is contained within the
  navigation rail and no longer overlaps the dataset icon in either sidebar state.
- Web verification: TypeScript, ESLint, production build and 9 Vitest tests pass. CI run
  `35089560867` passed web, Java backend and integration jobs. VPS commit `407c0a7` is healthy.

## R4/R6 obstacle-aware checkpoint (local, not deployed)

- The route planner now searches real polylines around buffered forbidden geometry instead of
  accepting straight endpoint links. Existing OKS clearance is selected from the planned DU
  (5/7/9 m), and park, social area, prohibited site, water and railway constraints participate in
  construction as hard obstacles.
- Road, tram, gas, power and independent heat-network crossings are split into reproducible
  `base`/`special` sections. The independent final validator rechecks the complete resulting
  polyline, crossing angle and unrelated constraints after path simplification.
- One official-dataset run deterministically returns `independent`, `shared` and `diverse`
  strategies. The preferred independent variant connects all 17 OКС; strategies that cannot
  connect an object without violating constraints retain the valid partial network and explicit
  `NO_NON_CROSSING_ROUTE` reason.
- R5 bottom-up sizing is now applied to every accepted new-network tree. Result edges expose
  calculated `flow_tph` and DU to the API and visual inspector. Continuous-length violations are
  preserved as explicit sizing issues; the planner does not yet increase/split DU to resolve them.
  At this historical checkpoint propagation into the existing network and reconstruction were
  still missing; the following R5 checkpoint supersedes that limitation.
- A read-only local demo API can serve the result produced directly from the sole tracked official
  GeoJSON. The UI was browser-checked at `http://localhost:5174`: all three strategies render on
  the vector map, switching works, and no console error or Vite overlay is present.
- This checkpoint has intentionally not been deployed to the VPS. Deployment is deferred until an
  explicit user command.

## R5 sizing and reconstruction checkpoint (local, not deployed)

- New-network sizing now chooses the minimum official DU satisfying both flow and uninterrupted
  length. It promotes DU when the current row's length is exhausted and never resets unchanged-DU
  length at an intermediate chamber.
- Added tie-in flow is traced through explicit `upstream_object_id` chains to a source. Multiple
  tie-ins are summed on common existing sections; `LengthIndexedLine` splits the target segment at
  the projected tie-in so only the upstream part participates.
- Existing sections and used chambers are emitted as reconstruction only when the resulting flow
  requires a DU larger than the supplied baseline. Missing direction, existing flow or chamber DU
  produces `RECONSTRUCTION_INPUT_UNAVAILABLE`; no baseline is inferred.
- The map/API expose reconstruction sections, chambers, old/new DU and added/resulting flow. The
  supplied organizer file displays one grouped Russian warning because the official contest
  profile lacks reconstruction inputs.
- Verification: 56 Java tests pass locally in Java-11 compatibility mode, including every flow and
  length boundary of all 18 catalog rows, partial tie-in, overlapping loads, chamber reconstruction
  and planner integration. Nine web tests, ESLint, TypeScript and production build pass. Browser
  smoke at `http://localhost:5174` confirms 17/17 connected OKS, three variants, zero calculation
  errors and zero console warnings/errors.

## R7 costing checkpoint (local, not deployed)

- Exact official rates now cover base/special new-network sections, reconstruction by required DU,
  new chambers in the 3/5/8/12 million bands, 5 million per independent tie-in, reconstructed used
  tie-in chambers and the per-OKS unconnected penalty.
- Every variant exposes component totals, new/reconstruction/combined length and calculated cost.
  Contract-complete variants receive official score and deterministic rank; variants whose
  reconstruction baseline is unavailable expose known cost but deliberately withhold score/rank.
- The local UI displays the known cost and explains why the final score is unavailable for the
  supplied organizer file. The checkpoint passes 59 Java tests, 10 web tests, lint, typecheck,
  production build and browser smoke without console errors.

## R7 official-output checkpoint (local, not deployed)

- A dedicated adapter serializes every complete ranked alternative into one GeoJSON containing
  only `heat_network`, `tie_in`, `heat_network_reconstruction`, `heat_chamber`,
  `heat_chamber_reconstruction`, `technical_node` and `variant_summary`.
- Output IDs are globally unique across alternatives. New-network start/end references resolve to
  scoped tie-ins, chambers or technical nodes; EPSG:32637 calculation geometry is converted to
  WGS84.
- An independent contract validator enforces exact per-type field whitelists, required scalar
  types, WGS84 geometry, unique IDs, network references and one summary per variant. Tests prove
  component-sum equality, multi-variant ID isolation and rejection of incomplete calculations.
- `GET /api/v1/official/runs/{runId}/export` returns `application/geo+json` for a complete result.
  Baseline/supplied profile may omit `existing_diameter` on a tie-in and remains exportable without
  invented reconstruction data; extended strict profile still rejects the same omission.
- The workspace exposes the download when variants have complete economics and rank.
- Complete ranked alternatives are requested with `variant_id` and rendered on the map from the
  same strict output types used by download. `variant_summary` is correctly omitted from spatial
  layers. The internal nodes/edges conversion remains only as a preview fallback for the supplied
  incomplete dataset or a transient official-layer request failure.
- Current verification after the selected-tie-in persistence checkpoint: 76 Java tests and 13 web tests, ESLint,
  TypeScript, production build and local browser smoke all pass.
- Export performs a feature-by-feature preflight and then writes with Jackson `JsonGenerator`; the
  full output tree is not retained. The all-seven-type fixture includes existing-chamber
  reconstruction. Spring MVC streaming uses a bounded 2–16 thread executor with a 64-request queue
  and 15-minute timeout instead of the unbounded fallback. The writer/validator now has measured
  500 MiB evidence on Java 11; full-calculation scale and the 50-user gate remain separate checks.

## R6 full 2D boundary matrix (local, not deployed)

- Every published 2D constraint row now has exact-value coverage plus positive, exact-boundary and
  negative behavior tests: park, social area, prohibited site, water, three OKS DU bands,
  road/tram crossings and gas/power/independent-heat-network crossings.
- A route exactly on the minimum-clearance buffer boundary is accepted, while a 0.01 m intrusion
  is rejected. The previous prepared-geometry predicate treated legal tangential contact as a
  violation; the blocked buffer now excludes only a 1 µm numerical boundary epsilon and retains
  the indexed prepared-geometry search path.
- Road and tram accept exactly 45° and reject just below it; their 3 m extensions produce a 6 m
  special span around a linear crossing. Utility crossings produce the required 2+2 m span, and
  the final validator rejects a crossing omitted from special sections.
- `railway` is tested as a supplied-dataset alias of the published `tram_tracks` special crossing.
- Full backend verification: 75 tests, zero failures; the official dataset routing case retains
  the prepared-geometry performance path.

## R4 dense-geometry lookup checkpoint (local, not deployed)

- Candidate segment checks now use an adaptive JTS `STRtree`: the small organizer dataset retains
  the lower-overhead linear prepared-geometry path, while dense constraint sets query only
  envelopes intersecting the candidate segment or navigation corridor.
- A deterministic fixture builds 1,001 constraints, proves indexed and linear decisions identical
  for blocked and clear segments, verifies that only the nearby object is returned and completes
  20,000 indexed checks inside a five-second budget.
- This closes the missing dense lookup primitive, not the geometry-complexity part of R9. Byte-size
  input/output boundaries and the exact Ubuntu 22/docker-compose 1.29.2 environment are now
  measured separately; maximum-topology route quality remains open.

## R2 deterministic replay checkpoint (local, not deployed)

- Imports are now idempotent for the same input contract and raw SHA-256. A repeated upload returns
  the existing durable import and does not reload identical features into PostGIS.
- PostgreSQL enforces the invariant with a unique `(contract_version, raw_sha256)` index;
  `INSERT ... ON CONFLICT DO NOTHING` resolves concurrent uploads without a check-then-insert race.
- Unit coverage proves both an ordinary replay and the concurrent-conflict winner path. The exact
  3 GiB streaming boundary is measured separately; representative maximum-topology work remains.

## R9 bounded-worker preparation (local, not deployed)

- Durable jobs no longer depend on one unbounded synchronous scheduler call. A dedicated executor
  runs a configurable, hard-clamped 1–16 calculations (default 2), while PostgreSQL `SKIP LOCKED`
  remains the only claim authority.
- Active jobs renew their five-minute lease every minute. Tests prove the concurrency bound and
  heartbeat behavior, preventing a long calculation from being reclaimed and executed twice.
- `scripts/r9-generate-byte-boundary.mjs` and `scripts/r9-concurrency.mjs` provide reproducible
  3 GiB transport and 50-user probes; `docs/operations/R9_ACCEPTANCE.md` states exactly what each
  probe proves and what evidence is still missing. The 3 GiB/500 MiB byte-boundary workflow passed;
  the 50-user measurement is now enforced by the clean-stack CI gate.

## R9 Ubuntu 22 / Compose 1.29.2 gate

- CI run `35110318718` passed on a clean `ubuntu-22.04` runner using the checksum-pinned official
  `docker-compose 1.29.2` binary, not the modern Compose plugin.
- The job built the pinned Java 11/PostGIS/web images from a clean checkout, applied Liquibase,
  imported the organizer file, calculated all 17 demands, validated the UI/API contracts and
  stopped the stack cleanly. VPS deployment remains intentionally unchanged.
- Run `35112362689` passed the restart-recovery assertion: after the real 17-demand calculation,
  CI restarted the API container and read the same completed result from PostgreSQL. The assertion
  remains mandatory in every integration run.
- Local 3 GiB parser preflight on commit `915d42f` passed under `-Xmx512m`: 4,308 ms and
  42,005,872 bytes reported peak heap. This is recorded in `R9_INPUT_SCALE_EVIDENCE.md`; the manual
  Java 11/Ubuntu 22 run `35111560434` also passed with 40,650,752 bytes peak heap and 357,272 KiB
  maximum process RSS.
- The extracted production stream writer and exact validator passed a local 524,781,467-byte
  output probe under the same 512 MiB heap cap in 6,386 ms. Clean Ubuntu 22 / Temurin Java 11 run
  `35112046184` repeated it in 6,942 ms with 104,260,560 bytes reported peak heap and 267,096 KiB
  maximum process RSS. The same run repeated the exact 3 GiB input probe and passed.
- Clean-stack run `35112362689` accepted 50 simultaneous organizer-file imports in 3.674 seconds;
  p95 response latency was 3,614 ms and all responses resolved to one durable import ID. This
  proves 50 concurrent public API sessions and the deduplication race, not 50 simultaneously
  executing heavy calculations. Backend, web, integration, real calculation and restart recovery
  all completed successfully in the same run. Exact evidence is in `R9_CONCURRENCY_EVIDENCE.md`.
- Final checkpoint run `35113595198` passed all Java 11, web and integration gates after adding the
  selected-tie-in persistence regression; it repeated the 50-user race, all-demand calculation and
  restart recovery successfully.
- Final depth/scale checkpoint run `35120982320` passes backend, web and clean integration gates;
  Java 11 run `35120995991` repeats the full 2× calculation in 2:14.65 with 406,608 KiB peak RSS,
  34/34 demands connected and three valid variants.
- Draft 2020-12 schemas for appendix input, the official contest dataset profile and strict
  output are versioned in `docs/contracts`, served by the Java API and compiled by NetworkNT 2.0.3.
  Contract tests validate the actual organizer file and the actual exporter result, not only hand
  written examples.
- Run `35124933139` caught a timing-dependent connection-pool starvation bug in the 50-user import
  gate. Commit `6b0ff88` now commits content-hash registration before the long feature transaction,
  keeps duplicate waiters outside database transactions and marks a rolled-back winner `failed`.
  Clean Ubuntu 22 run `35126566499` passes all 50 imports against one durable ID (p95 3,838 ms),
  the real all-demand calculation, published schema/OpenAPI checks and restart recovery. The local
  backend suite now contains 107 tests: 104 passed and three explicit scale probes skipped by
  default.
- Contest-path browser audit found two issues that isolated unit/API gates did not expose. The
  local read-only bundle had replaced the real import report with a synthetic SHA, serialized byte
  size and zero warnings; it now uses `OfficialGeoJsonInspector` over the exact organizer bytes and
  asserts 233,277 bytes, the official SHA-256 and 76 warnings. The warning dialog groups those 76
  records into three localized causes, uses readable 13–14 px text and separates input, depth and
  reconstruction diagnostics. At 1280×720 the map/profile switch had also overlapped the third
  route tab; the responsive top controls are now separated. A real Chromium smoke opens the demo,
  switches to `Альтернативные врезки`, opens the grouped modal and renders the longitudinal profile
  with no console errors or warnings.
- Clean Ubuntu 22 run `35129162919` is the release checkpoint for those contest-path fixes. Web,
  Java 11 backend and integration jobs all passed; integration repeated the 50-user import race,
  real calculation, public/internal contracts and restart recovery. The immutable organizer file
  now reproduces preferred independent 17/17, shared 16/17 and diverse 14/17; team/demo documents
  use those current figures rather than the superseded first-slice result.
- Full-story browser verification then exercised the primary local path instead of only “open
  demo” and exposed a real `405` on file upload. `local-demo-server.mjs` now accepts only the exact
  organizer bytes (size plus SHA-256), replays the completed run for that import and returns 422/413
  for unrelated or oversized data instead of showing a false result. Four Node tests protect the
  multipart parser and replay boundary. The same browser session verified both POST requests, the
  rendered 17/17 result and an error-free console. A second visual failure showed that route
  overlays waited for every remote CARTO tile; initialization now uses `style.load`, so calculated
  geometry is visible as soon as the style graph exists. A Vitest lifecycle regression protects it.
- A responsive browser pass covered 1024×768, 900×700, 640×800 and 390×844. At laptop widths the
  result island now uses a readable 2×2 metric grid; the longitudinal profile reserves that island's
  height without overlap. At 900 px and below profile mode removes the redundant overall-results
  island to keep the engineering chart usable, while map mode retains it. The mobile variant picker
  stays compact and becomes horizontally scrollable on phone widths instead of hiding the map or
  clipping route names.
- A fresh official-file browser journey then covered upload, automatic calculation, all route
  variants, structured no-route diagnostics, the depth profile, warning details, navigation collapse
  and the system-information screen. It exposed an accessibility defect in the warning dialog:
  keyboard focus remained on the map behind the overlay. The shared dialog primitive now provides
  initial focus, wraparound focus trapping, Escape handling, focus restoration and background-scroll
  locking. Variant tabs now support arrow/Home/End navigation with a single tab stop, while the
  map/schematic/profile switch publishes its selected state. Focus behavior is protected by Vitest
  and was rechecked in Chromium; the full journey produced no console errors or warnings.
- A browser fault-injection pass returned `503` for the CARTO style and proved that the previous
  map became completely blank, including calculated routes. MapLibre now starts from an inline
  engineering style, installs source/route overlays immediately and adopts the external vector
  style only after its document is available. A failed or three-second style request keeps the
  complete interactive route/network geometry visible and shows a non-blocking fallback notice;
  online mode still upgrades to the styled CARTO map. Unit coverage protects both paths, and
  desktop plus 640 px browser screenshots verified that fallback status does not collide with the
  inspector, controls or result island.

- R0 — complete: official gap audit, Java decision and team roadmap.
- R1 — complete for current single-process foundation: Java runtime, PostGIS readiness, Liquibase,
  Swagger, durable PostgreSQL job state, claim/lease/cancel/recovery, Docker and CI.
- R2 — complete for the appendix and official contest dataset contracts, including deterministic
  contract+SHA replay/deduplication, published schemas and the exact 3 GiB streaming boundary.
- R3 — functionally complete: topology validation, chamber rule, deterministic candidates, line
  splitting and adaptive dense-constraint lookup. Selected tie-in target IDs are part of every
  immutable variant and persisted in the run JSON; the full 2× scale gate passes.
- R4 — complete for the project-owned acceptance profile: immutable all-demand runs, independent/shared/diverse
  strategies, actual polyline search, simplification, partial no-route, an independent validator
  and GIS/result viewer. Dense constraint lookup is indexed and full 2× end-to-end performance is
  measured; only organizer approval of the hidden maximum profile remains external.
- R5 — functionally complete for contract-complete input: bottom-up flow/DU sizing, automatic
  continuous-length promotion, upstream propagation, partial/common-section reconstruction and
  chamber reconstruction are covered by focused tests. The supplied organizer file cannot produce
  reconstruction because its existing-network baseline and direction fields are absent.
- R6 — complete for the published mandatory 2D table: dynamic OKS buffers, hard forbidden zones,
  special crossings and final validation have exact-value and positive/boundary/negative coverage.
  The supplied `railway` value uses the complete `tram_tracks` rule; vertical
  depth rules belong to optional R8.
- R7 — complete for mandatory 2D: component costing, length, score/rank, all seven output types,
  independent validation, incremental download and official-output map rendering are integrated.
  The section 10.8 illustrative-number discrepancy is documented and the normative arithmetic is
  locked by a golden test.
- R8 — functionally complete for the published depth rules: utility crossings are projected to route chainage;
  the Java optimizer selects above/below passage on the official 0.5 m grid, creates 4 m
  plateaus and 0.10 m/m ramps, and an independent validator checks depth, slope and clearance.
  Endpoint-adjacent crossings can retain a legal selected depth at a chamber, nearby crossings
  at the same depth share one continuous profile, and tie-in egress along the connected utility
  is not misclassified as an independent crossing. The official 17-demand dataset now produces
  zero depth issues across every edge of all three variants.
  Every sized edge carries a depth profile; cost is integrated between profile breakpoints, strict
  GeoJSON exports technical nodes, `depth_start`/`depth_end` and exact XYZ axis coordinates, and
  the web workspace has a dedicated longitudinal-profile view. An impossible passage starts a
  separate XY detour and repeats sizing/profile validation; if no detour exists, the result remains
  explicitly partial with a manual-resolution issue.
- R9 — substantially closed: exact 3 GiB input and 500 MiB valid-output boundaries pass on Ubuntu
  22 / Java 11 with a 512 MiB heap; 50 concurrent API users and clean Compose 1.29.2 deployment are
  measured in CI. A full 2× supplied-geometry calculation also passes locally with 288 features,
  34 demands, 408 candidates, 116.141 seconds and 249,833,520 bytes used heap. Organizer approval of
  the maximum profile and a production-like Ubuntu 22 host rehearsal remain external acceptance
  items; the current VPS is intentionally not changed.
- The same 2× full calculation passes on clean Ubuntu 22 / Temurin 11 in run `35119722470`:
  213.308 seconds calculation time, 481,092 KiB peak RSS, 34/34 connected and three valid variants.

## Next change

Continue profiling and optimizing the 17-demand routing calculation before any further cosmetic UI
work. On 17 September the real `baseline_input` import completed successfully (144 features, 17
connection points, no blocking errors), but the background calculation remained `running` after a
12-minute manual timeout. The first optimization pass now builds each visibility graph once, limits
navigation obstacles by actual distance to the route corridor and caps the simplified convex hull
at 12 navigation vertices. Three bounded 55-second manual probes still did not complete, so the
performance gate remains open and completion time must not be claimed yet.

The next optimization pass adds admissible Euclidean lower bounds before expensive obstacle
searches. Direct assignments skip a farther candidate only after an already valid route proves it
cannot win; shared-pair candidates are skipped when even their obstacle-free lower bound cannot
beat the two independent routes or the best pair already found. These bounds do not weaken any
crossing rule or final validation and preserve deterministic tie-breaking. Final runtime measurement
is pending an explicitly requested verification run.
The routing environment now emits bounded phase diagnostics for visibility-search count, accumulated
navigation nodes and candidate edge pairs (first search, every 25 searches and each completed
variant phase). A first measured run completed in 220.4 seconds with 402 visibility searches and
40,223,610 candidate pairs, but naive vertex sampling caused unacceptable route loss. Replacing it
with a circumscribed 12-sided navigation hull restored connected boundary traversal. The final real
run `74a18b63-726a-4548-97fe-f862dfb1a9ad` completed in 118.9 seconds with 287 searches and
17,288,729 pairs. The focused obstacle-router suite passes 12/12, including a detailed 48-vertex
convex obstacle.

The two correctness gaps found by that probe are fixed. Mandatory egress rebuilding now applies
only to terminal `demand_connection` nodes, and shared junction candidates are built between the
already completed OKS egresses and rejected while they remain inside an OKS. Final supplied-file run
`cc9b8cf6-fef0-43a5-a01a-382a7093cfca` completed in 137.1 seconds. Independent/shared/diverse are
all valid and ranked, connect 14/16/9 demands, and shared is the preferred rank-1 variant. Explicit
economic exclusions remain separated from geometric no-route results. The same run exports HTTP
200 `application/geo+json`: a 169,412-byte `FeatureCollection` with 487 features across all three
variants. Baseline tie-ins without `existing_diameter` are omitted from reconstruction fields rather
than fabricated; extended strict profile retains the completeness failure. Focused suites passed
during correction (17/17 planner/export); after the final search-limit change the shared-OKS
regression passed 1/1 on the final state.

Cancellation is now cooperative inside visibility-graph construction and route search. A running
real calculation reached terminal `cancelled` state for both job and run in 0.5–0.7 seconds. Stale
requested cancellations are finalized after restart. The full-screen web state has its own cancel
button and no longer interprets a failed/missing persisted run request as an infinite calculation;
the browser returns to the workspace and exposes the actual import/API error.

The local Compose stack is now healthy at configurable host ports and builds from a checkout whose
path contains non-ASCII characters. `scripts/dev.ps1` creates an environment-relative ASCII
junction for Docker build context when required; tool caches remain checkout-local by default.
Web verification is green: 16 Vitest tests, 4 local API tests, lint and typecheck. Runtime packaging
is separated from the explicit `test` image target, so starting the service does not silently claim
that the long test gate passed.

Route-result quality follow-up on 18 September removed three misleading behaviours found in the
interactive map. Generated `technical_node` points remain selectable but are rendered as small
neutral markers instead of physical chambers. Exported intermediate IDs now use `:geometry:` for
polyline/section boundaries and reserve `:depth:` for actual depth-profile breakpoints. Failed
connections include bounded diagnostics: candidate/attempt counts, attempted target IDs, direct
blocking constraints and the maximum search corridor.

Coverage is now prioritized during generation and ranking. Candidate search falls back beyond the
nearest four only when none of them is routable; a failed first pass is retried with the failed
demands first; feasible exclusive spurs are no longer deleted merely because their construction
cost exceeds the unconnected penalty. Variant rank compares connected-demand count before the
published economic score. Numeric demand IDs use natural order (`1, 2, ... 10`) rather than
lexicographic order. The final Java 11 gate passes 136 tests with the supplied 17-demand dataset
selecting a preferred 17/17-connected variant; three explicitly gated scale tests remain skipped.
Frontend verification passes 17 Vitest and 4 local API tests, plus lint and typecheck. The processing
screen exposes cancellation immediately after `job_id` is returned, even before the first polling
response.

The 18 September building/cost correction removes the former endpoint loophole that could drop an
entire `oks` constraint whenever a route endpoint fell inside its clearance. The footprint now
remains a hard obstacle; a demand may enter only its own OKS through the terminal normal-egress
leg. Concave footprints use the first valid clearance exit and extend to the last buffered-boundary
intersection only when the minimum exit is still blocked. Shared junctions are rejected inside any
forbidden clearance, and every edge is checked and, when possible, rerouted after bottom-up sizing
with its final DU.

The focused obstacle/planner suites pass 29/29, and the five supplied-data egress regressions pass
1/1 as one grouped test. The final untouched supplied-file test passes in 468.506 seconds. All three
variants are valid: independent connects 15/17 (7,442.981 m, score 64.318630863), shared connects
17/17 (6,409.452 m, score 48.672753143) and diverse connects 13/17 (9,808.406 m, score
94.580208806). Shared is therefore the preferred full-coverage economic variant. This is a bounded
deterministic search over the implemented topologies, not a proof of the global optimum. The next
algorithmic task is reuse/caching of visibility graphs and a true multi-demand tree optimizer; the
current full-dataset runtime is not suitable for an interactive loading screen.

The next routing stage replaces greedy pair acceptance with a monetary constrained-tree search.
Shared branches are compared against their independent baseline using full marginal construction
cost (pipe diameter, branch chamber and tie-in), not geometric length alone. Candidate junctions
include the three-terminal geometric median, and a deterministic beam of up to 96 states selects
compatible branch combinations while enforcing demand exclusivity, existing-chamber capacity and
the route validator after every addition. The algorithm version is now `cost-tree-2`. This removes
the known greedy-choice defect; it remains a bounded constrained-Steiner approximation rather than
an unsupported claim of a proven global optimum. Focused and full-dataset verification is pending
because it was not requested in this implementation turn.

The following `cost-tree-3` stage removes the remaining pair-only topology limitation. For each
unassigned demand, `shared` now evaluates both a separate tie-in and attachment to an existing
branch chamber or an interior point of a built route edge. An interior attachment splits the edge
and its `RouteSection` metadata at a new chamber, adds one branch, validates the complete tree and
then performs bottom-up flow/DU sizing before comparing total new-network construction cost. This
allows third and subsequent demands to reuse one upstream trunk instead of creating parallel rays.
Only restrictions present in the imported dataset participate in routing; background-map roads are
not synthesized as constraints. A focused three-demand regression was added. Tests, full-dataset
runtime and live Compose behaviour remain unverified in this implementation turn.

`cost-tree-4` adds the local improvement pass required to reduce order-dependent parallel routes.
After the initial shared tree is built, every remaining independent root ray is temporarily removed
and evaluated as a branch of the rest of the network. The replacement is accepted only when the
complete bottom-up-sized network is cheaper, or when the rounded monetary result is equal and the
number of independent tie-in rays decreases. Every candidate still passes the tree, chamber-degree,
cycle and outside-node intersection validator. This keeps the official monetary objective primary
while preferring one reusable trunk over equivalent parallel rays. Verification remains pending.

`cost-tree-5` generalizes that pass from independent root rays to every terminal demand in the
shared forest. Each demand is detached, orphan chambers are pruned, degree-two generated chambers
are contracted with their section metadata preserved, and the demand is rerouted against the whole
remaining network. A candidate is accepted only by a strictly decreasing lexicographic objective:
full sized construction cost, tie-in ray count, total route length, then generated chamber count.
The search is deterministically bounded to two accepted relocations and, during this improvement
pass, the four nearest chambers plus the nearest projection on three route edges per demand;
it is a whole-tree local improvement, not a claim of an exact local or global Steiner optimum.
Focused and supplied-file verification remain pending.

Older `m1-evidence.md` … `m6-engineering-evidence.md` are historical prototype records only.
The current cross-check against all three organizer artifacts is in `OFFICIAL_ALIGNMENT_AUDIT.md`.

## Workstation note

Docker Desktop and the local Compose stack are operational. The default ports 5173 and 8000 were
occupied by unrelated local processes, so the verified instance uses `WEB_HOST_PORT=5174` and
`API_HOST_PORT=8080` without stopping those processes.
# 2026-09-21 — amended organizer documents and global-tree-7 (local)

- The corrected organizer GeoJSON replaced the tracked sample without normalizing IDs or geometry.
- `railway` is again a forbidden restriction with a 1 m clearance; only `tram_tracks` uses the
  special crossing rule.
- Arbitrary turns from 0 to 90 degrees no longer receive an invented 1.5 cost multiplier.
- Existing-network and existing-chamber reconstruction is excluded from calculation and export.
- Official export is reduced to `heat_network`, `heat_chamber`, `technical_node` and
  `variant_summary`; the summary now reports the existing-chamber tie-in count and cost.
- The shared-tree search is bounded before obstacle routing and publishes up to three meaningful
  balance, cost and length oriented variants.
- This contract checkpoint was subsequently covered by the focused performance result below;
  full test, lint, typecheck and smoke suites were not run.
# 2026-09-21 — routing performance recovery (local)

- Repeated empty-context route searches, including failed searches, are cached per calculation.
- Shared-pair exploration is bounded to the four closest candidates; assignment, beam and graft
  candidate counts are capped before obstacle routing rather than after it.
- Grafts are evaluated at the nearest projection and midpoint of the closest tree edges. The full
  geometry validator runs on the selected final variant instead of every simulated attachment.
- Incomplete independent/alternative drafts are no longer depth-profiled and published; the
  contract permits one to three meaningful variants.
- The focused official-dataset method completed successfully in 57.417 s with 263 visibility
  searches and 1,902,450 evaluated pairs. Before the recovery, CI exceeded 20 minutes with 1,475
  searches and about 78.8 million pairs.
- The resulting balanced variant connected 17/17 objects, measured 2,098.903 m and scored
  14.937905995. This restores the CI time budget, but route quality still needs improvement against
  the external 1.83 km / 286.2 million reference.

# 2026-09-21 — global-tree-8 geometry correction (local, recheck required)

- The planner can reuse the nearest eligible generated branch chamber instead of creating a new
  chamber for every graft; the degree-four invariant remains enforced by the structural validator.
- A failed independent ray can attach to the already built forest, so length-oriented and
  alternative-target drafts are no longer discarded solely because one separate ray crosses the
  accepted geometry.
- A connection point inside its own OKS chooses a nearby boundary side facing the candidate
  network when that side is no more than 10 m farther than the nearest boundary. The final own-OKS
  leg no longer adds the foreign-building DU clearance; all other route segments retain it.
- CI failure `#87` was diagnosed: the timed official routing method passed, while backend, web and
  integration checks still asserted the superseded reconstruction, bend, railway, export and
  variant-label contracts. The web label and integration complete-export expectations were aligned;
  remaining backend expectation updates are not claimed complete.
- One focused Java 11 run was performed after the first implementation. It failed because the final
  validator still resolved the own-OKS exemption using the old nearest-only exit, and the wider
  chamber search raised the official calculation to 148.9 s. Both causes were then changed: the
  validator uses the actual approach direction and chamber reuse is limited to the nearest chamber.
  Per the project check policy, the modified final state has not been rerun without a new explicit
  verification request.

# 2026-09-21 — amended-contract test alignment (local, verified)

- Restored the established UI variant labels: `Раздельные трассы`, `Общая сеть` and
  `Альтернативные врезки`; the internal strategies remain `shortest`, `balanced` and `cheapest`.
- Backend expectations now cover the amended contract: forbidden `railway`, no arbitrary bend
  multiplier, no existing-asset reconstruction, four exported object types, construction cost
  including chambers/tie-ins, and valid partial results with explicit `no_route` connections.
- The CI integration assertions use the same contract: one to three valid variants and a preferred
  partial result whose `no_route` count exactly accounts for every unconnected demand.
- Invalid route drafts are no longer published as official variants. A valid balanced partial
  result is retained when a higher-coverage draft violates the no-crossing invariant.
- Final Java 11 `mvn verify`: 143 tests, 0 failures, 0 errors, 3 opt-in scale tests skipped;
  `OfficialDatasetRoutingTest` completed in 152.707 s. Final web Vitest: 8 files and 18 tests
  passed. No lint, typecheck, Compose smoke or deployment was run in this checkpoint.

# 2026-09-21 — global-tree-9 coverage and constructability pass (local calculation verified)

- Every OKS keeps its nearest short boundary exit first. If that route is physically blocked even
  without already accepted branches, the planner retries with a nearby target-facing side for the
  concrete tie-in or tree junction. This restored OKS 2 and 5 without restoring a route through the
  full building footprint; already-good terminal routes retain their original short exit.
- Equal-coverage drafts are compared by the complete official score, including `no_route`
  penalties. Construction cost alone can no longer select a partial tree with a worse official
  result merely because its connected objects happen to be cheaper to build.
- A selected tree attachment is checked after bottom-up diameter sizing before it is accepted, so
  a shared trunk cannot become invalid only after the added flow increases its required clearance.
- Visibility search uses a small bounded constructability preference: straight and right-angle
  paths are preferred to chains of arbitrary oblique bends when their lengths are close. This is
  a route-search preference only and does not alter any official construction tariff.
- An expensive third full search is skipped when two distinct candidates have already been built;
  after final validation only meaningful valid variants remain publishable.
- The final local Compose calculation `6e0b759c-24ed-42a0-a2ac-04d0021b6c16` completed in 239.861 s.
  Its valid preferred `balanced` tree connects 17/17 OKS, is 2,061.412 m long, costs
  305,521,285.79 RUB, scores 14.738832002, contains 14 chambers and 27 edges, and has zero geometry
  validation issues. This improves the previous valid 17/17 checkpoint by 37.491 m, about
  3.09 million RUB and 0.199073993 score, but runtime remains above the desired target.
- The Compose build compiled main and test sources with Java 11 and skipped test execution. The
  full automated suite, lint and typecheck were not run by explicit request.

# 2026-09-21 — global-tree-10 angle and detour pass (local calculation verified)

- Visibility search now treats straight, 45-degree and 90-degree turns as the preferred
  constructible set. A conservative post-pass replaces an arbitrary elbow only when both
  replacement segments remain legal and the local length grows by no more than five percent;
  this preference does not change the official tariff.
- Whole-tree optimization expands its graft search only for a terminal branch whose routed/direct
  length ratio exceeds 1.35. The ordinary candidate limits remain unchanged, avoiding a global
  expansion for every demand.
- The existing-network tie-in rule was rechecked and fixed in regression expectations: an existing
  `heat_chamber` is reused when it is at most 10 m from the selected point on the existing network
  and will retain at most four incident linear sections; beyond 10 m the tie-in requires a new
  chamber on the existing network.
- Final local run `b969b8ed-6892-47a1-a3c6-639ef09df780` completed in 373.076 s. Its only
  meaningful `balanced` variant is valid, connects 17/17 OKS, is 2,072.949 m long, costs
  283,934,192.11 RUB, scores 14.169004379, contains 12 chambers and 26 edges, and has zero
  validation issues.
- All 42 internal route bends are within three degrees of the straight/45/90 set (zero mean
  deviation after snapping). Compared with `global-tree-9`, this removes two chambers and reduces
  cost by 21,587,093.68 RUB, but adds 11.537 m and 133.215 s.
- Two excessive detours remain (ratios 2.30 and 1.77). They are explicitly not accepted as solved:
  a future pass must optimize the selected trunk/tie-in topology rather than widen visibility
  search again. The local UI was refreshed to this run.
- Compose packaging compiled main and test sources with Java 11 and skipped test execution. The
  full automated suite, lint and typecheck were not run by explicit request.

# 2026-09-23 — global-tree-40 geometry-first selection without roads (local diagnostic)

- Engineering portfolio selection now orders candidates by expert-rule compliance before bend
  count: out-of-range 90–135-degree bends, sub-2 m bend spacing and total range deviation are
  considered before canonical 90/135-degree preference, chamber count, final construction cost
  and length. Cost and length remain bounded by the existing 5% corridor with one relaxation to
  10%, so constructability cannot select an unbounded detour.
- The evaluator now includes angles where separate route edges meet at the same topology node.
  Degree-two nodes use the expert bend rule; branch chambers receive a separate soft diagnostic
  for deviation from 45/90/135/180-degree ray relationships. This closes the previous blind spot
  where visually irregular chamber stars did not affect portfolio ranking.
- Focused Java tests were added for canonical-angle preference and multi-edge junction evaluation,
  but were not executed by explicit request. Compose packaging compiled main and test sources with
  Java 11 and skipped test execution.
- The ordinary corrected dataset was calculated without road polygons in run
  `d944ea70-aeb3-4503-bf07-36f663c61298`. All three official-valid variants connect 17/17 OKS:
  engineering 1,791.020 m / 269,936,197.31 RUB, shortest 1,785.250 m / 274,070,570.37 RUB, and
  cheapest 1,762.700 m / 266,995,620.48 RUB. Engineering and shortest still expose five
  out-of-range bends; the result is numerically unchanged from the preceding no-road checkpoint.
- This proves ranking alone is insufficient because the bounded portfolio does not yet contain a
  better legal geometry for the four affected branches. The 1,361-second runtime also misses the
  ten-minute iteration target: 1,640 visibility searches and about 12.33 million evaluated pairs
  complete before final engineering repair. The next gate is bounded branch/egress candidate
  generation for those four edges plus early termination of non-improving global repairs.

# 2026-09-23 — road-corridor experiment restored on current planner (`global-tree-39`, local)

- The unchanged `global-tree-38` planner was rerun against the opt-in corrected dataset enriched
  with 94 OSM road polygons. Run `14b07065-698d-44aa-8ac5-e1d7874ebf8a` spent 1,471 seconds in
  3,454 visibility searches / roughly 58.1 million evaluated pairs and published zero variants.
- The failure was structural: a shallow road crossing is rejected by the official 45-degree rule,
  while the current visibility graph only supplied navigation vertices for forbidden polygons.
  Therefore it had no nodes from which to construct a legal alternative road crossing.
- `global-tree-39` restores two bounded navigation portals immediately outside an intersected
  polygonal road/tram object, aligned with the normal of its minimum rectangle. Roads remain an
  opt-in experiment and retain the official crossing validation and special-section economics.
- A focused regression case was added for a shallow direct road crossing that must be replaced by
  a perpendicular crossing. Road-enriched run `d9cd8766-1ce8-4ad7-a973-931fcb1cbca8` then
  published three valid 17/17 variants after 1,441 seconds. Engineering/shortest are identical at
  2,099.278 m / 316,418,078.53 RUB with eleven expert-angle findings; cheapest is 2,008.104 m /
  307,270,096.16 RUB. All variants contain 12 road special sections and one fewer chamber than the
  no-road control, but are materially longer, costlier and less constructible. The polygon-road
  visibility experiment is therefore diagnostic only and does not replace the corrected-dataset
  default. Automated tests were not run by request; Docker packaging compiled main and test
  sources with test execution skipped.

# 2026-09-23 — global-tree-38 bounded engineering repair and honest diagnostics

- Engineering regularization is now applied to the selected engineering and shortest drafts in
  the normal portfolio path, rather than only when objective selection returns no draft. On the
  corrected dataset this reduced the expert-angle findings from eight to five while preserving
  three official-valid 17/17 alternatives.
- Directional OKS egress is capped relative to the nearest legal wall exit. A target on the
  opposite side can no longer make a connection traverse the full building merely because the
  globally configured alternate-egress allowance is large.
- A broader rotated-dogleg experiment reduced five angle findings to four but took 1,348.5 s in
  the focused official-dataset scenario and still failed the zero-warning criterion. It was
  removed from the active algorithm instead of shipping a slower incomplete repair.
- Focused verification passes 35/35 tests across `EngineeringRouteEvaluatorTest`,
  `OfficialObstacleRouterTest` and `OfficialRoutePlannerTest`. The official-dataset regression
  now requires exactly three variants and 17/17 connections; the attempted zero-warning assertion
  correctly failed and remains an open topology-rebuild gate rather than being hidden.
- The last published comparison run before removing the rejected dogleg is
  `3ece314a-651c-4e8b-9231-dead14021fca` (`global-tree-36`): all three variants are official-valid
  and connect 17/17, but engineering/shortest still report five out-of-range bends. Runtime was
  960.410 s, so both expert-angle completion and the approximate ten-minute iteration target
  remain open. Full tests, lint and typecheck were not run by explicit request.

# 2026-09-23 — global-tree-34 variant publication and calculation-summary correction

- All official-valid `engineering`, `shortest` and `cheapest` representatives are published and
  ranked. Expert 90–135-degree and 2 m bend findings remain attached to engineering/shortest as
  `engineering_issues` warnings instead of deleting those alternatives from the response.
- The web workspace localizes and exposes those warnings in the inspector and validation dialog.
  The bottom result cards now describe the selected variant itself: actual network length,
  connected OKS count and cost; they no longer substitute absent sibling strategies with zeros.
- Focused web verification passed: `RouteVisualization.test.tsx`, 3/3 tests. Focused Java
  verification passed 18/18 tests in `EngineeringRouteEvaluatorTest` and
  `OfficialRoutePlannerTest`; stale index-based expectations were aligned with the three-strategy
  contract. Full tests, lint and typecheck were not run by the user's explicit scope limitation.
- This restores comparison visibility but does not declare the expert geometry gate complete.
  Corrected-dataset engineering/shortest warnings still identify the subtrees that need topology-
  level rebuilding; the official validator remains the blocking publication boundary.
- Live run `dcfcfe45-3c2a-4ad1-a8a7-b58b5dffba4d` completed in 781.996 s on the corrected import.
  All three variants are official-valid and connect 17/17 OKS. Engineering: 1,704.185 m,
  260,010,070.50 RUB, score 12.392836974, eight expert-angle findings. Shortest after the required
  engineering normalization: 1,698.415 m, 264,144,443.56 RUB, score 12.491289420, the same eight
  expert-angle findings. Cheapest under the official-TZ-only geometry rules: 1,689.315 m,
  258,272,510.65 RUB, score 12.299575298, and no engineering-warning contract applies to it.
- The rebuilt local Compose stack is healthy on `http://127.0.0.1:5174/`; the latest calculation
  action visibly loads all three tabs and the selected-variant drawer reports 1.69 km / 17 of 17
  instead of `0 m / 0 OKS`.

# 2026-09-22 — global-tree-33 expert geometry and bounded objective portfolio

- The calculation keeps independent, shared-tree and cheapest-with-existing-chamber-reuse drafts
  as a bounded portfolio instead of deriving all three UI choices from one winner. The shortest
  representative is selected by total routed length, the cheapest by the official construction
  economics, and the engineering representative inside a five-percent cost/length corridor that
  is relaxed once to ten percent when necessary.
- The domain expert's additional constructability rule is enforced on the finished geometry for
  the engineering and shortest strategies: internal bend angles must be 90–135 degrees and two
  consecutive bends must be at least 2 m apart. The cheapest strategy deliberately retains only
  the official-TZ geometry rules. A final bounded normalization pass removes safe shallow kinks or
  replaces them with legal 90/135-degree elbows after tree grafting, chamber contraction, sizing
  and mandatory OKS egress have finished.
- Building polygons remain mandatory obstacles with the official 5 m clearance. A 1.5 m roadway
  offset is enforced when roadway geometry is supplied in the official input. The corrected
  dataset contains no roadway features, so the algorithm does not infer legal engineering
  corridors from the visual basemap.
- UI strategy names are now `Инженерная трасса`, `Самый короткий` and `Самый дешёвый`; the
  explanatory copy states the different optimization priorities.
- Docker packaging compiled Java main and test sources with test execution skipped. The focused
  evaluator tests were added but were not run, and the full automated suite, lint, typecheck and
  browser smoke remain deferred by explicit request.
- Final corrected-dataset run `d6a8b187-a14f-4638-a39f-5212a6473166` completed in 725.214 s.
  The cheapest official-TZ representative is valid at 17/17 OKS, 1,689.315 m,
  258,272,510.65 RUB, score 12.299575298, 29 edges and 33 nodes. The two strict representatives
  were intentionally withheld: final local normalization reduced their non-compliant bends from
  14 to 8, with zero sub-2 m bend pairs, but could not remove the remaining bends without entering
  an official clearance zone. Their next gate is bounded subtree rebuilding rather than another
  point-level corner adjustment.

# 2026-09-22 — objective-specific variants and optional existing-chamber reuse (`global-tree-29`, local)

- The expensive valid merged-chamber candidates are now routed once and used to select separate
  length and construction-cost winners. The shortest draft is compared against the independent
  topology, while the cheapest draft is compared against the balanced topology by fully sized
  construction cost; a longer draft is therefore allowed to win the cheapest objective.
- The cheapest-only post-pass can replace a single-ray new tie-in chamber with a connected
  existing chamber up to 60 m away. It considers the bounded incident network directions at a
  junction chamber, preserves the maximum four sections, and accepts a replacement only after
  full geometry validation and a strict reduction of the final construction cost.
- Corrected-dataset run `0e3853ec-8af9-41e5-8a5a-e9adfe323e09` completed in 710.374 seconds.
  All three variants are valid and connect 17/17 OKS: balanced is 1,761.086 m / 271,453,650.54
  RUB, shortest is 1,759.014 m / 268,589,256.36 RUB, and cheapest is 1,765.990 m /
  264,238,637.23 RUB. The cheapest route is 6.976 m longer than the shortest but 4,350,619.13
  RUB cheaper.
- The cheapest route reuses existing chamber `106` instead of constructing a new chamber on
  segment `126`, reducing generated chamber cost from 53 million RUB in the shortest variant to
  48 million RUB. Chamber `107` was evaluated through each incident network direction but no
  fully valid cheaper route was found, so the upper-left building remains on its segment tie-in.
- Docker packaging compiled main and test sources with Java 11 and skipped test execution. Full
  automated tests, lint and typecheck were not run; two corrected-dataset calculations verified
  the intermediate and final local states. The remaining runtime target is approximately ten
  minutes, with the base shared-tree visibility phase now the dominant optimization target.

# 2026-09-22 — final-tree tie-in relocation (`global-tree-28`, local)

- A new tie-in selected for the initial shared pair is no longer frozen after the rest of the
  tree is grafted. For every single-ray `new_tie_in_chamber`, the planner projects the completed
  adjacent trunk back onto the same existing-network feature and evaluates that nearer point.
- The replacement keeps the mandatory 10 m existing-chamber reuse zone, a perpendicular four-
  metre entry relative to the existing-network tangent, and a 45/90/135/180-degree final ray at
  the generated tree chamber. It is accepted only after complete geometry validation and when
  final construction cost, or equal-cost length, improves.
- On corrected-dataset run `6a9fe026-ca3b-4cbf-8c74-bbcf6834604d`, target segment `126` moved
  from `(414445.482, 6173320.717)` to `(414430.807, 6173282.893)`. Its root edge fell from
  48.886 m to 18.090 m and retained three base legs: 4.000 m perpendicular entry, 10.090 m
  connecting leg and 4.000 m constructible chamber approach.
- All three published variants remain valid and connect 17/17 OKS. Balance is 1,761.086 m /
  271,453,650.54 RUB / score 12.883960215; shortest is 1,759.014 m / 268,589,256.36 RUB /
  score 12.797541178; cheapest is 1,759.191 m / 268,218,637.65 RUB / score 12.787694854.
  Against `global-tree-26`, each variant is 30.796 m shorter and 4,620,077.51 RUB cheaper.
- The full run took about 830.963 seconds. Docker packaging compiled main and test sources with
  Java 11 and skipped test execution; automated tests, lint and typecheck were not run.

# 2026-09-22 — adjacent-chamber group merge experiment (local, no preferred-route change)

- `global-tree-20`–`global-tree-23` add a generic whole-tree operator that finds two short-linked
  degree-three generated chambers, proposes one degree-four chamber at their endpoints, midpoint
  or outer-branch line intersections, and reroutes all four external branches as one group.
- Demand branches select the side of their OKS facing the proposed street junction. A non-demand
  trunk may leave an existing topology node without treating the other old edges incident to that
  same node as obstacles; the final crossing, clearance, tree and economics checks still run on
  the complete rebuilt draft.
- The corrected-dataset run `f7b5dc2b-b582-44dc-9e3a-377535585988` completed valid at 17/17 OKS,
  1,864.356 m, 288,191,073.55 RUB, score 13.662418059, 13 generated chambers and 30 edges. No merge
  candidate improved the complete official score, so the preferred route is geometrically and
  economically unchanged from `global-tree-19`.
- The requested vertical common trunk cannot be obtained reliably by contracting two chambers
  after the surrounding branches have already been routed. The next gate must generate this
  intersection-centred trunk topology before terminal grafting, keep it as a competing complete
  draft, and compare it with the accepted economical control after sizing and final validation.
- Docker packaging compiled main and test sources with tests skipped. Three live calculations were
  used to isolate the rejection stage and measure the final variant; full tests, lint and typecheck
  were not run by explicit request.

# 2026-09-22 — competing early topology seeds (`global-tree-24`, local experiment)

- The shared-tree constructor now keeps up to four different pair-based common-trunk seeds,
  completes every seed to a full 17-demand tree, compares complete official economics, and runs
  the expensive whole-tree improvement only for the winning completed draft.
- Corrected-dataset run `a94db9b1-bf67-453e-beba-ac7bcf57303f` remained valid at 17/17 but
  selected exactly the previous geometry: 1,864.356 m, 288,191,073.55 RUB, score 13.662418059,
  13 generated chambers, 30 edges and zero validation issues.
- Runtime increased to 1,315.076 seconds without a quality gain. Pair-seed diversity alone cannot
  produce the requested intersection-centred vertical trunk; that topology needs an explicit
  multi-terminal trunk seed rather than another ordering of the same pair-and-graft operations.
- Docker packaging compiled main and test sources with tests skipped. One live corrected-dataset
  calculation and the junction diagnostic were run; full tests, lint and typecheck were not run.

# 2026-09-22 — three valid objectives and merged chambers (`global-tree-26`, local)

- The planner again publishes up to three distinct valid variants: 70/30 balance, shortest, and
  cheapest. A valid chamber-group replacement is retained for the latter objectives even when it
  would not replace the current control during a local score-improvement pass.
- Replacement branches that terminate at the same proposed chamber no longer treat one another
  as obstacles at that common endpoint. The completed draft is still rejected for any overlap,
  crossing, forbidden clearance, degree, tree, sizing or economics violation elsewhere.
- Corrected-dataset run `7cba3213-4d79-44f2-b6e5-995fc7fb5806` produced three valid 17/17 trees:
  balance at 1,791.882 m / 276,073,728.05 RUB / score 13.105710385 / 12 generated chambers;
  shortest at 1,789.810 m / 273,209,333.87 RUB / score 13.019291348 / 11 chambers; and cheapest
  at 1,789.987 m / 272,838,715.16 RUB / score 13.009445024 / 11 chambers. The latter two contain
  two merged chamber nodes each; every variant has zero validation issues.
- Runtime fell from 1,315.076 to 1,143.256 seconds after removing four full early-tree builds, but
  remains unacceptable. Shortest and cheapest currently rerun the same local merge candidates;
  caching one evaluated candidate set for both objectives is the next bounded performance step.
- Direct Docker builds compiled Java main/test sources and the TypeScript production bundle;
  backend tests were skipped and full tests, lint and standalone typecheck were not run.

# 2026-09-21 — experimental OSM road-corridor routing (local verified)

- OpenStreetMap road centrelines in the active network/OKS window are converted to official
  `restriction_type=road` polygons in an ignored local experiment dataset. Service drives and
  roads outside the routing window are excluded; the current experiment adds 94 road polygons.
- The unchanged router completed the road-enriched run but produced no complete variant (12/17
  connected). It could validate a crossing angle but had no navigation vertices from which to
  construct a legal alternative crossing.
- The visibility graph now adds two points immediately outside an intersected road polygon on a
  line normal to the polygon's main axis. This supplies an explicit 90-degree crossing candidate
  while retaining the published 45-degree minimum as the hard rule.
- The repeated run `26bfc22b-40fb-42db-86ff-951c36288ac0` is valid and connects 17/17 at
  2,071.836 m, 305,170,558.59 RUB and score 14.760283641, with 11 chambers, 25 edges and zero
  validation issues. Runtime was 691 s. This is 131.216 m and 37,788,333.24 RUB worse than the
  no-road `global-tree-12` checkpoint, so the road layer remains an opt-in experiment rather than
  the default official calculation.
- Compose packaging compiled main and test sources with Java 11 and skipped test execution.
  Automated tests, lint and typecheck remain deferred by explicit request.

# 2026-09-21 — global-tree-13 perpendicular graft approach (locally verified, ineffective)

- The OSM road enrichment was rejected as the default: although it produced a valid 17/17 tree,
  it was 131.216 m longer, 37,788,333.24 RUB costlier and slower than the corrected-dataset
  checkpoint. Road polygons and crossing portals are not used by the default calculation.
- A branch grafted into the generated tree now tries a four-metre perpendicular final approach to
  the supporting trunk. The constructible branch replaces the shortest branch only when the full
  path remains legal and its local length grows by no more than five percent.
- This stage targets acute and arbitrary junction entries without hard-coding coordinates or
  changing the official cost model. Compose compilation succeeded with tests skipped. Corrected-
  dataset run `26294ae1-ad99-4aa9-aa31-92a4a12d47ca` completed valid at 17/17 OKS,
  1,940.620 m, 267,382,225.35 RUB, score 13.30856231, 11 chambers and 25 edges in about
  509.896 seconds.
- Exact comparison with the preceding no-road run found 0 changed geometries out of 25 edges and
  identical metrics. The four-metre local suffix therefore does not improve this dataset: the next
  optimization gate must relocate junctions or reconnect whole subtrees instead of adding a bend
  immediately before a fixed junction. Automated tests, lint and typecheck remain deferred by
  explicit request.

# 2026-09-21 — bounded junction relocation experiment (rejected)

- Experimental `global-tree-14` through `global-tree-16` moved terminal graft chambers by bounded
  offsets along their supporting trunk and evaluated candidate drafts with the official score.
- The approach remained valid at 17/17 OKS but regressed the corrected dataset. The two material
  results were 1,951.141 m / 272,437,673.30 RUB / score 13.481677852 and 1,952.772 m /
  273,803,205.00 RUB / score 13.524805740, versus the accepted `global-tree-13` checkpoint at
  1,940.620 m / 267,382,225.35 RUB / score 13.308562310.
- The exact final-economics guard exposed that comparing against the raw pre-optimization tree is
  insufficient: the correct control is the completed legacy whole-tree pass. The guarded run also
  took about 659.931 seconds. The experiment was removed from the default algorithm rather than
  shipping a slower and worse route.
- The next topology gate must compare a jointly rebuilt branch group against the completed control
  tree, not relocate one terminal attachment at a time. No automated tests, lint or typecheck were
  run; Compose compilation and live corrected-dataset calculations were used for this experiment.

# 2026-09-22 — fourth-ray constructability experiment (local, rejected as preferred route)

- The highlighted `junction:6:8` had four incident routes. One late tree attachment entered at
  non-constructible directions (about 35/124/159 degrees relative to the existing rays), while one
  of its incident branches had a routed/direct ratio of 1.53.
- `global-tree-17` first preferred a perpendicular three-terminal seed and rejected a later fourth
  ray unless all new incident angles were within 7.5 degrees of 45/90/135/180. It reduced the
  diagnostic count of non-preferred chamber-angle pairs from 26 to 8, but produced 1,943.662 m,
  291,830,984.20 RUB, score 14.002253558 and 12 generated chambers. The cost regression is not
  acceptable for the preferred 70/30 objective.
- `global-tree-18` removed the forced seed preference; `global-tree-19` further limited the rule to
  the fourth ray only. Both final calculations were identical: valid 17/17 at 1,864.356 m,
  288,191,073.55 RUB, score 13.662418059, 13 generated chambers and 30 edges. They are 76.264 m
  shorter than the accepted `global-tree-13` checkpoint but 20,808,848.20 RUB more expensive and
  therefore worse by the official score.
- The local UI was refreshed to `global-tree-19` for visual comparison. This experiment is not an
  accepted replacement for `global-tree-13` and must not be pushed as the preferred algorithm.
  The next implementation must preserve both complete candidates and expose the shorter,
  constructible tree separately, or rebuild the complete four-terminal branch group and accept it
  only after final economics rather than forcing a local attachment decision.
- Docker packaging compiled main and test sources with Java 11 and skipped test execution. Three
  live corrected-dataset calculations were performed; full tests, lint and typecheck were not run.

# 2026-09-23 — CI runtime allowance and stale expectation alignment

- The first post-push workflow was cancelled by the configured higher-priority `master` run; it
  did not represent missing source files or a failed push. In the final run, `web` passed, while
  `integration` exhausted its 360-second polling window with the official job still running and
  `backend` later exposed two stale assertions.
- The official-dataset backend test completed its calculation in about 841 seconds. Bounded search
  reductions did not materially lower the visibility workload and degraded the published route
  geometry, length and expert-angle diagnostics, so those algorithm changes were rejected and the
  previous planner behavior was restored.
- Dynamic obstacle-route results are now cached only under a key containing the complete accepted
  route and additional-constraint geometry. This retains repeated constrained-search reuse without
  sharing a result between different avoidance contexts or relaxing any official/expert rule.
- The integration workflow first demonstrated that 900 seconds was still insufficient on the
  shared runner: the job remained in `running` state at the deadline, while the parallel backend
  and web jobs passed. Its allowance is therefore 1,200 seconds, while terminal job errors still
  fail immediately. This is a CI-harness allowance, not a performance acceptance claim; reducing
  official-dataset runtime remains open.
- Export expectations now account for the variant-scoped generated technical node in each
  published route. The road-crossing test now accepts the shorter legal 45-degree portal because
  the official rule requires a minimum crossing angle of 45 degrees rather than a mandatory
  perpendicular crossing.
- Focused Java verification ran `OfficialGeoJsonExporterTest` and `OfficialObstacleRouterTest`:
  26 tests passed, with zero failures or errors. The full official-dataset suite, frontend checks
  and live Compose smoke were not repeated on this final state.

# 2026-09-21 — global-tree-12 alternative tie-in and chamber approach pass (local verified)

- A segment whose nearest tie-in is within 10 m of an existing chamber now contributes a bounded
  alternative point outside that 10 m reuse zone. This permits a new chamber farther along the
  same existing-network segment when the mandatory existing chamber would force a long obstacle
  detour; the point is selected by the optimizer and is not dataset-specific.
- A route into an existing or new tie-in chamber first attempts a four-metre final leg
  perpendicular to the local existing-network tangent. This is a constructability preference:
  the reviewed public rules require a safe sealed wall penetration but do not establish a general
  statutory 90-degree angle for the point-only chamber model. The ordinary legal approach remains
  a fallback when a perpendicular leg is blocked.
- A terminal route with routed/direct ratio above 1.35 may retry the nearby target-facing side of
  its own OKS. The alternate egress is accepted only when it is legal and strictly shorter; this
  removed the 60.230 m hairpin whose endpoints were only 14.744 m apart.
- Final local run `0c84f5b4-1e40-4493-bc66-7e43e76d957c` completed in 486.077 s. Its valid
  `balanced` variant connects 17/17 OKS, is 1,940.620 m long, costs 267,382,225.35 RUB, scores
  13.308562310, contains 11 chambers and 25 edges, and has zero validation issues.
- Compared with `global-tree-10`, the network is 132.329 m shorter, 16,551,966.76 RUB cheaper,
  and uses one fewer chamber and edge. Every generated chamber has only straight/right-angle
  incident directions in the point topology check.
- The previously highlighted round-building branch is shorter, but still has a 1.89 detour ratio;
  it is improved, not closed. Runtime also regressed to 486.077 s. The next gate is a strict-budget
  reuse/cache of alternative tie-in searches followed by a topology-level repair of this remaining
  branch.
- Compose packaging compiled main and test sources with Java 11 and skipped test execution. The
  full automated suite, lint and typecheck were not run by explicit request.

# 2026-09-24 — distinct map point roles (implementation only)

- The route map now assigns stable visual roles to demand connections, new chambers, tie-ins,
  existing chambers, reconstruction chambers and technical geometry vertices for both preview and
  strict official-output data.
- A persistent map legend and inspector role label make physical facilities visually distinct from
  generated export vertices. Technical nodes remain selectable but small and grey; routing,
  geometry, economics and official export content are unchanged.
- The legend is placed beside the left-side map controls in the full-screen workspace so the
  results inspector cannot cover it, and carries an explicit `Обозначения` heading.
- Strict-output root IDs with the `:tie:` role are now displayed as orange tie-ins instead of grey
  technical vertices. All calculated markers remain compact and close to the route-line width;
  their role is communicated by colour rather than oversized symbols.
- Focused frontend assertions were extended for strict-output role classification. Tests, lint,
  typecheck, build and browser smoke were not run for this implementation-only change.

# 2026-09-24 — remove algorithm-only export points (implementation only)

- Geometry-only route vertices are retained as coordinates inside each exported `heat_network`
  LineString instead of being emitted as standalone `technical_node` point features.
- Technical nodes remain at real topology endpoints and at section/depth boundaries where route
  properties change, preserving reference integrity and useful engineering semantics.
- Strict-output map classification now recognizes a tie-in only when the local node ID itself is a
  tie-in root. An inherited `:tie:` fragment inside a generated edge ID no longer recolours a
  technical node as a physical connection.
- The point legend was moved into the collapsible layer panel so it is hidden while that panel is
  closed and no longer occupies the map canvas permanently.
- The exporter aggregation keeps original polyline coordinates and sums the same per-segment
  lengths and costs into fewer output features. Focused backend/frontend expectations were updated,
  but tests, build, lint, typecheck, live export and browser smoke were not run.

## Live baseline evidence

- Rebuilt the local API/web images with Maven test execution skipped and opened the canonical
  144-feature corrected competition dataset without the experimental roads or kindergarten area.
- Run `15a4b2a6-8891-42b6-9d85-1d0007b7fd35` completed in 439.666 seconds and connected 17/17
  demands in all three valid variants. Shortest is 1,857.155 m / RUB 273,953,260.00; cheapest is
  1,895.501 m / RUB 272,450,601.54; balanced is 1,928.150 m / RUB 286,238,609.32.
- All three strict exports succeeded. Balanced/shortest/cheapest contain respectively 32/30/28
  `heat_network` features, 15/13/11 new chambers and exactly 18 `technical_node` features: 17
  demand endpoints plus one physical tie-in root. Generated geometry/section technical nodes are
  zero in every variant.
- The in-app browser was switched to the new baseline run with the shortest variant selected. The
  point legend is visible only inside the expanded layer panel. Automated tests, lint and
  typecheck were not run.

# 2026-09-24 — road and kindergarten social-area experiment (`global-tree-61`, local)

- A separate ignored experiment dataset keeps the corrected 144 source objects, adds the existing
  94 OSM-derived `restriction_type=road` polygons and one `social_area` for the building served by
  connection point 11 at улица Родченко, дом 1. A narrow service approach remains outside the
  forbidden social polygon so the experiment measures obstacle routing instead of making the
  destination itself unreachable. The ordinary corrected dataset is unchanged.
- Run `f6afcce2-d6f1-4b8a-81a4-9c10df9225d1` completed in 2,008.615 seconds and published three
  official-valid 17/17 variants with no validation or engineering issues. Engineering is
  1,860.391 m / 288,150,035.15 RUB; shortest is 1,856.603 m / 284,834,678.95 RUB; cheapest is
  1,880.547 m / 280,703,240.09 RUB and remains the preferred score winner.
- The local browser now displays this run with the restrictions layer enabled and the kindergarten
  social polygon selected. The experiment is useful for geometry review but exceeds both the six-
  minute target and the earlier ten-minute development ceiling; full OSM road polygons therefore
  remain opt-in diagnostic input rather than the default routing dataset.
- No automated tests, lint, typecheck or build were run. The explicitly requested verification was
  one live local import/calculation and browser inspection of the published result.

# 2026-09-24 — square kindergarten territory and safe road fast path (`global-tree-62`, local)

- The opt-in experiment now models the kindergarten as a clean rectangular `social_area` matching
  the lighter cadastral territory. A terminal inside its own social polygon receives a single
  straight egress through that exact territory; the same polygon remains forbidden to every other
  branch, and all foreign social areas keep their ordinary clearance.
- A direct segment that intersects only a shallow road/tram polygon now tries perpendicular entry
  and exit portals before constructing the full visibility graph. The candidate is still checked
  against every active restriction; parks, foreign social areas, buildings and complex crossings
  continue through the complete obstacle search.
- Run `2217cd3e-ea31-4af8-a373-26ec0f6d9c05` completed in 1,041.600 seconds versus 2,008.615
  seconds for the previous road/social diagnostic (48.1% faster). It published three complete
  17/17 variants with zero validation, engineering or sizing issues: balanced 1,877.566 m /
  283,714,352.13 RUB; shortest 1,864.361 m / 283,597,161.22 RUB; cheapest 1,950.225 m /
  283,118,693.11 RUB.
- The measured improvement does not meet the six-minute target or the ten-minute development
  ceiling. The independent phase fell from roughly 15:26 to 8:01; repeated shared-network searches
  and candidate validation are now the dominant optimization targets. No restriction was relaxed
  to obtain this speed-up.
- The API image was rebuilt with Maven test execution skipped and the final Compose service became
  healthy. Automated tests, lint and typecheck were not run; one final live local calculation was
  performed on the rebuilt service.

# 2026-09-24 — separate downloads for calculated variants (verified locally)

- The results toolbar no longer requests the run-wide export that combines all ranked variants.
  Its export menu exposes one button per calculated variant and always supplies that variant's
  `variant_id` to the existing strict export endpoint.
- `Скачать все` downloads the same exportable variants sequentially as separate, uniquely named
  GeoJSON files instead of merging them into one collection. It is enabled only when every
  calculated variant is complete, valid, ranked and costed.
- The backend export format and routing/economics are unchanged. The final local web gate passes:
  8 Vitest files / 36 tests, 35 script tests, ESLint, TypeScript and the production Vite build.
  Browser smoke will be covered by the production deployment verification below.

# 2026-09-24 — CI repair after `06f3f39` (verified locally)

- Terminal egress exemptions are now keyed by both feature identity and constraint role: the own
  building exemption applies only to its `oks` constraint and a containing social parcel exemption
  only to its `social_area` constraint. A foreign park or other obstacle with the same source ID is
  no longer removed from route or final-validator checks.
- Two backend assertions now follow the declared `STABLE_ALGORITHM_VERSION` instead of freezing the
  superseded `global-tree-61` label. The route-map lifecycle assertion was aligned with the current
  compact point-role styling; neither adjustment relaxes routing or export requirements.
- Run `ci #98` reached GitHub and integration passed, but backend failed four assertions and web
  failed one stale styling assertion. The repaired final state passes the full Java 11 gate:
  750 tests, zero failures/errors and three skipped scale tests. The web gate passes 36 Vitest
  tests, 35 script tests, ESLint, TypeScript and the production build. GitHub CI and live deployment
  remain pending until this exact commit is pushed.

# 2026-09-24 — `69c5e16` CI recovery and production deployment

- Commit `69c5e160e673d32a082adc68b1c0fd7839fb1749` reached `origin/master` exactly. GitHub Actions
  run `ci #99` passed all jobs: web in 1:01, backend in 8:23 and the Ubuntu 22 integration gate in
  9:31, including 50 concurrent imports, public/internal contracts and restart recovery.
- Before deployment, the live PostgreSQL database was backed up to
  `/opt/heatroute/backups/heatroute-20260924T143943Z-06f3f391c0ef060efa21d533e3441c7d543e90c82.dump`
  (713,784 bytes, mode 600). The clean VPS checkout was fast-forwarded and the API/web images were
  rebuilt; db, api, web and gateway all became healthy.
- Readiness, OpenAPI and public HTTPS checks pass. A fresh import
  `f92e4ae2-fd49-47a4-a131-bdeecadef086` is `valid`: 144 features, zero errors and 76 advisory
  warnings. No production routing job was started as part of this deployment smoke.
