-- Seed de demo. Fechas relativas a CURRENT_DATE para que el escenario
-- "llegará mañana" funcione cualquier día.

DELETE FROM tickets;
DELETE FROM orders;
DELETE FROM users;

INSERT INTO users (id, name, email) VALUES
  ('u-001', 'Ana García', 'ana@example.com'),
  ('u-002', 'Luis Pérez', 'luis@example.com');

-- u-001: escenario de referencia (dos pedidos recientes, uno en reparto)
INSERT INTO orders (id, user_id, description, status, order_date, estimated_delivery, tracking_carrier) VALUES
  (123, 'u-001', 'Portátil Lenovo ThinkPad X1', 'IN_DELIVERY', DATEADD(DAY, -4, CURRENT_DATE),  DATEADD(DAY, 1, CURRENT_DATE),  'SEUR'),
  (456, 'u-001', 'Ratón inalámbrico Logitech',  'PAID',        DATEADD(DAY, -1, CURRENT_DATE),  DATEADD(DAY, 3, CURRENT_DATE),  NULL),
  (789, 'u-001', 'Monitor 27" Dell',            'DELIVERED',   DATEADD(DAY, -30, CURRENT_DATE), DATEADD(DAY, -25, CURRENT_DATE), 'MRW');

-- u-002: cancelación permitida (200) y rechazada (201)
INSERT INTO orders (id, user_id, description, status, order_date, estimated_delivery, tracking_carrier) VALUES
  (200, 'u-002', 'Teclado mecánico Keychron', 'CREATED', CURRENT_DATE,                   DATEADD(DAY, 5, CURRENT_DATE), NULL),
  (201, 'u-002', 'Auriculares Sony WH-1000',  'SHIPPED', DATEADD(DAY, -2, CURRENT_DATE), DATEADD(DAY, 2, CURRENT_DATE), 'Correos');

-- Las secuencias de identidad deben superar los ids insertados a mano
ALTER TABLE orders ALTER COLUMN id RESTART WITH 1000;
