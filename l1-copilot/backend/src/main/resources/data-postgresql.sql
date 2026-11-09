-- Demo seed, PostgreSQL dialect. Idempotent: deletes and reloads the demo data.
-- Delete in reverse foreign-key order
TRUNCATE TABLE tickets RESTART IDENTITY CASCADE;
TRUNCATE TABLE orders  RESTART IDENTITY CASCADE;
DELETE FROM users;

-- Demo users
-- Ana (young) is addressed informally; Luis (older) formally.
INSERT INTO users (id, name, email, age, avatar_url) VALUES
  ('u-001', 'Ana García',  'ana@example.com', 28, '/assets/avatars/ana.jpg'),
  ('u-002', 'Luis Pérez',  'luis@example.com', 70, '/assets/avatars/luis.jpg');

-- u-001 orders: reference scenario
--    id=123  out for delivery, arrives tomorrow
--    id=456  paid, waiting to ship
--    id=789  delivered a month ago
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

-- u-002 orders
--    id=200  CREATED (cancellable)
--    id=201  SHIPPED (not cancellable)
--    id=202  PAID    (cancellable; button-confirmation scenario)
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

-- Sample tickets for u-001 (/tickets/1..3); the agent's next ticket gets id 4.
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

-- Identity sequences must start above the hand-inserted ids
SELECT setval(pg_get_serial_sequence('orders', 'id'), 1000);
SELECT setval(pg_get_serial_sequence('tickets', 'id'), 3);

