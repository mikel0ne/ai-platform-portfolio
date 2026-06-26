package io.github.mikel0ne.autoops.observability;

import io.github.mikel0ne.autoops.observability.model.LogEntry;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Construcción de LogQL, resolución de la ventana de tiempo y aplanado de la respuesta de Loki, con
 * fixtures (sin Loki real, mismo criterio que {@code K8sInspectionMappingTest}).
 */
class LokiGatewayTest {

    @Test
    void buildLogQl_solo_namespace() {
        assertThat(LokiGateway.buildLogQl("autoops", null, null, null))
                .isEqualTo("{namespace=\"autoops\"}");
    }

    @Test
    void buildLogQl_con_pod_contenedor_y_texto() {
        assertThat(LokiGateway.buildLogQl("autoops", "autoops-backend-abc", "app", "ERROR"))
                .isEqualTo("{namespace=\"autoops\",pod=\"autoops-backend-abc\",container=\"app\"} |= \"ERROR\"");
    }

    @Test
    void buildLogQl_escapa_comillas_en_el_texto() {
        assertThat(LokiGateway.buildLogQl("autoops", null, null, "dijo \"hola\""))
                .isEqualTo("{namespace=\"autoops\"} |= \"dijo \\\"hola\\\"\"");
    }

    @Test
    void resolveVentana_sin_nada_son_los_ultimos_15_minutos_hasta_ahora() {
        LokiGateway.Ventana v = LokiGateway.resolveVentana(null, null, null);

        assertThat(Duration.between(v.desde(), v.hasta())).isCloseTo(Duration.ofMinutes(15), Duration.ofSeconds(2));
        assertThat(Duration.between(v.hasta(), Instant.now())).isLessThan(Duration.ofSeconds(2));
    }

    @Test
    void resolveVentana_respeta_ventanaMinutos_personalizada() {
        LokiGateway.Ventana v = LokiGateway.resolveVentana(null, null, 5);

        assertThat(Duration.between(v.desde(), v.hasta())).isCloseTo(Duration.ofMinutes(5), Duration.ofSeconds(2));
    }

    @Test
    void resolveVentana_con_desde_y_hasta_explicitos_admite_milisegundos() {
        LokiGateway.Ventana v = LokiGateway.resolveVentana(
                "2026-06-26T14:32:07.100Z", "2026-06-26T14:32:07.900Z", null);

        assertThat(v.desde()).isEqualTo(Instant.parse("2026-06-26T14:32:07.100Z"));
        assertThat(v.hasta()).isEqualTo(Instant.parse("2026-06-26T14:32:07.900Z"));
    }

    @Test
    void flatten_aplana_y_ordena_cronologicamente_varios_streams() {
        var streamA = new LokiGateway.LokiStream(
                Map.of("namespace", "autoops", "pod", "p1", "container", "app"),
                List.of(List.of("1750949000000000000", "segunda linea")));
        var streamB = new LokiGateway.LokiStream(
                Map.of("namespace", "autoops", "pod", "p2", "container", "app"),
                List.of(List.of("1750948999000000000", "primera linea")));
        var respuesta = new LokiGateway.LokiResponse("success",
                new LokiGateway.LokiData("streams", List.of(streamA, streamB)));

        List<LogEntry> entradas = LokiGateway.flatten(respuesta);

        assertThat(entradas).hasSize(2);
        assertThat(entradas.get(0).linea()).isEqualTo("primera linea");
        assertThat(entradas.get(0).pod()).isEqualTo("p2");
        assertThat(entradas.get(1).linea()).isEqualTo("segunda linea");
        assertThat(entradas.get(0).timestamp()).isBefore(entradas.get(1).timestamp());
    }

    @Test
    void flatten_respuesta_vacia_no_revienta() {
        assertThat(LokiGateway.flatten(null)).isEmpty();
        assertThat(LokiGateway.flatten(new LokiGateway.LokiResponse("success", null))).isEmpty();
    }
}
