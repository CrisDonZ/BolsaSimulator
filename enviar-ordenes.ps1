# Envía varias órdenes de prueba a través del gateway (puerto 8080).
# Inicia sesión con tu correo y contraseña (los del registro en POST /api/usuarios) y envía cada orden
# con el token de acceso. Las órdenes no llevan el id del usuario: order-service lo toma del token.
# Los símbolos deben ser de instrumentos activos: los 10 iniciales (ANDX, CAFE, BGUA, TAYR, QUIM, FLOR, COLB,
# ARRA, SALV, PACF) o los que cree el ADMIN. Uno que no existe o está suspendido se rechaza con el motivo.
#
#   $yo = @{ Correo = "cris@correo.com"; Contrasena = "secreta123" }
#   .\enviar-ordenes.ps1 @yo                                             -> 3 compras de ANDX y 3 de CAFE a 5000
#   .\enviar-ordenes.ps1 @yo -Simbolos ANDX                              -> 3 compras solo de ANDX
#   .\enviar-ordenes.ps1 @yo -Simbolos ANDX -Lado VENTA                  -> 3 ventas de ANDX a 5000
#   .\enviar-ordenes.ps1 @yo -Simbolos ANDX -Lado VENTA -Precio 4900 -Cantidad 50 -Veces 1
#   .\enviar-ordenes.ps1 @yo -Simbolos ANDX -Tipo MERCADO -Cantidad 80 -Veces 1

param(
    [Parameter(Mandatory = $true, HelpMessage = "correo con el que te registraste")]
    [string] $Correo,
    [Parameter(Mandatory = $true, HelpMessage = "tu contraseña")]
    [string] $Contrasena,
    [string[]] $Simbolos = @("ANDX", "CAFE"),
    [int] $Veces = 3,
    [ValidateSet("COMPRA", "VENTA")] [string] $Lado = "COMPRA",
    [ValidateSet("LIMITE", "MERCADO")] [string] $Tipo = "LIMITE",
    [long] $Precio = 5000,
    [int] $Cantidad = 100
)

$gateway = "http://localhost:8080"

# Saca el motivo del rechazo que explica el servicio (campo "detail" del ProblemDetail).
# PowerShell 7 lo deja en ErrorDetails; Windows PowerShell 5.1 hay que leerlo de la respuesta.
# Sin respuesta del servidor (gateway apagado), devuelve el error de conexión.
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

try {
    $sesion = Invoke-RestMethod -Method Post -Uri "$gateway/api/auth/login" -ContentType "application/json" `
        -Body (@{ correo = $Correo; contrasena = $Contrasena } | ConvertTo-Json)
} catch {
    Write-Host "No se pudo iniciar sesión: $(Get-MotivoRechazo $_)" -ForegroundColor Red
    exit 1
}
$cabeceras = @{ Authorization = "Bearer $($sesion.tokenAcceso)" }
Write-Host "Sesión iniciada como $Correo"

foreach ($simbolo in $Simbolos) {
    for ($i = 1; $i -le $Veces; $i++) {
        $orden = @{
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
            $respuesta = Invoke-RestMethod -Method Post -Uri "$gateway/api/ordenes" -Headers $cabeceras `
                -ContentType "application/json" -Body ($orden | ConvertTo-Json)
            Write-Host "$Lado $Cantidad $simbolo $detalle #$i -> $($respuesta.mensaje)"
        } catch {
            # Por ejemplo, 401 si el token venció, o 422 si el usuario no está registrado o el instrumento
            # no existe o está suspendido
            $motivo = Get-MotivoRechazo $_
            Write-Host "$Lado $Cantidad $simbolo $detalle #$i -> RECHAZADA: $motivo" -ForegroundColor Red
            exit 1
        }
    }
}
