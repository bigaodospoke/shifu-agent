# Shifu Agent (Fabric 1.21.1)

Mod de servidor que conecta o Minecraft à plataforma Shifu e entrega compras automaticamente.

> Para servidores **NeoForge 1.21.1**, use [`../shifu-agent-neoforge`](../shifu-agent-neoforge/README.md).
> É o mesmo código: aquele projeto compila os arquivos desta pasta e só troca o ponto de entrada.

## Compilar

Pré-requisito: **Java 21** (ex.: [Adoptium Temurin 21](https://adoptium.net)).

```bash
./gradlew build          # Linux/macOS
gradlew.bat build        # Windows
gradlew.bat test         # testes (JUnit)
```

O `.jar` fica em `build/libs/shifu-agent-0.2.1.jar` (ou baixe pronto em [Releases](../../../releases)). Coloque na pasta `mods/` do servidor junto com a **Fabric API**.

## Configurar

Na primeira inicialização o mod cria `config/shifu-agent.json`:

```json
{
  "apiKey": "",
  "serverId": "",
  "apiUrl": "https://shifu-plataforma.vercel.app",
  "pollIntervalSeconds": 15,
  "debug": false
}
```

1. No painel da Shifu: **Servidores → seu servidor → API Keys → Gerar chave**.
2. Copie `apiKey` e `serverId` para o arquivo.
3. No console: `/shifu reconnect` e depois `/shifu status`.

## Comandos (operador nível 3+)

| Comando | O que faz |
|---|---|
| `/shifu status` | Estado da conexão, último heartbeat, entregas da sessão |
| `/shifu reconnect` | Relê o JSON e reconecta |
| `/shifu sync` | Busca e entrega compras agora |
| `/shifu test` | Valida a chave e mede a latência da API |

## Como funciona

```
[thread Shifu-Agent]                         [thread principal do servidor]
 heartbeat ─┐
 GET /queue ─┼─> valida nick e comandos ──────> jogador online? executa comandos
            │                                   (grava STARTED/COMPLETED no diário)
 POST /deliveries/{id} <────────────────────── resultado de cada comando
 POST /logs
```

- **Reconexão automática:** se a API cair, tenta de novo em 15s, 30s, 60s... até 5 min.
- **Sem entrega duplicada:** `config/shifu-agent/journal.json` lembra o que já foi executado.
- **Logs:** console, `logs/shifu-agent/agent-AAAA-MM-DD.log` e painel da Shifu.

## Publicar no Modrinth e na CurseForge

Página no Modrinth: https://modrinth.com/mod/shifu-agent (projeto `f45HQ7EF`). A descrição fica em
`PAGINA-DO-MOD.md` e o ícone em `icone.png`.

Para lançar uma nova versão:

1. Suba `version` no `gradle.properties` e escreva as novidades no `CHANGELOG.md`.
2. Rode com o token (nunca coloque tokens em arquivos do projeto):

```bash
MODRINTH_TOKEN=mrp_... CURSEFORGE_TOKEN=... ./gradlew publishMods
```

Sem `MODRINTH_TOKEN` o comando roda em modo de teste: monta tudo e não envia nada.
A CurseForge só entra quando `curseforge_project_id` estiver preenchido no `gradle.properties`
(o projeto precisa ser criado antes, pelo site da CurseForge).

## Funciona com qualquer mod

O agente não depende de nenhum mod além da Fabric API. Cada entrega é uma lista de comandos de console
(`lp user Steve parent add vip`, `give Steve minecraft:diamond 16`, `eco give Steve 1000`...),
então qualquer mod ou plugin que tenha comando serve. Antes de vender, teste o comando no console do servidor.
