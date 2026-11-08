# language: es
@US43
Característica: US43 Despacho de retorno de una devolución de saldos
  Como encargado de obra o de almacén
  quiero ver la devolución en tránsito con su manifiesto
  para seguirla igual que los demás despachos, aunque la gestione inventario

  Antecedentes:
    Dado que la organización "Constructora Andina SAC" con RUC "20123456789" está registrada con el administrador "ana@andina.pe" y contraseña "Segura123"
    Y que "ana@andina.pe" inició sesión con la contraseña "Segura123"
    Y que registré la obra "Torre Norte" del "2026-11-01" al "2027-06-30"
    Y que registré el almacén "Almacén Central"
    Y que registré el material "Cemento Portland tipo I" con SKU "CEM-001", unidad "bolsa" y tolerancia de merma "2.5" %
    Y que creé el usuario "Jorge Huamán" con correo "jorge@andina.pe", rol "encargado de obra" y contraseña "Obra12345"
    Y que asigné a "jorge@andina.pe" a la obra "Torre Norte"
    Y que inventario inició la devolución "R1" de la obra "Torre Norte" al almacén "Almacén Central" con "12.5" del material "CEM-001"

  Escenario: La devolución en tránsito se ve como un despacho de retorno
    Dado que "jorge@andina.pe" inició sesión con la contraseña "Obra12345"
    Cuando consulto los despachos en tránsito
    Entonces la lista de despachos contiene exactamente:
      | R1 |
    Cuando consulto el despacho "R1"
    Entonces el despacho es un retorno sin pedido en estado "IN_TRANSIT"

  Escenario: Al recibir la devolución el despacho de retorno se cierra
    Dado que inventario recibió la devolución "R1"
    Cuando consulto el despacho "R1"
    Entonces el despacho es un retorno sin pedido en estado "RECEIVED"
    Y el despacho tiene fecha de recepción

  Escenario: Un despacho de retorno no abre recepción en ordering
    Dado que "jorge@andina.pe" inició sesión con la contraseña "Obra12345"
    Cuando intento abrir la recepción del despacho "R1"
    Entonces la operación es rechazada con estado 409 y código "RETURN_DISPATCH_NOT_RECEIVABLE"
