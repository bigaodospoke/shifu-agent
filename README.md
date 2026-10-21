<img src="shifu-agent/icone.png" width="96" align="right" alt="Shifu Agent">

# Shifu Agent

Mod de **servidor** (Fabric e NeoForge 1.21.1) que liga o Minecraft à loja
[Shifu](https://shifu-plataforma.vercel.app). Quando um jogador compra alguma coisa na loja (Pix ou
cartão pelo Mercado Pago), o agente entrega dentro do jogo em poucos segundos.

Também está no [Modrinth](https://modrinth.com/mod/shifu-agent).

```
Jogador -> Loja Shifu -> Mercado Pago -> API da Shifu -> Shifu Agent -> comandos no servidor -> confirmação
```

## O que faz

- Cada produto da loja é uma lista de **comandos de console**
  (`lp user {player} parent add vip`, `give {player} minecraft:diamond 16`, `eco give {player} 1000`...),
  então funciona com qualquer mod ou plugin que tenha comando.
- Se o jogador estiver offline, a entrega espera ele entrar.
- Reconecta sozinho quando a API cai (15s, 30s, 60s... até 5 min).
- Um diário local impede entregar a mesma compra duas vezes, mesmo se o servidor cair no meio.
- Logs no console, em `logs/shifu-agent/` e no painel da loja.
- Comandos de operador: `/shifu status`, `/shifu reconnect`, `/shifu sync` e `/shifu test`.

## Pastas

| Pasta | O que é |
|---|---|
| [`shifu-agent/`](shifu-agent) | versão Fabric, com todo o código e os testes (JUnit) |
| [`shifu-agent-neoforge/`](shifu-agent-neoforge) | versão NeoForge: só troca o ponto de entrada e compila o código da pasta Fabric |

As duas pastas precisam ficar lado a lado.

## Instalação

1. Coloque o jar da [release](../../releases) na pasta `mods` do **servidor** (no Fabric, junto com a
   Fabric API). No cliente não precisa de nada.
2. Abra o servidor uma vez. O mod cria `config/shifu-agent.json`.
3. No painel da Shifu, gere uma API Key e cole `apiKey` e `serverId` no arquivo.
4. No console: `/shifu reconnect` e depois `/shifu status`.

## Compilar

Precisa de JDK 21.

```bash
cd shifu-agent && ./gradlew build              # Fabric
cd shifu-agent-neoforge && ./gradlew build     # NeoForge
```

Detalhes de cada versão nos READMEs das pastas. Histórico de versões no [CHANGELOG](shifu-agent/CHANGELOG.md).
