-- ─────────────────────────────────────────────────────────────────────────────
-- Seed de demo — PostgreSQL
-- Se ejecuta al arrancar con el perfil "local" (spring.sql.init.platform=postgresql)
--
-- IDEMPOTENTE: borra y recarga los datos de demo en cada arranque.
-- Si corrompiste o borraste datos de demo, simplemente reinicia la app.
--
-- Fechas relativas a CURRENT_DATE (sintaxis PostgreSQL):
--   CURRENT_DATE - INTERVAL 'N days'   ← equivale a DATEADD(DAY, -N, ...) en H2
--   CURRENT_DATE + INTERVAL 'N days'   ← equivale a DATEADD(DAY, +N, ...) en H2
-- ─────────────────────────────────────────────────────────────────────────────

-- Limpieza en orden inverso al de las FK
TRUNCATE TABLE tickets RESTART IDENTITY CASCADE;
TRUNCATE TABLE orders  RESTART IDENTITY CASCADE;
DELETE FROM users;

-- ── Usuarios de demo ──────────────────────────────────────────────────────────
INSERT INTO users (id, name, email) VALUES
  ('u-001', 'Ana García',  'ana@example.com'),
  ('u-002', 'Luis Pérez',  'luis@example.com');

-- ── Pedidos de u-001: escenario de referencia ────────────────────────────────
--    id=123  → en reparto, llega mañana
--    id=456  → pagado, pendiente de envío
--    id=789  → entregado hace un mes
INSERT INTO orders (id, user_id, description, status, order_date, estimated_delivery, tracking_carrier)
OVERRIDING SYSTEM VALUE VALUES
  (123, 'u-001', 'Portátil Lenovo ThinkPad X1', 'IN_DELIVERY',
        CURRENT_DATE - INTERVAL '4 days',  CURRENT_DATE + INTERVAL '1 day',   'SEUR'),
  (456, 'u-001', 'Ratón inalámbrico Logitech',  'PAID',
        CURRENT_DATE - INTERVAL '1 day',   CURRENT_DATE + INTERVAL '3 days',  NULL),
  (789, 'u-001', 'Monitor 27" Dell',            'DELIVERED',
        CURRENT_DATE - INTERVAL '30 days', CURRENT_DATE - INTERVAL '25 days', 'MRW');

-- ── Pedidos de u-002 ─────────────────────────────────────────────────────────
--    id=200  → CREATED (cancelable)
--    id=201  → SHIPPED (no cancelable)
INSERT INTO orders (id, user_id, description, status, order_date, estimated_delivery, tracking_carrier)
OVERRIDING SYSTEM VALUE VALUES
  (200, 'u-002', 'Teclado mecánico Keychron', 'CREATED',
        CURRENT_DATE,                      CURRENT_DATE + INTERVAL '5 days',  NULL),
  (201, 'u-002', 'Auriculares Sony WH-1000', 'SHIPPED',
        CURRENT_DATE - INTERVAL '2 days',  CURRENT_DATE + INTERVAL '2 days',  'Correos');

-- Avanzar la secuencia de orders por encima de los IDs insertados a mano
SELECT setval(pg_get_serial_sequence('orders', 'id'), 1000);

