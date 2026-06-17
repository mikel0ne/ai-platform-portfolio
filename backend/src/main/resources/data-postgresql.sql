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
-- Ana (joven) → tuteo simpático; Luis (mayor) → trato de usted, amable.
INSERT INTO users (id, name, email, age, avatar_url) VALUES
  ('u-001', 'Ana García',  'ana@example.com', 28, '/assets/avatars/ana.jpg'),
  ('u-002', 'Luis Pérez',  'luis@example.com', 70, '/assets/avatars/luis.jpg');

-- ── Pedidos de u-001: escenario de referencia ────────────────────────────────
--    id=123  → en reparto, llega mañana
--    id=456  → pagado, pendiente de envío
--    id=789  → entregado hace un mes
INSERT INTO orders (id, user_id, description, status, order_date, estimated_delivery, tracking_carrier, image_url)
OVERRIDING SYSTEM VALUE VALUES
  (123, 'u-001', 'Portátil Lenovo ThinkPad X1', 'IN_DELIVERY',
        CURRENT_DATE - INTERVAL '4 days',  CURRENT_DATE + INTERVAL '1 day',   'SEUR',
        'https://images.unsplash.com/photo-1496181133206-80ce9b88a853?w=200&q=80'),
  (456, 'u-001', 'Ratón inalámbrico Logitech',  'PAID',
        CURRENT_DATE - INTERVAL '1 day',   CURRENT_DATE + INTERVAL '3 days',  NULL,
        'https://images.unsplash.com/photo-1527864550417-7fd91fc51a46?w=200&q=80'),
  (789, 'u-001', 'Monitor 27" Dell',            'DELIVERED',
        CURRENT_DATE - INTERVAL '30 days', CURRENT_DATE - INTERVAL '25 days', 'MRW',
        'https://images.unsplash.com/photo-1616763355548-1b606f439f86?w=200&q=80');

-- ── Pedidos de u-002 ─────────────────────────────────────────────────────────
--    id=200  → CREATED (cancelable)
--    id=201  → SHIPPED (no cancelable)
--    id=202  → PAID    (cancelable; escenario de confirmación por botón)
INSERT INTO orders (id, user_id, description, status, order_date, estimated_delivery, tracking_carrier, image_url)
OVERRIDING SYSTEM VALUE VALUES
  (200, 'u-002', 'Teclado mecánico Keychron', 'CREATED',
        CURRENT_DATE,                      CURRENT_DATE + INTERVAL '5 days',  NULL,
        'https://images.unsplash.com/photo-1587829741301-dc798b83add3?w=200&q=80'),
  (201, 'u-002', 'Auriculares Sony WH-1000', 'SHIPPED',
        CURRENT_DATE - INTERVAL '2 days',  CURRENT_DATE + INTERVAL '2 days',  'Correos',
        'https://images.unsplash.com/photo-1505740420928-5e560c06d30e?w=200&q=80'),
  (202, 'u-002', 'Impresora Brother DCP-L3550CDW', 'PAID',
        CURRENT_DATE - INTERVAL '1 day',   CURRENT_DATE + INTERVAL '4 days',  NULL,
        'https://images.unsplash.com/photo-1612815154858-60aa4c59eaa6?w=200&q=80');

-- ── Tickets de ejemplo (u-001) ───────────────────────────────────────────────
--    Permiten navegar a /tickets/1, /tickets/2 y /tickets/3. El siguiente ticket
--    que abra el agente tomará el id 4, luego 5, etc.
INSERT INTO tickets (id, user_id, order_id, subject, description, status, created_at)
OVERRIDING SYSTEM VALUE VALUES
  (1, 'u-001', 789,  'Pedido entregado pero no recibido',
      'El pedido figura como entregado pero no lo he recibido. Intentaron entregarlo una vez que no estaba en casa y me llegó un aviso de error de entrega por destinatario ausente, y ya no me han vuelto a contactar.',
      'IN_PROGRESS', CURRENT_TIMESTAMP - INTERVAL '2 days'),
  (2, 'u-001', NULL, 'No puedo acceder a mi cuenta',
      'Al introducir mi correo y contraseña la web me devuelve un error y no consigo iniciar sesión. He probado a restablecer la contraseña pero no llega el correo.',
      'OPEN', CURRENT_TIMESTAMP - INTERVAL '1 day'),
  (3, 'u-001', 123,  'Producto defectuoso al recibirlo',
      'El portátil llegó con la carcasa golpeada y la pantalla no enciende. Solicito sustitución o reembolso.',
      'CLOSED', CURRENT_TIMESTAMP - INTERVAL '5 days');

-- Avanzar las secuencias por encima de los IDs insertados a mano
SELECT setval(pg_get_serial_sequence('orders', 'id'), 1000);
SELECT setval(pg_get_serial_sequence('tickets', 'id'), 3);

