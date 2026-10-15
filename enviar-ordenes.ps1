# Envía varias órdenes de prueba a order-service.
#
#   .\enviar-ordenes.ps1                                  -> 3 compras de ANDX y 3 de CAFE a 5000
#   .\enviar-ordenes.ps1 -Simbolos ANDX                   -> 3 compras solo de ANDX
#   .\enviar-ordenes.ps1 -Simbolos ANDX -Lado VENTA       -> 3 ventas de ANDX a 5000
#   .\enviar-ordenes.ps1 -Simbolos ANDX -Lado VENTA -Precio 4900 -Cantidad 50 -Veces 1
#   .\enviar-ordenes.ps1 -Simbolos ANDX -Tipo MERCADO -Cantidad 80 -Veces 1

param(
    [string[]] $Simbolos = @("ANDX", "CAFE"),
    [int] $Veces = 3,
    [ValidateSet("COMPRA", "VENTA")] [string] $Lado = "COMPRA",
    [ValidateSet("LIMITE", "MERCADO")] [string] $Tipo = "LIMITE",
    [long] $Precio = 5000,
    [int] $Cantidad = 100
)

foreach ($simbolo in $Simbolos) {
    for ($i = 1; $i -le $Veces; $i++) {
        $orden = @{
            usuarioId = "cris"
            simbolo   = $simbolo
            lado      = $Lado
            tipo      = $Tipo
            cantidad  = $Cantidad
        }
        # Una orden a MERCADO no lleva precio
        if ($Tipo -eq "LIMITE") {
            $orden.precioLimite = $Precio
        }

        $respuesta = Invoke-RestMethod -Method Post -Uri http://localhost:8081/api/ordenes `
            -ContentType "application/json" -Body ($orden | ConvertTo-Json)
        $detalle = if ($Tipo -eq "LIMITE") { "a $Precio" } else { "a mercado" }
        Write-Host "$Lado $Cantidad $simbolo $detalle #$i -> $($respuesta.mensaje)"
    }
}
