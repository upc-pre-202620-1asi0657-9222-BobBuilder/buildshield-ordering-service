# language: es
@US38
Característica: US38 Recepción parcial
  Como encargado de la obra de destino
  quiero guardar lo que ya llegó aunque falte descargar el resto
  para que el stock de la obra se actualice de a poco sin duplicarse

  Antecedentes:
    Dado que la organización "Constructora Andina SAC" con RUC "20123456789" está registrada con el administrador "ana@andina.pe" y contraseña "Segura123"
    Y que "ana@andina.pe" inició sesión con la contraseña "Segura123"
    Y que registré la obra "Torre Norte" del "2026-11-01" al "2027-06-30"
    Y que registré la obra "Torre Sur" del "2026-11-01" al "2027-06-30"
    Y que registré el almacén "Almacén Central"
    Y que registré el almacén "Almacén Callao"
    Y que registré el material "Cemento Portland tipo I" con SKU "CEM-001", unidad "bolsa" y tolerancia de merma "2.5" %
    Y que registré el material "Fierro corrugado 1/2" con SKU "FIE-012", unidad "unidad" y tolerancia de merma "1" %
    Y que creé el usuario "Jorge Huamán" con correo "jorge@andina.pe", rol "encargado de obra" y contraseña "Obra12345"
    Y que creé el usuario "Lucía Paz" con correo "lucia@andina.pe", rol "encargado de obra" y contraseña "Obra12345"
    Y que creé el usuario "Rosa Quispe" con correo "rosa@andina.pe", rol "encargado de almacén" y contraseña "Almacen123"
    Y que creé el usuario "María Ríos" con correo "maria@andina.pe", rol "encargado de almacén" y contraseña "Almacen123"
    Y que asigné a "jorge@andina.pe" a la obra "Torre Norte"
    Y que asigné a "lucia@andina.pe" a la obra "Torre Sur"
    Y que asigné a "rosa@andina.pe" al almacén "Almacén Central"
    Y que asigné a "maria@andina.pe" al almacén "Almacén Callao"
    Y que el almacén "Almacén Central" tiene "100" del material "CEM-001"
    Y que el almacén "Almacén Central" tiene "200" del material "FIE-012"
    Y que "jorge@andina.pe" inició sesión con la contraseña "Obra12345"
    Y que creé el pedido "P1" para la obra "Torre Norte" al almacén "Almacén Central" con:
      | material | cantidad |
      | CEM-001  | 50       |
      | FIE-012  | 120      |
    Y que "rosa@andina.pe" inició sesión con la contraseña "Almacen123"
    Y que aprobé el pedido "P1"
    Y que despaché del pedido "P1" el despacho "D1" con:
      | material | cantidad |
      | CEM-001  | 30       |
    Y que el despacho "D1" salió con el transportista "Transportes Rímac SAC"
    Y que "jorge@andina.pe" inició sesión con la contraseña "Obra12345"
    Y que abrí la recepción "R1" del despacho "D1"

  Escenario: La recepción parcial suma a la obra lo recibido hasta ahora
    Dado que registré que en la recepción "R1" llegaron "10" del material "CEM-001"
    Cuando guardo la recepción parcial de "R1" con la clave "K1"
    Entonces la respuesta tiene estado 200
    Y la recepción "R1" está en estado "RecibidaParcialmente"
    Y la recepción "R1" tiene 1 recepción parcial
    Y el despacho "D1" está en estado "EnTransito"
    Y el stock del material "CEM-001" en la obra "Torre Norte" es "10"

  Escenario: Repetir la recepción parcial con la misma clave no la aplica dos veces
    Dado que registré que en la recepción "R1" llegaron "10" del material "CEM-001"
    Y que guardé la recepción parcial de "R1" con la clave "K1"
    Cuando guardo la recepción parcial de "R1" con la clave "K1"
    Entonces la respuesta es la misma recepción parcial reproducida
    Y la recepción "R1" tiene 1 recepción parcial
    Y el stock del material "CEM-001" en la obra "Torre Norte" es "10"

  Escenario: Cada parcial suma solo la diferencia y la conformidad suma lo que falta
    Dado que registré que en la recepción "R1" llegaron "10" del material "CEM-001"
    Y que guardé la recepción parcial de "R1" con la clave "K1"
    Y que registré que en la recepción "R1" llegaron "25" del material "CEM-001"
    Y que guardé la recepción parcial de "R1" con la clave "K2"
    Y el stock del material "CEM-001" en la obra "Torre Norte" es "25"
    Y que registré que en la recepción "R1" llegaron "29" del material "CEM-001"
    Cuando confirmo la recepción "R1" con la clave "K3"
    Entonces la respuesta tiene estado 200
    Y la recepción "R1" está en estado "Confirmada"
    Y el despacho "D1" está en estado "Recibido"
    Y el stock del material "CEM-001" en la obra "Torre Norte" es "29"
    Cuando consulto el pedido "P1"
    Entonces el pedido muestra por material:
      | material | solicitado | despachado | recibido | pendiente |
      | CEM-001  | 50         | 30         | 29       | 20        |
      | FIE-012  | 120        | 0          | 0        | 120       |

  Escenario: Lo recibido no baja de lo ya ingresado
    Dado que registré que en la recepción "R1" llegaron "10" del material "CEM-001"
    Y que guardé la recepción parcial de "R1" con la clave "K1"
    Cuando registro que en la recepción "R1" llegaron "5" del material "CEM-001"
    Entonces la operación es rechazada con estado 409 y código "RECEIVED_BELOW_STOCKED"

  Escenario: Sin nada nuevo no hay recepción parcial
    Dado que registré que en la recepción "R1" llegaron "10" del material "CEM-001"
    Y que guardé la recepción parcial de "R1" con la clave "K1"
    Cuando guardo la recepción parcial de "R1" con la clave "K2"
    Entonces la operación es rechazada con estado 409 y código "NOTHING_TO_RECEIVE"

  Escenario: El encargado de almacén no guarda recepciones parciales
    Dado que registré que en la recepción "R1" llegaron "10" del material "CEM-001"
    Y que "rosa@andina.pe" inició sesión con la contraseña "Almacen123"
    Cuando guardo la recepción parcial de "R1" con la clave "K1"
    Entonces la operación es rechazada con estado 403
