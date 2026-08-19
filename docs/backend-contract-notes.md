# Contrato Rencia App — resumo de implementação

Base: `https://renciaapp.manus.space`.

## Identificação e acesso

- O aparelho é identificado pelo MAC físico da interface Wi‑Fi.
- Normalizar o MAC para `AA:BB:CC:DD:EE:FF`; aceitar entrada com ou sem separadores, mas enviar ao backend com dois-pontos.
- Validar antes de liberar a Home: `GET /api/device/check?mac={MAC}`.
- Respeitar `found`, `allowed`, `status`, `app`, `urlM3u8`, `urlEpg` e `dataExpiracao`.
- Buscar fontes Xtream em `GET /api/guim.php?mac={MAC}`; resposta principal em `data[]` com `url`, `username`, `password`, `type`.
- As chamadas do novo fluxo devem usar HTTPS.

## Aparência

- Para Ultra Player: `GET /api/v5/ultra-config?mac={MAC}`.
- Usar `app_name`, `logo_url`/`ultra_logo_url`, `banner_url`/`ultra_banner_url`, `background_url`/`ultra_background_url`, `icons.live_tv`, `icons.movies`, `icons.series`, `server_api_url`, `apk_download_url`, `apk_version` quando fornecidos.
- O app deve aceitar campos visuais vazios.

## Presença e conteúdo assistido

- `GET /api/v5/heartbeat?mac={MAC}&current_content={URL_ENCODED_TITULO}`.
- Enviar ao iniciar a reprodução, ao trocar de canal/filme/série/episódio e a cada 60 segundos.
- Nunca enviar `current_content` vazio, nulo ou `undefined`.
- Em falha real do player: `POST /api/v5/playback-failure` JSON `{ "mac": "...", "active_list_number": 1 }`.

## Avisos, failover e comandos

- A cada abertura e 60 segundos: `GET /api/v5/list-notifications?mac={MAC}`.
- Exibir `expiration.modal_title`/`modal_message` apenas uma vez por `modal_key`.
- Exibir novos `notifications[]` de forma amigável e confirmar somente com `POST /api/v5/list-notifications/ack` JSON `{ "mac": "...", "alert_id": 123 }`.
- Quando `playlist_sync_required=true`, buscar novamente `/api/guim.php`, atualizar a lista em memória sem fechar o app, mostrar `playlist_sync_message` e deduplicar por `failover_transition_id`.
- Consultar `GET /api/v5/remote-commands?mac={MAC}` e confirmar com `POST /api/v5/remote-commands/ack` JSON contendo `mac`, `command_id`, `status` e `result_message`.
- Processar comandos como `refresh_playlist`, `switch_playlist`, `update_dns`, `show_message`, `restart_player`, `sync_access`; ignorar vencidos e processar um por vez.

## Atualização

- Para Ultra: `GET /api/v5/ultra-update?mac={MAC}`; usar `url`/`apk_link`, `version`, `update_available`.

## Marca solicitada

- Nome do aplicativo: `SUPREMUS`.
- Autenticação solicitada: MAC físico no formato `AA:BB:CC:DD:EE:FF`, sem login e senha.
