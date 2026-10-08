# language: es
@US25
Característica: US25 Anular un despacho preparado
  Como encargado del almacén de origen
  quiero anular un despacho que todavía no sale
  para que lo despachado vuelva a lo pendiente del pedido

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

  Escenario: El encargado del almacén anula un despacho preparado y lo despachado vuelve a lo pendiente
    Cuando anulo el despacho "D1" con el motivo "El camión no llegó"
    Entonces la respuesta tiene estado 200
    Y el despacho "D1" está en estado "Anulado"
    Y el despacho "D1" tiene el motivo de anulación "El camión no llegó"
    Y el pedido "P1" está en estado "EnRevision"
    Y el stock reservado del material "CEM-001" en el almacén "Almacén Central" es "50"
    Cuando consulto el pedido "P1"
    Entonces el pedido muestra por material:
      | material | solicitado | despachado | recibido | pendiente |
      | CEM-001  | 50         | 0          | 0        | 50        |
      | FIE-012  | 120        | 0          | 0        | 120       |

  Escenario: Con otro despacho vigente el pedido queda parcialmente atendido
    Dado que despaché del pedido "P1" el despacho "D2" con:
      | material | cantidad |
      | CEM-001  | 20       |
      | FIE-012  | 120      |
    Y el pedido "P1" está en estado "Atendido"
    Cuando anulo el despacho "D2" con el motivo "Se reprograma el envío"
    Entonces la respuesta tiene estado 200
    Y el pedido "P1" está en estado "ParcialmenteAtendido"
    Cuando consulto el pedido "P1"
    Entonces el pedido muestra por material:
      | material | solicitado | despachado | recibido | pendiente |
      | CEM-001  | 50         | 30         | 0        | 20        |
      | FIE-012  | 120        | 0          | 0        | 120       |

  Escenario: Un despacho que ya salió no se anula
    Dado que el despacho "D1" salió con el transportista "Transportes Rímac SAC"
    Cuando anulo el despacho "D1" con el motivo "El camión no llegó"
    Entonces la operación es rechazada con estado 409 y código "INVALID_DISPATCH_TRANSITION"

  Escenario: El motivo debe tener al menos 3 caracteres
    Cuando anulo el despacho "D1" con el motivo "ab"
    Entonces la operación es rechazada con estado 400
    Y el despacho "D1" está en estado "Preparado"

  Escenario: El encargado de obra no anula despachos
    Dado que "jorge@andina.pe" inició sesión con la contraseña "Obra12345"
    Cuando anulo el despacho "D1" con el motivo "No lo necesito"
    Entonces la operación es rechazada con estado 403

  Escenario: El encargado de otro almacén no anula el despacho
    Dado que "maria@andina.pe" inició sesión con la contraseña "Almacen123"
    Cuando anulo el despacho "D1" con el motivo "No corresponde"
    Entonces la operación es rechazada con estado 404
