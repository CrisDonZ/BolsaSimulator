package co.bolsasim.auth.web;

import jakarta.validation.constraints.NotBlank;

/** Cuerpo de POST /api/auth/refresh y POST /api/auth/logout: el token de refresco que entregó el login. */
public record TokenRefrescoRequest(@NotBlank String tokenRefresco) {

    /** Sin el token: quien lo tenga puede renovar la sesión, así que no debe quedar en los logs. */
    @Override
    public String toString() {
        return "TokenRefrescoRequest[tokenRefresco=***]";
    }
}
