package co.bolsasim.gateway;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Hace de auth-service y order-service en las pruebas, con el servidor HTTP que trae el JDK.
 * Publica el JWKS de prueba, responde 200 a todo lo demás y anota cada petición que le llega
 * para comprobar qué dejó pasar el gateway y con qué header Authorization.
 */
class ServiciosFalsos {

    record Peticion(String metodo, String ruta, String authorization) {
    }

    private final HttpServer servidor;
    private final Queue<Peticion> recibidas = new ConcurrentLinkedQueue<>();

    ServiciosFalsos(String jwks) {
        try {
            servidor = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        servidor.createContext("/", intercambio -> {
            String ruta = intercambio.getRequestURI().getPath();
            intercambio.getRequestBody().readAllBytes();
            recibidas.add(new Peticion(intercambio.getRequestMethod(), ruta,
                    intercambio.getRequestHeaders().getFirst("Authorization")));
            byte[] cuerpo = (ruta.endsWith("jwks.json") || ruta.equals("/oauth2/jwks") ? jwks : "{\"servicio\":\"falso\"}")
                    .getBytes(StandardCharsets.UTF_8);
            intercambio.getResponseHeaders().set("Content-Type", "application/json");
            intercambio.sendResponseHeaders(200, cuerpo.length);
            intercambio.getResponseBody().write(cuerpo);
            intercambio.close();
        });
        servidor.start();
    }

    String url() {
        return "http://127.0.0.1:" + servidor.getAddress().getPort();
    }

    /** Las peticiones que llegaron a esa ruta (sin contar las que el gateway hace para leer el JWKS). */
    List<Peticion> recibidas(String ruta) {
        return recibidas.stream().filter(peticion -> peticion.ruta().equals(ruta)).toList();
    }

    void olvidar() {
        recibidas.clear();
    }
}
