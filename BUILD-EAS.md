# Build Android

O arquivo `eas.json` disponibiliza dois perfis para a plataforma de build.

| Perfil | Resultado | Uso |
|---|---|---|
| `preview` | APK | Instalação e testes internos. |
| `production` | AAB | Envio para a Google Play. |

Selecione **Android**, informe a referência Git `master` e use o perfil **`preview`** para receber um APK instalável. O projeto é Android nativo em Kotlin; a plataforma de build precisa reconhecer o diretório raiz como projeto Android Gradle.
