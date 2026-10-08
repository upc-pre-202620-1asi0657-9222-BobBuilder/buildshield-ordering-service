# language: es
@US32 @US31
Característica: US32 Incidencias en ruta y US31 seguimiento de despachos en tránsito
  Como encargado del almacén de origen
  quiero registrar lo que pasa con un despacho en el camino
  para que la obra y el administrador sepan por qué se demora o llega distinto

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

  Escenario: El encargado del almacén registra una incidencia de un despacho en tránsito
    Cuando registro una incidencia "Retraso" en el despacho "D1" con la observación "Tráfico detenido en la Panamericana Sur"
    Entonces la respuesta tiene estado 201
    Y la incidencia registrada es de tipo "Retraso"
    Y el despacho "D1" tiene 1 incidencia

  Escenario: El seguimiento muestra los despachos en tránsito con sus incidencias en orden
    Dado que registré una incidencia "Retraso" en el despacho "D1" con la observación "Tráfico en la vía"
    Y que registré una incidencia "Cambio de ruta" en el despacho "D1" con la observación "Desvío por obras en la vía"
    Y que "jorge@andina.pe" inició sesión con la contraseña "Obra12345"
    Cuando consulto los despachos en tránsito
    Entonces la lista de despachos contiene exactamente:
      | D1 |
    Y la lista de despachos muestra el despacho "D1" con 2 incidencias
    Cuando consulto las incidencias del despacho "D1"
    Entonces las incidencias son, en orden:
      | Retraso        |
      | Cambio de ruta |

  Escenario: Solo se registran incidencias de un despacho en tránsito
    Dado que despaché del pedido "P1" el despacho "D2" con:
      | material | cantidad |
      | CEM-001  | 10       |
    Cuando registro una incidencia "Accidente" en el despacho "D2" con la observación "Choque leve"
    Entonces la operación es rechazada con estado 409 y código "INVALID_DISPATCH_TRANSITION"

  Escenario: La observación debe tener al menos 3 caracteres
    Cuando registro una incidencia "Otra" en el despacho "D1" con la observación "x"
    Entonces la operación es rechazada con estado 400

  Escenario: El encargado de obra no registra incidencias
    Dado que "jorge@andina.pe" inició sesión con la contraseña "Obra12345"
    Cuando registro una incidencia "Retraso" en el despacho "D1" con la observación "Aún no llega"
    Entonces la operación es rechazada con estado 403

  Escenario: El encargado de otra obra no ve las incidencias
    Dado que registré una incidencia "Retraso" en el despacho "D1" con la observación "Tráfico en la vía"
    Y que "lucia@andina.pe" inició sesión con la contraseña "Obra12345"
    Cuando consulto las incidencias del despacho "D1"
    Entonces la operación es rechazada con estado 404
