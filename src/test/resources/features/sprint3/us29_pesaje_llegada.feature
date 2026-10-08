# language: es
@US29
Característica: US29 Pesaje de llegada
  Como encargado de la obra de destino
  quiero pesar el vehículo al llegar y compararlo con el pesaje de salida
  para detectar si se perdió carga en el camino

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

  Escenario: El pesaje de llegada se compara con el de salida dentro de la tolerancia
    Cuando registro el pesaje de llegada de la recepción "R1" con bruto "2530" kg y tara "1050.5" kg
    Entonces el pesaje de llegada tiene neto "1479.5" kg, diferencia "20.5" kg ("1.37" %) y "sí" dentro de la tolerancia de "2.5" %
    Y la recepción "R1" muestra el pesaje de llegada con neto "1479.5" kg

  Escenario: Una diferencia mayor que la tolerancia queda fuera
    Cuando registro el pesaje de llegada de la recepción "R1" con bruto "2400" kg y tara "1000" kg
    Entonces el pesaje de llegada tiene neto "1400" kg, diferencia "100" kg ("6.67" %) y "no" dentro de la tolerancia de "2.5" %

  Escenario: La tara debe ser menor que el peso bruto
    Cuando registro el pesaje de llegada de la recepción "R1" con bruto "1000" kg y tara "1000" kg
    Entonces la operación es rechazada con estado 400 y código "INVALID_WEIGHING"

  Escenario: Hay un solo pesaje de llegada por recepción
    Dado que registré el pesaje de llegada de la recepción "R1" con bruto "2530" kg y tara "1050.5" kg
    Cuando registro el pesaje de llegada de la recepción "R1" con bruto "2500" kg y tara "1000" kg
    Entonces la operación es rechazada con estado 409 y código "ARRIVAL_WEIGHING_ALREADY_RECORDED"

  Escenario: El encargado de almacén no registra el pesaje de llegada
    Dado que "rosa@andina.pe" inició sesión con la contraseña "Almacen123"
    Cuando registro el pesaje de llegada de la recepción "R1" con bruto "2530" kg y tara "1050.5" kg
    Entonces la operación es rechazada con estado 403
