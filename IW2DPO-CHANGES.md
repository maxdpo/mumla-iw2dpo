This code is in BETA version
Created on Aug 16 2026

Roger beep and Beep volume adjustable
Tested on Phone and HIRO it works


This code need the libraries 
https://github.com/maxdpo/humla-iw2dpo

Change requested by IW2DPO Max Italy : end-of-transmission beep (PTT)
Problem

The group uses Mumla on PoC radios in PTT (half-duplex) mode. 
Upon releasing the PTT button, no acoustic signal was transmitted to 
the channel, leaving listening users unaware when the channel became free.

Solution
When the PTT button is released, a short tone ("roger beep") is synthesized 
right before ending the audio encoding. 
It is sent to the channel just like microphone audio: it goes through the 
same encoder (Opus/CELT) and encryption/network stream, making it audible 
to all connected users, not just locally.

Beep characteristics (easily adjustable in libraries/humla/src/main/java/se/lublin/humla/protocol/AudioHandler.java):

Frequency: 1500 Hz
Duration: 150 ms
Amplitude: adjustable in-app (see "Beep volume" below), 40% full scale by default (to avoid being overly obtrusive)
Fade-in/out: 10 ms to prevent clicking sounds

Activation / Deactivation
Added a new option under the Audio settings in the app, within the "Push-to-talk settings" section:

End of transmission beep (preference key: release_beep_enabled)
Enabled by default (true)

Visible only when the transmission mode is set to "Push-to-talk"

Beep volume
Added a slider right below the beep toggle, in the same "Push-to-talk settings" section:

Transmission end beep volume (preference key: release_beep_volume)
Range 1-100 (percentage of full scale), default 40, same as the original fixed amplitude
Only enabled when "End of transmission beep" is on
Propagated to the Humla library through a new extra, EXTRAS_RELEASE_BEEP_VOLUME

Modified files


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

- versionName: `<versione-base>-IW2DPO-rogerbeep-beta2` (es.
  `3.7.3-21-g477b337-IW2DPO-rogerbeep-beta2[-debug]`)
- Nome app sul dispositivo: **Mumla Beta IW2DPO**
- Package Android (applicationId): `se.lublin.mumla.beta` — può essere
  installata *insieme* alla versione ufficiale di Mumla, senza
  conflitti.

## Nota importante

Questa è una build di test ("beta1"), non rivista né distribuita dal
maintainer ufficiale di Mumla. È pensata solo per il collaudo interno del
gruppo radioamatoriale.
