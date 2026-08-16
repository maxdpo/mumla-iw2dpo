# Modifica richiesta da IW2DPO: beep di fine trasmissione (PTT)

## Problema

Il gruppo usa Mumla su radio POC in modalità PTT (half-duplex). Al rilascio
del pulsante PTT non veniva trasmesso alcun segnale acustico al canale, per
cui gli altri utenti in ascolto non capivano quando il canale tornava
libero.

## Soluzione

Quando il PTT viene rilasciato, prima di terminare la codifica audio viene
sintetizzato un breve tono ("roger beep") e inviato al canale esattamente
come se fosse audio del microfono: passa quindi attraverso lo stesso
encoder (Opus/CELT), la stessa cifratura/rete e viene sentito da tutti gli
utenti collegati, non solo in locale.

Caratteristiche del beep (facilmente regolabili, vedi
`libraries/humla/src/main/java/se/lublin/humla/protocol/AudioHandler.java`):

- Frequenza: 1500 Hz
- Durata: 150 ms
- Ampiezza: 40% del fondo scala (per non risultare troppo invadente)
- Dissolvenza (fade-in/out) di 10 ms per evitare click

## Attivazione / disattivazione

Aggiunta una nuova opzione nelle impostazioni Audio dell'app, sotto la
sezione "Impostazioni push-to-talk":

- **Beep di fine trasmissione** (chiave preferenza: `release_beep_enabled`)
- Attiva per default (`true`)
- Visibile solo quando la modalità di trasmissione è impostata su
  "Push-to-talk"

## File modificati

- `libraries/humla/src/main/java/se/lublin/humla/protocol/AudioHandler.java`
  — logica di generazione/trasmissione del beep (metodo
  `encodeReleaseBeep()`), chiamato al rilascio del PTT prima di
  `mEncoder.terminate()`.
- `libraries/humla/src/main/java/se/lublin/humla/HumlaService.java` —
  nuovo extra `EXTRAS_RELEASE_BEEP` per propagare l'impostazione
  dall'app alla libreria Humla.
- `app/src/main/java/se/lublin/mumla/Settings.java` — nuova preferenza
  `PREF_RELEASE_BEEP` / `isReleaseBeepEnabled()`.
- `app/src/main/java/se/lublin/mumla/app/ServerConnectTask.java` — invia
  l'impostazione alla connessione iniziale.
- `app/src/main/java/se/lublin/mumla/service/MumlaService.java` — applica
  il cambio impostazione anche "a caldo" (senza riconnettersi).
- `app/src/main/res/xml/settings_audio.xml`,
  `app/src/main/res/values/preference.xml`,
  `app/src/main/res/values-it/preference.xml` — voce di menu (EN + IT).
- `app/build.gradle` — versionName/versionCode di questa build di test.
- `app/src/beta/res/values/strings_notranslate.xml` — nome app
  "Mumla Beta IW2DPO" per il flavor beta (installabile insieme alla
  versione ufficiale, package `se.lublin.mumla.beta`).
- `.github/workflows/build-iw2dpo-beta.yml` — workflow GitHub Actions per
  compilare l'APK di test automaticamente nel cloud (vedi istruzioni).

## Versione di questa build

- versionName: `<versione-base>-IW2DPO-rogerbeep-beta1` (es.
  `3.7.3-21-g477b337-IW2DPO-rogerbeep-beta1[-debug]`)
- Nome app sul dispositivo: **Mumla Beta IW2DPO**
- Package Android (applicationId): `se.lublin.mumla.beta` — può essere
  installata *insieme* alla versione ufficiale di Mumla, senza
  conflitti.

## Nota importante

Questa è una build di test ("beta1"), non rivista né distribuita dal
maintainer ufficiale di Mumla. È pensata solo per il collaudo interno del
gruppo radioamatoriale.
