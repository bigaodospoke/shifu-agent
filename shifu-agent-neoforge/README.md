# Shifu Agent para NeoForge (1.21.1)

Mesma coisa que o [agente Fabric](../shifu-agent/README.md), para servidores **NeoForge 1.21.1**.

Só o ponto de entrada muda (`ShifuAgentNeoForgeMod.java`). Todo o resto (cliente da API, configuração,
fila de entregas, diário anti-duplicação, comandos `/shifu` e logs) é compilado direto de
`../shifu-agent/src/main/java`, então **as duas versões nunca saem de sincronia**.

> As duas pastas (`shifu-agent/` e `shifu-agent-neoforge/`) precisam ficar lado a lado.

## Compilar

Pré-requisito: **Java 21**.

```bash
./gradlew build          # Linux/macOS
gradlew.bat build        # Windows
```

O `.jar` fica em `build/libs/shifu_agent-neoforge-0.2.1.jar` (ou baixe pronto em [Releases](../../../releases)).
Coloque na pasta `mods/` do servidor. Não precisa de nenhum outro mod além do próprio NeoForge.

Para testar num servidor de desenvolvimento: `./gradlew runServer`.

## Configurar

Igual ao Fabric: o mod cria `config/shifu-agent.json` na primeira inicialização; cole a API Key e o
`serverId` gerados no painel da Shifu e rode `/shifu reconnect` e `/shifu status` no console.

## Testes

Os testes automatizados (JUnit) ficam no projeto Fabric, porque testam o código compartilhado:

```bash
cd ../shifu-agent && gradlew.bat test
```
