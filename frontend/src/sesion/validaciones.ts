/**
 * Las mismas reglas que RegistroUsuarioRequest en auth-service, para avisar antes de enviar (HU-01). El backend las
 * vuelve a validar: estas solo ahorran un viaje.
 *
 * @return el mensaje del error, o null si el valor sirve
 */
export const validar = {
  nombre(valor: string): string | null {
    if (valor.trim() === '') {
      return 'Escribe tu nombre.';
    }
    return valor.length > 100 ? 'El nombre puede tener hasta 100 caracteres.' : null;
  },

  alias(valor: string): string | null {
    return /^[A-Za-z0-9_]{3,30}$/.test(valor)
      ? null
      : 'El alias debe tener de 3 a 30 letras, números o _ (sin espacios ni tildes).';
  },

  correo(valor: string): string | null {
    if (valor.length > 150) {
      return 'El correo puede tener hasta 150 caracteres.';
    }
    return /^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(valor) ? null : 'Escribe un correo válido, por ejemplo ana@correo.co.';
  },

  contrasena(valor: string): string | null {
    if (valor.length < 8) {
      return 'La contraseña debe tener al menos 8 caracteres.';
    }
    return valor.length > 72 ? 'La contraseña puede tener hasta 72 caracteres.' : null;
  },
};
