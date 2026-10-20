-- Los 10 instrumentos con los que arranca el mercado (supuesto de la especificación). Son empresas ficticias.
-- Al arrancar, market-data-service publica todo el catálogo en instruments.events, así los demás
-- servicios los conocen sin que nadie los cree a mano.
INSERT INTO instrumento (simbolo, nombre, sector, precio_inicial, tick, acciones_emitidas, estado, creado_en, actualizado_en)
VALUES ('ANDX', 'Andex Energía',        'Energía',            5000,  10, 1000000, 'ACTIVO', now(), now()),
       ('CAFE', 'Café Cordillera',      'Agro',              12000,  50,  500000, 'ACTIVO', now(), now()),
       ('BGUA', 'Banco Guadua',         'Financiero',        25000,  50,  400000, 'ACTIVO', now(), now()),
       ('TAYR', 'Tayrona Telecom',      'Telecomunicaciones', 3500,   5, 2000000, 'ACTIVO', now(), now()),
       ('QUIM', 'Cementos Quimbaya',    'Construcción',       8000,  10,  800000, 'ACTIVO', now(), now()),
       ('FLOR', 'Flores de la Sabana',  'Agro',               1500,   5, 3000000, 'ACTIVO', now(), now()),
       ('COLB', 'Aerolínea Colibrí',    'Transporte',         6200,  10,  900000, 'ACTIVO', now(), now()),
       ('ARRA', 'Minera Arrayán',       'Minería',           18000,  50,  300000, 'ACTIVO', now(), now()),
       ('SALV', 'Clínicas Salud Viva',  'Salud',              9500,  10,  600000, 'ACTIVO', now(), now()),
       ('PACF', 'Software Pacífico',    'Tecnología',        40000, 100,  200000, 'ACTIVO', now(), now());
