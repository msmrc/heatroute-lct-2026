import { Braces, Boxes, Code2, Database, ExternalLink, GitBranch, Map } from "lucide-react";

import { Card } from "../components/ui/primitives";

const PRODUCT_VERSION = "0.1.0";

const stack = [
  { icon: Code2, name: "Серверная часть", value: "Java 11 · Spring Boot 2.6.3" },
  { icon: Database, name: "Хранение и геоданные", value: "PostgreSQL · PostGIS · Liquibase" },
  { icon: Map, name: "Интерфейс и карта", value: "React · TypeScript · MapLibre GL" },
  { icon: Boxes, name: "Развёртывание", value: "Docker Compose · Caddy · Nginx" },
];

export function SystemInfoPage() {
  return (
    <div className="page system-page">
      <header className="page-heading">
        <div>
          <h1>Системная информация</h1>
          <p>Сведения о продукте, версии и используемых технологиях.</p>
        </div>
      </header>

      <section className="system-overview" aria-label="О продукте">
        <Card className="system-product-card">
          <div className="system-product-mark" aria-hidden="true">H</div>
          <div>
            <span>HeatRoute</span>
            <h2>Проектирование маршрутов теплоснабжения</h2>
            <p>Проверка геоданных, расчёт вариантов подключения и визуальный анализ маршрутов на карте.</p>
          </div>
          <dl>
            <div><dt>Версия</dt><dd>{PRODUCT_VERSION}</dd></div>
            <div><dt>Команда</dt><dd>Dragons</dd></div>
          </dl>
        </Card>
      </section>

      <section className="system-grid">
        <Card className="system-section">
          <header>
            <GitBranch size={19} />
            <div><h2>Технологии</h2><p>Основные компоненты приложения</p></div>
          </header>
          <div className="system-stack-list">
            {stack.map(({ icon: Icon, name, value }) => (
              <div key={name}>
                <span className="system-row-icon"><Icon size={17} /></span>
                <span><strong>{name}</strong><small>{value}</small></span>
              </div>
            ))}
          </div>
        </Card>

        <Card className="system-section system-links">
          <header>
            <Braces size={19} />
            <div><h2>Для разработчиков</h2><p>Контракты и документация API</p></div>
          </header>
          <a href="/api/v1/swagger-ui.html" target="_blank" rel="noreferrer">
            <span><strong>Swagger API</strong><small>Интерактивная документация сервиса</small></span>
            <ExternalLink size={17} />
          </a>
        </Card>
      </section>

      <p className="system-credit">Разработано командой <strong>Dragons</strong></p>
    </div>
  );
}
