package co.bolsasim.auth.web;

import jakarta.validation.constraints.NotBlank;

/**
 * Lo que el cliente envía en el cuerpo de POST /api/auth/login (HU-02).
 * Solo se exige que vengan los dos campos: cualquier otro error responde "Correo o contraseña incorrectos".
 */
public record LoginRequest(@NotBlank String correo, @NotBlank String contrasena) {

    /** Sin la contraseña: así nunca termina en un log por accidente (RNF-21). */
    @Override
    public String toString() {
        return "LoginRequest[correo=" + correo + "]";
    }
}
