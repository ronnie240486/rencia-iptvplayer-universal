# Meu IPTV Player

App Android nativo (Kotlin), **código original, escrito do zero**, para consumo de
listas IPTV via API Xtream Codes (padrão de mercado usado por diversos provedores).

Não usa nenhum código, biblioteca proprietária, asset ou binário de terceiros
comerciais — o player é feito com **Media3/ExoPlayer** (biblioteca aberta mantida
pelo Google), a rede com **Retrofit/OkHttp**, e o carregamento de imagens com **Coil**.

## Telas
- **Ativação** — MAC físico do aparelho no formato `AA:BB:CC:DD:EE:FF`; o backend valida o dispositivo e devolve a lista Xtream atribuída
- **Home** — grade com as 8 seções: Live TV, EPG, VOD, Séries, Favoritos, Conta, Configurações, Busca
- **Live TV / VOD / Séries** — sidebar de categorias (puxadas dinamicamente do provedor via `group-title`/categorias Xtream) + grade de conteúdo
- **Player** — reprodução do stream (HLS/VOD) em tela cheia
- **EPG** — guia de programação do canal (toque longo em um canal na lista)
- **Configurações** — liga/desliga a tarja de cor da categoria ativa (8 cores à escolha) e o fundo com pôster em destaque desfocado
- **Conta** — dados básicos da assinatura (status, vencimento, conexões)
- **Favoritos** — estado vazio por enquanto (favoritar é um próximo passo, ver TODOs)

## Como obter o APK pronto (sem instalar nada)

O workflow do **GitHub Actions** está preservado em
`docs/build-apk.yml`. Para ativá-lo, copie-o para `.github/workflows/build-apk.yml`
em um clone local com uma conta que tenha permissão para publicar workflows; depois,
o GitHub compila o APK automaticamente:

1. Crie um repositório vazio no GitHub e suba este projeto:
   ```bash
   cd IPTVPlayer
   git remote add origin https://github.com/SEU_USUARIO/SEU_REPO.git
   git push -u origin main
   ```
2. No GitHub, vá na aba **Actions** do repositório — o build "Build APK" vai
   rodar sozinho (leva ~3-5 min).
3. Quando terminar (bolinha verde ✅), clique no build → em **Artifacts**,
   baixe `app-debug-apk` — dentro está o `app-debug.apk` pronto pra instalar.

## Como compilar localmente (alternativa)
Requer Android Studio (Giraffe+) ou o Android SDK + Gradle instalados localmente.
```bash
./gradlew assembleDebug
# APK gerado em app/build/outputs/apk/debug/app-debug.apk
```


## Estrutura
```
app/src/main/java/com/meuapp/iptvplayer/
├── data/
│   ├── model/       # modelos de dados (Auth, Category, LiveStream, EPG)
│   └── api/         # Retrofit service + Repository (chamadas Xtream Codes)
├── ui/
│   ├── login/
│   ├── channels/
│   ├── player/      # Media3 ExoPlayer
│   └── epg/
└── util/            # persistência de sessão
```

## Próximos passos sugeridos
- Criptografar os dados de sessão e credenciais recebidas da lista (hoje ficam em SharedPreferences)
- Cache offline da lista de canais (Room)
- Tela de detalhes de série (temporadas/episódios via `get_series_info`) — hoje o toque numa série só mostra um Toast
- Sistema de favoritos (guardar streamId de canais/filmes/séries e listar em Favoritos)
- Tela/diálogo de busca unificada (botão já existe na Home e nas telas de conteúdo, ainda sem ação)
- Fazer a Home carregar um item real em destaque (pôster) para o `BackdropView`, hoje ele usa o fundo padrão de ondas
- Integrar heartbeat, avisos, failover e comandos remotos do contrato Rencia em segundo plano
