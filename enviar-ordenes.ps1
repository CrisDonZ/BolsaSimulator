# Envía varias órdenes de prueba a order-service.
# -UsuarioId es el "id" que devuelve POST /api/usuarios (auth-service): solo los usuarios registrados
# pueden enviar órdenes. Guárdalo en una variable para no repetirlo:
#
#   $yo = "2be3c35d-6bb5-4dc6-a971-3477cc37dfd9"
#   .\enviar-ordenes.ps1 -UsuarioId $yo                                  -> 3 compras de ANDX y 3 de CAFE a 5000
#   .\enviar-ordenes.ps1 -UsuarioId $yo -Simbolos ANDX                   -> 3 compras solo de ANDX
#   .\enviar-ordenes.ps1 -UsuarioId $yo -Simbolos ANDX -Lado VENTA       -> 3 ventas de ANDX a 5000
#   .\enviar-ordenes.ps1 -UsuarioId $yo -Simbolos ANDX -Lado VENTA -Precio 4900 -Cantidad 50 -Veces 1
#   .\enviar-ordenes.ps1 -UsuarioId $yo -Simbolos ANDX -Tipo MERCADO -Cantidad 80 -Veces 1

param(
    [Parameter(Mandatory = $true, HelpMessage = "id del usuario registrado (lo devuelve POST /api/usuarios)")]
    [string] $UsuarioId,
    [string[]] $Simbolos = @("ANDX", "CAFE"),
    [int] $Veces = 3,
    [ValidateSet("COMPRA", "VENTA")] [string] $Lado = "COMPRA",
    [ValidateSet("LIMITE", "MERCADO")] [string] $Tipo = "LIMITE",
    [long] $Precio = 5000,
    [int] $Cantidad = 100
)

# Saca el motivo del rechazo que explica order-service (campo "detail" del ProblemDetail).
# PowerShell 7 lo deja en ErrorDetails; Windows PowerShell 5.1 hay que leerlo de la respuesta.
# Sin respuesta del servidor (order-service apagado), devuelve el error de conexión.
function Get-MotivoRechazo($errorRegistro) {
    $cuerpo = $errorRegistro.ErrorDetails.Message
    $respuesta = $errorRegistro.Exception.Response
    if (-not $cuerpo -and $respuesta -and $respuesta.GetType().GetMethod("GetResponseStream")) {
        $flujo = $respuesta.GetResponseStream()
        if ($flujo.CanSeek) { $flujo.Position = 0 }
        $cuerpo = (New-Object System.IO.StreamReader($flujo)).ReadToEnd()
    }
    if (-not $cuerpo) {
        return $errorRegistro.Exception.Message
    }
    try { return ($cuerpo | ConvertFrom-Json).detail } catch { return $cuerpo }
}

foreach ($simbolo in $Simbolos) {
    for ($i = 1; $i -le $Veces; $i++) {
        $orden = @{
            usuarioId = $UsuarioId
            simbolo   = $simbolo
            lado      = $Lado
            tipo      = $Tipo
            cantidad  = $Cantidad
        }
        # Una orden a MERCADO no lleva precio
        if ($Tipo -eq "LIMITE") {
            $orden.precioLimite = $Precio
        }

        $detalle = if ($Tipo -eq "LIMITE") { "a $Precio" } else { "a mercado" }
        try {
            $respuesta = Invoke-RestMethod -Method Post -Uri http://localhost:8081/api/ordenes `
                -ContentType "application/json" -Body ($orden | ConvertTo-Json)
            Write-Host "$Lado $Cantidad $simbolo $detalle #$i -> $($respuesta.mensaje)"
        } catch {
            # Por ejemplo, 422 si el usuario no está registrado
            $motivo = Get-MotivoRechazo $_
            Write-Host "$Lado $Cantidad $simbolo $detalle #$i -> RECHAZADA: $motivo" -ForegroundColor Red
            exit 1
        }
    }
}
