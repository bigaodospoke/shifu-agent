package gg.shifu.agent.delivery;

import gg.shifu.agent.api.ApiModels;
import gg.shifu.agent.log.AgentLogger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** O diário é o que impede entregar a mesma compra duas vezes depois de um crash. */
class DeliveryJournalTest {

    @TempDir
    Path dir;

    @Test
    void lembraDoEstagioDepoisDeReiniciar() {
        AgentLogger logger = new AgentLogger(dir.resolve("logs"));
        DeliveryJournal journal = new DeliveryJournal(dir, logger);

        journal.markStarted("entrega-interrompida");
        journal.markCompleted("entrega-sem-aviso", true,
                List.of(new ApiModels.ActionResult(0, "lp user Steve parent add vip", true, "ok")));

        // Simula o servidor reiniciando: um diário novo lendo o mesmo arquivo
        DeliveryJournal reloaded = new DeliveryJournal(dir, logger);
        assertEquals(DeliveryJournal.Stage.STARTED, reloaded.get("entrega-interrompida").stage);
        assertEquals(DeliveryJournal.Stage.COMPLETED, reloaded.get("entrega-sem-aviso").stage);
        assertTrue(reloaded.get("entrega-sem-aviso").success);
        assertEquals("lp user Steve parent add vip", reloaded.get("entrega-sem-aviso").results.get(0).command);

        reloaded.markAcknowledged("entrega-sem-aviso");
        assertEquals(DeliveryJournal.Stage.ACKNOWLEDGED, new DeliveryJournal(dir, logger).get("entrega-sem-aviso").stage);
    }

    @Test
    void arquivoCorrompidoComecaVazioSemQuebrar() throws IOException {
        Files.writeString(dir.resolve("journal.json"), "{isto nao eh json", StandardCharsets.UTF_8);
        DeliveryJournal journal = new DeliveryJournal(dir, new AgentLogger(dir.resolve("logs")));
        assertNull(journal.get("qualquer"));
    }
}
