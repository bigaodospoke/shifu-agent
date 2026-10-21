# Shifu Agent

**Server-side** mod for **Fabric and NeoForge** that connects your Minecraft server to [Shifu](https://shifu-plataforma.vercel.app),
a web store platform for Minecraft servers. When a player buys something in your store (Pix or credit card
through Mercado Pago), Shifu Agent delivers it in-game within seconds.

> **Requires a Shifu account** (30-day free trial). Without an API key the mod stays idle and does nothing.
>
> **Beta:** still being tested. Try it on a development server before using it in production.

## How it works

- Every product in the store is a list of **console commands**, for example
  `lp user {player} parent add vip`, `give {player} minecraft:diamond 16` or `eco give {player} 1000`.
- The agent fetches approved purchases, runs the commands on the server and confirms each delivery in the dashboard.
- **Works with any mod or plugin that has commands.** No dependencies besides Fabric API.

## Features

- Automatic delivery, including for players who join later (a product can wait until the player is online)
- Automatic reconnection with backoff: 15 s, 30 s, 60 s... up to 5 minutes
- Local journal that prevents delivering the same purchase twice, even if the server crashes mid-delivery
- Logs in the console, in `logs/shifu-agent/` and in the Shifu dashboard
- Operator commands (level 3+): `/shifu status`, `/shifu reconnect`, `/shifu sync` and `/shifu test`

## Installation

1. Put the `.jar` in the **server's** `mods/` folder. On Fabric, add **Fabric API** too; on NeoForge nothing
   else is needed. Nothing is needed on the client.
2. Start the server once: the mod creates `config/shifu-agent.json`.
3. In the Shifu dashboard, generate an API key and paste the generated JSON into that file.
4. In the server console, run `/shifu reconnect` and then `/shifu status`.

## External service

This mod talks to the Shifu API over HTTPS using your server's API key.

- **Sends:** agent and Minecraft version, online and maximum player count, the result of each delivery
  (command executed and server response) and the agent's logs.
- **Receives:** the deliveries to run (buyer's username and commands).

The mod does not collect data about your server's players. The only username involved is the buyer's,
which already comes from the store.

---

**Português:** mod de servidor que entrega dentro do jogo as compras da sua loja Shifu (Pix e cartão),
executando comandos de console. Funciona com qualquer mod ou plugin. Requer conta na Shifu.
