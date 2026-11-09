-- Demo seed, H2 dialect (dev profile). Dates are relative to CURRENT_DATE so the scenarios work any day.
DELETE FROM tickets;
DELETE FROM orders;
DELETE FROM users;

-- Ana (young) is addressed informally; Luis (older) formally.
INSERT INTO users (id, name, email, age, avatar_url) VALUES
  ('u-001', 'Ana García', 'ana@example.com', 28, '/assets/avatars/ana.jpg'),
  ('u-002', 'Luis Pérez', 'luis@example.com', 70, '/assets/avatars/luis.jpg');

-- u-001: reference scenario (two recent orders, one out for delivery)
INSERT INTO orders (id, user_id, description, status, order_date, estimated_delivery, tracking_carrier, image_url) VALUES
  (123, 'u-001', 'Portátil Lenovo ThinkPad X1', 'IN_DELIVERY', DATEADD(DAY, -4, CURRENT_DATE),  DATEADD(DAY, 1, CURRENT_DATE),  'SEUR',    'https://images.unsplash.com/photo-1496181133206-80ce9b88a853?w=200&q=80'),
  (456, 'u-001', 'Ratón inalámbrico Logitech',  'PAID',        DATEADD(DAY, -1, CURRENT_DATE),  DATEADD(DAY, 3, CURRENT_DATE),  NULL,      'https://images.unsplash.com/photo-1527864550417-7fd91fc51a46?w=200&q=80'),
  (789, 'u-001', 'Monitor 27" Dell',            'DELIVERED',   DATEADD(DAY, -30, CURRENT_DATE), DATEADD(DAY, -25, CURRENT_DATE), 'MRW',    'https://images.unsplash.com/photo-1616763355548-1b606f439f86?w=200&q=80');

-- u-002: cancellation allowed (200), rejected (201) and allowed via button (202)
INSERT INTO orders (id, user_id, description, status, order_date, estimated_delivery, tracking_carrier, image_url) VALUES
  (200, 'u-002', 'Teclado mecánico Keychron', 'CREATED', CURRENT_DATE,                   DATEADD(DAY, 5, CURRENT_DATE), NULL,      'https://images.unsplash.com/photo-1587829741301-dc798b83add3?w=200&q=80'),
  (201, 'u-002', 'Auriculares Sony WH-1000',  'SHIPPED', DATEADD(DAY, -2, CURRENT_DATE), DATEADD(DAY, 2, CURRENT_DATE), 'Correos', 'https://images.unsplash.com/photo-1505740420928-5e560c06d30e?w=200&q=80'),
  (202, 'u-002', 'Impresora Brother DCP-L3550CDW', 'PAID',    DATEADD(DAY, -1, CURRENT_DATE), DATEADD(DAY, 4, CURRENT_DATE), NULL,      'https://images.unsplash.com/photo-1612815154858-60aa4c59eaa6?w=200&q=80');

-- Sample tickets for u-001 (/tickets/1..3); the agent's next ticket gets id 4.
INSERT INTO tickets (id, user_id, order_id, subject, description, status, created_at) VALUES
  (1, 'u-001', 789,  'Pedido entregado pero no recibido',
      'El pedido figura como entregado pero no lo he recibido. Intentaron entregarlo una vez que no estaba en casa y me llegó un aviso de error de entrega por destinatario ausente, y ya no me han vuelto a contactar.',
      'IN_PROGRESS', DATEADD(DAY, -2, CURRENT_TIMESTAMP)),
  (2, 'u-001', NULL, 'No puedo acceder a mi cuenta',
      'Al introducir mi correo y contraseña la web me devuelve un error y no consigo iniciar sesión. He probado a restablecer la contraseña pero no llega el correo.',
      'OPEN', DATEADD(DAY, -1, CURRENT_TIMESTAMP)),
  (3, 'u-001', 123,  'Producto defectuoso al recibirlo',
      'El portátil llegó con la carcasa golpeada y la pantalla no enciende. Solicito sustitución o reembolso.',
      'CLOSED', DATEADD(DAY, -5, CURRENT_TIMESTAMP));

-- Identity sequences must start above the hand-inserted ids
ALTER TABLE orders ALTER COLUMN id RESTART WITH 1000;
ALTER TABLE tickets ALTER COLUMN id RESTART WITH 4;
