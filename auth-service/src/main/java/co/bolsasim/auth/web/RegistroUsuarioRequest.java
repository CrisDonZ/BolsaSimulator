package co.bolsasim.auth.web;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Lo que el cliente envía en el cuerpo de POST /api/usuarios (HU-01).
 * Solo se piden los datos necesarios (RNF-30): nada de documento, teléfono ni dirección.
 */
public record RegistroUsuarioRequest(
        @NotBlank @Size(max = 100) String nombre,
        @NotBlank @Pattern(regexp = "[A-Za-z0-9_]{3,30}", message = "debe tener de 3 a 30 letras, números o _")
        String alias,
        @NotBlank @Email @Size(max = 150) String correo,
        // BCrypt solo usa los primeros 72 bytes, por eso el máximo
        @NotNull @Size(min = 8, max = 72, message = "debe tener entre 8 y 72 caracteres") String contrasena,
        @AssertTrue(message = "debes aceptar la política de tratamiento de datos para registrarte")
        boolean aceptaPoliticaDatos) {

    /** Sin la contraseña: así nunca termina en un log por accidente (RNF-21). */
    @Override
    public String toString() {
        return "RegistroUsuarioRequest[nombre=" + nombre + ", alias=" + alias + ", correo=" + correo
                + ", aceptaPoliticaDatos=" + aceptaPoliticaDatos + "]";
    }
}
