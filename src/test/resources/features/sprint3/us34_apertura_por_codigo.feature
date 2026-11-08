# language: es
@US34
Característica: US34 Abrir la recepción con el código del manifiesto
  Como encargado de la obra de destino
  quiero abrir la recepción escaneando o escribiendo el código del manifiesto
  para empezar a registrar lo que llegó

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

  Escenario: El encargado de obra abre la recepción con el código
    Dado que el despacho "D1" salió con el transportista "Transportes Rímac SAC"
    Y que "jorge@andina.pe" inició sesión con la contraseña "Obra12345"
    Cuando abro la recepción "R1" con el código del manifiesto de "D1"
    Entonces la respuesta tiene estado 201
    Y la recepción "R1" está en estado "EnCurso"

  Escenario: Si ya está abierta, abrirla otra vez por el despacho responde que ya existe
    Dado que el despacho "D1" salió con el transportista "Transportes Rímac SAC"
    Y que "jorge@andina.pe" inició sesión con la contraseña "Obra12345"
    Y que abrí la recepción "R1" con el código del manifiesto de "D1"
    Cuando abro la recepción "R2" del despacho "D1"
    Entonces la operación es rechazada con estado 409 y código "RECEPTION_ALREADY_EXISTS"

  Escenario: El encargado de otra obra no abre la recepción con el código
    Dado que el despacho "D1" salió con el transportista "Transportes Rímac SAC"
    Y que "lucia@andina.pe" inició sesión con la contraseña "Obra12345"
    Cuando abro la recepción "R1" con el código del manifiesto de "D1"
    Entonces la operación es rechazada con estado 404

  Escenario: Un despacho que no salió no se recibe
    Dado que "jorge@andina.pe" inició sesión con la contraseña "Obra12345"
    Cuando abro la recepción "R1" con el código del manifiesto de "D1"
    Entonces la operación es rechazada con estado 409 y código "DISPATCH_NOT_IN_TRANSIT"
