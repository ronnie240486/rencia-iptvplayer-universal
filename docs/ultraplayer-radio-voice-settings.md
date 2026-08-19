# Funções trazidas do ultraplayer para o SUPREMUS

## Rádio

O ultraplayer usa o Radio Browser como catálogo dinâmico, com busca por tags, filtros de streams verificados e reprodução pelo campo `urlResolved`. A API oficial documenta endpoints como `/json/stations/search` e `/json/stations/bytag/{searchterm}`. O wrapper de referência recomenda `urlResolved`, que resolve playlists M3U/PLS/ASX e redirecionamentos HTTP.

Categorias planejadas no SUPREMUS: Gospel (até 200 estações), Sertaneja (até 100), Pop (até 100), Rock (até 100), Heavy Metal (até 100), Jazz (até 100), Blues (até 100) e Esportes (até 100). Os limites são máximos por categoria; a quantidade real depende da disponibilidade de streams públicos verificados na API.

## Comando de voz

A implementação usa a API nativa `android.speech.SpeechRecognizer` com idioma `pt-BR`, permissão `RECORD_AUDIO`, resultado textual e correspondência normalizada por nome de canal. A frase “Space HD” será comparada com a lista Xtream ao vivo e, quando houver correspondência, o app abre o player do canal. O comando também remove acentos e palavras de ação comuns como “abrir”, “tocar” e “canal”.

## Configurações

A tela atual do SUPREMUS já possui barra de categoria, seleção de cor e fundo com pôster. Serão acrescentados, mantendo o mesmo estilo escuro: Conta, MAC/dispositivo com cópia, Suporte/Revendedor, limpar cache, idioma, controle parental, preferências do player, diagnóstico, versão e sair/trocar dispositivo.

## Fontes

1. Radio Browser API — https://docs.radio-browser.info/
2. Radio Browser API wrapper — https://github.com/ivandotv/radio-browser-api
3. Android SpeechRecognizer — https://developer.android.com/reference/android/speech/SpeechRecognizer
4. Android RecognizerIntent — https://developer.android.com/reference/android/speech/RecognizerIntent
5. Repositório de referência — https://github.com/ronnie240486/ultraplayer
